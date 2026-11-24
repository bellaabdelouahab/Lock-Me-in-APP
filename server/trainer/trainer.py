"""Lock Me In trainer (FEATURES 3.20): turns the phone's uploads into judge versions.

Runs next to the relay. The relay only stores what the phone sends (sealed screenshots it cannot open) in the shared
/training volume; this container alone holds the private key (in /private, never shared) and:
  1. makes the RSA key on first start and puts the public half in /training/public.pem for the phone;
  2. every few minutes moves new uploads out of /training/inbox: the screenshot is opened and kept in /private/images, the
     record (scores, label) in /private/records;
  3. when there are new records, trains a judge with scripts/judge/train.py (his labels plus the public frames, 20% held out),
     keeps every version in /private/models/vNNN (judge.json when it passed, report.json always: the backups);
  4. offers the newest version that passed (and has at least MIN_FALSE_ALARMS of his false alarms behind it) in
     /training/offer.json. The phone files it as a change request: it waits in the change gate like any loosening.
/training/status.json has the counts and every version's numbers, for the MCP (training_status). No picture leaves /private.
"""
import datetime
import glob
import json
import os
import re
import sys
import time

SHARED = os.environ.get("TRAINING_DIR", "/training")
PRIVATE = os.environ.get("PRIVATE_DIR", "/private")
JUDGE = os.environ.get("JUDGE_DIR", "/judge")
EVERY_S = int(os.environ.get("TRAIN_EVERY_S", "300"))
MIN_FALSE_ALARMS = int(os.environ.get("MIN_FALSE_ALARMS", "30"))
ID = re.compile(r"^[A-Za-z0-9_-]{1,80}$")


def log(*a):
    print(datetime.datetime.now().isoformat(timespec="seconds"), *a, flush=True)


def write_json(path, data):
    tmp = path + ".tmp"
    with open(tmp, "w") as f:
        json.dump(data, f, indent=2)
    os.replace(tmp, path)


def key():
    from cryptography.hazmat.primitives import serialization as s
    from cryptography.hazmat.primitives.asymmetric import rsa

    path = os.path.join(PRIVATE, "key.pem")
    if not os.path.exists(path):
        k = rsa.generate_private_key(public_exponent=65537, key_size=3072)
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "wb") as f:
            f.write(k.private_bytes(s.Encoding.PEM, s.PrivateFormat.PKCS8, s.NoEncryption()))
        log("made the training key")
    k = s.load_pem_private_key(open(path, "rb").read(), None)
    pub = k.public_key().public_bytes(s.Encoding.PEM, s.PublicFormat.SubjectPublicKeyInfo)
    out = os.path.join(SHARED, "public.pem")
    if not os.path.exists(out) or open(out, "rb").read() != pub:
        open(out + ".tmp", "wb").write(pub)
        os.replace(out + ".tmp", out)
    return k


def open_sealed(sealed: bytes, private_key) -> bytes:
    """core Sealed.seal: u16 wrapped length | RSA-OAEP(SHA-256, MGF1 SHA-256) key | 12-byte IV | AES-GCM body and tag."""
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.asymmetric import padding
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM

    n = int.from_bytes(sealed[:2], "big")
    k = private_key.decrypt(sealed[2:2 + n], padding.OAEP(mgf=padding.MGF1(hashes.SHA256()), algorithm=hashes.SHA256(), label=None))
    return AESGCM(k).decrypt(sealed[2 + n:14 + n], sealed[14 + n:], None)


def take_inbox(private_key) -> int:
    import base64

    taken = 0
    for path in sorted(glob.glob(os.path.join(SHARED, "inbox", "*.json"))):
        try:
            up = json.load(open(path))
            rid = up["record"]["id"]
            if not ID.match(rid) or up.get("label") not in ("WRONG", "CORRECT"):
                raise ValueError("bad upload")
            if up.get("image"):
                img = open_sealed(base64.b64decode(up["image"]), private_key)
                open(os.path.join(PRIVATE, "images", rid + ".jpg"), "wb").write(img)
            write_json(os.path.join(PRIVATE, "records", rid + ".json"), {"record": up["record"], "label": up["label"], "has_image": bool(up.get("image")), "received": up.get("received")})
            taken += 1
        except Exception as e:  # a broken upload is set aside, never trained on
            log("upload set aside:", os.path.basename(path), e)
            os.makedirs(os.path.join(PRIVATE, "rejected"), exist_ok=True)
            os.replace(path, os.path.join(PRIVATE, "rejected", os.path.basename(path)))
            continue
        os.remove(path)
    return taken


