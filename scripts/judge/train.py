#!/usr/bin/env python3
"""Trains the personal judge (spec v3.1 F5) and says whether it may ship.

The three models stay frozen. The judge is a logistic regression on the scores of a frame the rule already calls a hit, answering
only "is this a false alarm". It learns from:
  - his labels from the review window (one or more .lmib exports): "Wrong block" = false alarm, "Correct" = real;
  - public frames (public-frames.csv, the screens of docs/FILTER-EVAL.md): hits on normal screens = false alarm, hits on risky
    screens = real.
Held out, never trained on: 20% of his labels (by id) and the public half 1 (the F2 test set).

Ships only if, on the held-out data, no risky screen the filter closes today stops being closed, and false closes fall.
The cutoff is the lowest that loses no risky screen in training and none of his correct blocks.

    python3 scripts/judge/train.py lockmein-review-*.lmib            # asks for each passphrase (or LMI_PASSPHRASE)
    python3 scripts/judge/train.py --demo                             # pipeline check on public data only

Writes judge-YYYY-MM-DD.json when it may ship; install it from the phone's Screen filter page (waits for the change gate).
Needs numpy (and cryptography for the exports).
"""
import argparse
import csv
import datetime
import getpass
import hashlib
import json
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
# Same names and order as core FilterJudge.FEATURES.
FEATURES = ["porn", "hentai", "sexy", "drawings", "neutral", "anime_suggestive", "anime_explicit", "seductive", "is_drawing", "porn_hentai"]
STRONG = 0.95
AGREE, OF = 3, 4


def features(s):
    drawing = 1.0 if s["drawings"] + s["hentai"] >= 0.5 else 0.0
    return [s["porn"], s["hentai"], s["sexy"], s["drawings"], s["neutral"], s["anime_suggestive"], s["anime_explicit"], s["seductive"], drawing, s["porn"] + s["hentai"]]


def level(s):
    """core FilterRule.TUNED.level"""
    ph = s["porn"] + s["hentai"]
    if s["drawings"] + s["hentai"] >= 0.5:
        return max(ph / 0.50, s["anime_explicit"] / 0.55, min(s["anime_suggestive"] / 0.60, s["seductive"] / 0.45), s["seductive"] / 0.75)
    return max(ph / 0.85, s["sexy"] / 0.85, s["seductive"] / 0.75, min(ph / 0.70, s["seductive"] / 2), min(s["sexy"] / 0.70, s["seductive"] / 2))


def prob(w, b, x):
    return 1.0 / (1.0 + np.exp(-(np.asarray(x) @ w + b)))


def fit(X, y, weight, l2=1.0, iters=50):
    """Logistic regression by Newton steps with L2, numpy only."""
    X = np.asarray(X, float); y = np.asarray(y, float); sw = np.asarray(weight, float)
    Xb = np.hstack([X, np.ones((len(X), 1))])
    beta = np.zeros(Xb.shape[1])
    reg = np.eye(Xb.shape[1]) * l2; reg[-1, -1] = 0
    for _ in range(iters):
        p = 1 / (1 + np.exp(-Xb @ beta))
        g = Xb.T @ (sw * (p - y)) + reg @ beta
        H = (Xb * (sw * p * (1 - p))[:, None]).T @ Xb + reg
        step = np.linalg.solve(H, g)
        beta -= step
        if np.abs(step).max() < 1e-8:
            break
    return beta[:-1], beta[-1]


def load_public(path):
    screens = {}
    for r in csv.DictReader(open(path)):
        s = {k: float(r[k]) for k in ["porn", "hentai", "sexy", "drawings", "neutral", "anime_suggestive", "anime_explicit", "seductive"]}
        screens.setdefault((r["screen"], r["layout"]), {"set": r["set"], "risky": r["risky"] == "1", "half": int(r["half"]), "frames": []})["frames"].append(s)
    return list(screens.values())


def load_labels(paths, demo_screens):
    rows = []
    if demo_screens is not None:
        # Demo: pretend he labelled the hits of the public half 0 (normal = wrong block, risky = correct).
        for sc in demo_screens:
            if sc["half"] != 0:
                continue
            for i, f in enumerate(sc["frames"]):
                if level(f) >= 1:
                    rows.append({"id": f"demo-{id(sc)}-{i}", "wrong": not sc["risky"], "s": f})
        return rows
    sys.path.insert(0, HERE)
    from decrypt import extract
    for p in paths:
        phrase = os.environ.get("LMI_PASSPHRASE") or getpass.getpass(f"Passphrase for {os.path.basename(p)}: ")
        z = extract(p, phrase.strip(), None)
        for rec in json.loads(z.read("blocks.json")):
            if rec.get("label") not in ("WRONG", "CORRECT"):
                continue
            sc = rec["scores"]
            s = {"porn": sc.get("porn", 0), "hentai": sc.get("hentai", 0), "sexy": sc.get("sexy", 0), "drawings": sc.get("drawings", 0),
                 "neutral": sc.get("neutral", 1), "anime_suggestive": sc.get("animeSuggestive", 0), "anime_explicit": sc.get("animeExplicit", 0),
                 "seductive": sc.get("seductive", 0)}
            rows.append({"id": rec["id"], "wrong": rec["label"] == "WRONG", "s": s})
    return rows