def labels():
    rows = []
    for path in sorted(glob.glob(os.path.join(PRIVATE, "records", "*.json"))):
        r = json.load(open(path))
        sc = r["record"].get("scores", {})
        s = {"porn": sc.get("porn", 0), "hentai": sc.get("hentai", 0), "sexy": sc.get("sexy", 0), "drawings": sc.get("drawings", 0),
             "neutral": sc.get("neutral", 1), "anime_suggestive": sc.get("animeSuggestive", 0), "anime_explicit": sc.get("animeExplicit", 0),
             "seductive": sc.get("seductive", 0)}
        rows.append({"id": r["record"]["id"], "wrong": r["label"] == "WRONG", "s": s})
    return rows


def versions():
    return sorted(glob.glob(os.path.join(PRIVATE, "models", "v[0-9][0-9][0-9]")))


def train_once():
    sys.path.insert(0, JUDGE)
    import train as judge_train

    rows = labels()
    previous = versions()
    if previous and json.load(open(os.path.join(previous[-1], "report.json")))["metrics"].get("labels") == len(rows):
        return  # nothing new since the last version
    n = len(previous) + 1
    folder = os.path.join(PRIVATE, "models", f"v{n:03d}")
    os.makedirs(folder)
    lines = []
    result = judge_train.train(rows, judge_train.load_public(os.path.join(JUDGE, "public-frames.csv")),
                               version=f"judge-v{n:03d}-{datetime.date.today()}", log=lines.append)
    m = result["metrics"]
    enough = m.get("false_alarms", 0) >= MIN_FALSE_ALARMS
    # Never offer a version that fixes fewer public false closes than the one already offered (accuracy only goes up).
    best = max((json.load(open(os.path.join(f, "report.json"))) for f in previous), key=lambda r: r["offered"], default=None)
    best = best if best and best["offered"] else None
    not_worse = best is None or m.get("public_false_closes_after", 1 << 30) <= best["metrics"]["public_false_closes_after"]
    offered = result["ok"] and enough and not_worse
    reason = result["reason"] or ("" if enough else f"fewer than {MIN_FALSE_ALARMS} false alarms so far") or \
        ("" if not_worse else f"no better than v{best['version']:03d} on the public held-out screens")
    write_json(os.path.join(folder, "report.json"), {"version": n, "at": datetime.datetime.now().isoformat(timespec="seconds"), "passed": result["ok"],
                                                       "offered": offered, "reason": reason, "metrics": result["metrics"], "log": lines})
    if result["judge"]:
        write_json(os.path.join(folder, "judge.json"), result["judge"])
    if offered:
        write_json(os.path.join(SHARED, "offer.json"), result["judge"])
    log(f"v{n:03d}: passed={result['ok']} offered={offered} {reason} {result['metrics']}")


def status():
    rows = labels()
    out = {"records": len(rows), "false_alarms": sum(r["wrong"] for r in rows), "images": len(glob.glob(os.path.join(PRIVATE, "images", "*.jpg"))),
           "waiting_in_inbox": len(glob.glob(os.path.join(SHARED, "inbox", "*.json"))), "min_false_alarms_to_offer": MIN_FALSE_ALARMS,
           "offer": json.load(open(os.path.join(SHARED, "offer.json")))["version"] if os.path.exists(os.path.join(SHARED, "offer.json")) else None,
           "versions": [{k: v for k, v in json.load(open(os.path.join(f, "report.json"))).items() if k != "log"} for f in versions()]}
    write_json(os.path.join(SHARED, "status.json"), out)


def main():
    for d in (os.path.join(SHARED, "inbox"), os.path.join(PRIVATE, "images"), os.path.join(PRIVATE, "records"), os.path.join(PRIVATE, "models")):
        os.makedirs(d, exist_ok=True)
    os.chmod(PRIVATE, 0o700)
    private_key = key()
    while True:
        try:
            if take_inbox(private_key):
                log("new uploads taken")
            if labels():
                train_once()
            status()
        except Exception as e:
            log("round failed:", repr(e))
        if os.environ.get("TRAIN_ONCE"):
            return
        time.sleep(EVERY_S)


if __name__ == "__main__":
    main()