def held_out(row_id):
    return int(hashlib.sha256(row_id.encode()).hexdigest(), 16) % 5 == 0


def closes(frames, judge=None):
    """The app's decision on four frames of one screen: 3 of 4 hits, or one very strong."""
    hits = []
    for f in frames:
        hit = level(f) >= 1
        if hit and judge is not None and f["porn"] + f["hentai"] < STRONG and prob(judge[0], judge[1], features(f)) >= judge[2]:
            hit = False
        hits.append(hit)
        if hit and f["porn"] + f["hentai"] >= STRONG:
            return True
    return sum(hits) >= AGREE


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("exports", nargs="*")
    ap.add_argument("--public", default=os.path.join(HERE, "public-frames.csv"))
    ap.add_argument("--demo", action="store_true")
    ap.add_argument("--out", default=None)
    ap.add_argument("--margin", type=float, default=0.02, help="cutoff at least this far above his highest correct block in training")
    a = ap.parse_args()
    if not a.exports and not a.demo:
        sys.exit("give one or more .lmib exports, or --demo")

    public = load_public(a.public)
    labels = load_labels(a.exports, public if a.demo else None)
    mine_train = [r for r in labels if not held_out(r["id"])]
    mine_test = [r for r in labels if held_out(r["id"])]
    wrong = sum(r["wrong"] for r in labels)
    print(f"his labels: {len(labels)} ({wrong} false alarms, {len(labels) - wrong} correct); held out {len(mine_test)}")
    if wrong < 30 and not a.demo:
        print("note: the spec waits for about 150 false alarms; with fewer the judge will likely not pass.")

    X, y, w = [], [], []
    for r in mine_train:
        X.append(features(r["s"])); y.append(1 if r["wrong"] else 0); w.append(3.0)  # his own screens count more
    for sc in public:
        if sc["half"] != 0:
            continue
        for f in sc["frames"]:
            if level(f) >= 1:
                X.append(features(f)); y.append(0 if sc["risky"] else 1); w.append(1.0)
    y_arr = np.asarray(y)
    if y_arr.min() == y_arr.max():
        sys.exit("training needs both false alarms and real hits")
    # Balance the two classes.
    w = np.asarray(w); pos = y_arr == 1
    w[pos] *= w[~pos].sum() / max(w[pos].sum(), 1e-9)
    wt, b = fit(X, y, w)

    # Cutoff: the lowest that, on the training data, loses no risky screen the filter closes today and drops none of his
    # correct blocks. Never below 0.5.
    train_risky = [sc for sc in public if sc["half"] == 0 and sc["risky"]]
    mine_real_train = [features(r["s"]) for r in mine_train if not r["wrong"]]
    real_max = float(prob(wt, b, mine_real_train).max()) if mine_real_train else 0.0
    cutoff = 0.999
    for c in np.arange(0.5, 0.999, 0.005):
        if c < real_max + a.margin:
            continue
        if all(closes(sc["frames"], (wt, b, c)) or not closes(sc["frames"]) for sc in train_risky):
            cutoff = float(c)
            break
    judge = (wt, b, cutoff)

    # Held-out check, screen by screen on the public half 1, and frame by frame on his held-out labels.
    test = [sc for sc in public if sc["half"] == 1]
    risky = [sc for sc in test if sc["risky"]]; normal = [sc for sc in test if not sc["risky"]]
    lost = sum(closes(sc["frames"]) and not closes(sc["frames"], judge) for sc in risky)
    fc_before = sum(closes(sc["frames"]) for sc in normal); fc_after = sum(closes(sc["frames"], judge) for sc in normal)
    mine_wrong = [r for r in mine_test if r["wrong"]]; mine_real = [r for r in mine_test if not r["wrong"]]
    mine_fixed = sum(prob(wt, b, features(r["s"])) >= cutoff for r in mine_wrong)
    mine_lost = sum(prob(wt, b, features(r["s"])) >= cutoff for r in mine_real)
    print(f"cutoff {cutoff:.3f}")
    print(f"public held-out: risky screens closed {sum(closes(sc['frames']) for sc in risky)}/{len(risky)} -> lost {lost}; "
          f"false closes on normal {fc_before}/{len(normal)} -> {fc_after}")
    print(f"his held-out: false alarms dropped {mine_fixed}/{len(mine_wrong)}, real blocks dropped {mine_lost}/{len(mine_real)}")

    ok = lost == 0 and mine_lost == 0 and (fc_after < fc_before or mine_fixed > 0)
    if not ok:
        print("NOT SHIPPING: " + ("it would miss real content" if lost or mine_lost else "false closes do not fall"))
        return 1
    version = f"judge-{datetime.date.today()}" + ("-demo" if a.demo else "")
    out = a.out or f"{version}.json"
    json.dump({"version": version, "weights": [round(float(v), 6) for v in wt], "bias": round(float(b), 6), "cutoff": round(cutoff, 6)}, open(out, "w"), indent=2)
    print(f"MAY SHIP: wrote {out}. Install it from the phone's Screen filter page; keep the previous file to roll back.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
