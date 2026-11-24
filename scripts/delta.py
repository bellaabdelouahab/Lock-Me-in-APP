#!/usr/bin/env python3
"""Small updates (FEATURES 3.19): a patch that turns the APK a phone already has into the new one.

    delta.py make OLD.apk NEW.apk OUT.patch    writes the patch, then checks it rebuilds NEW byte for byte
    delta.py apply OLD.apk PATCH OUT.apk       rebuilds the new APK (what the phone does, for checking by hand)
    delta.py index DIR                         the "patches" part of version.json for the patches in DIR

The format is bsdiff's (Colin Percival) with zlib in place of bzip2, so the phone needs only java.util.zip. Big-endian:
  "LMIDELTA" | u32 version (1) | i64 new size | sha256 old (32) | sha256 new (32) | i64 ctrl bytes | i64 diff bytes | i64 extra bytes
  | ctrl (zlib) | diff (zlib) | extra (zlib)
ctrl is triples of i64 (x, y, z): add x diff bytes to x old bytes, copy y extra bytes, move the old position by z.
The phone checks the old APK's sha256 before and the new one's after; Android checks the signature on install.
"""
import hashlib
import struct
import sys
import zlib


MAGIC = b"LMIDELTA"
HEADER = struct.Struct(">8sIq32s32sqqq")


def make(old: bytes, new: bytes) -> bytes:
    import bsdiff4.core  # pip install bsdiff4 numpy (only make and apply need them)

    controls, diff, extra = bsdiff4.core.diff(old, new)
    # bsdiff4 hands its triples in bsdiff's own int64 encoding (sign and magnitude, little-endian); the phone reads plain ones.
    ctrl = b"".join(struct.pack(">q", bsdiff4.core.decode_int64(controls[i:i + 8])) for i in range(0, len(controls), 8))
    c, d, e = (zlib.compress(b, 9) for b in (ctrl, diff, extra))
    head = HEADER.pack(MAGIC, 1, len(new), hashlib.sha256(old).digest(), hashlib.sha256(new).digest(), len(c), len(d), len(e))
    return head + c + d + e


def apply(old: bytes, patch: bytes) -> bytes:
    import numpy

    magic, version, size, old_sha, new_sha, lc, ld, le = HEADER.unpack_from(patch)
    if magic != MAGIC or version != 1:
        raise ValueError("not a Lock Me In patch")
    if hashlib.sha256(old).digest() != old_sha:
        raise ValueError("the old APK is not the one this patch is for")
    at = HEADER.size
    ctrl = zlib.decompress(patch[at:at + lc]); at += lc
    diff = zlib.decompress(patch[at:at + ld]); at += ld
    extra = zlib.decompress(patch[at:at + le])
    out = bytearray()
    old_pos = di = ei = 0
    for i in range(0, len(ctrl), 24):
        x, y, z = struct.unpack_from(">qqq", ctrl, i)
        out += (numpy.frombuffer(diff, numpy.uint8, x, di) + numpy.frombuffer(old, numpy.uint8, x, old_pos)).tobytes()
        di += x; old_pos += x
        out += extra[ei:ei + y]; ei += y
        old_pos += z
    if len(out) != size or hashlib.sha256(out).digest() != new_sha:
        raise ValueError("the rebuilt APK does not match")
    return bytes(out)


def index(folder: str) -> dict:
    """{kind: [{from, file, size}]} from files named lock-me-in-<kind>-from-<versionCode>.patch."""
    import json, os, re
    out: dict = {}
    for name in sorted(os.listdir(folder)):
        m = re.fullmatch(r"lock-me-in-(.+)-from-(\d+)\.patch", name)
        if m:
            out.setdefault(m[1], []).append({"from": int(m[2]), "file": name, "size": os.path.getsize(os.path.join(folder, name))})
    print(json.dumps(out))
    return out


def main() -> None:
    if sys.argv[1:2] == ["index"]:
        index(sys.argv[2])
        return
    cmd, a, b, out = sys.argv[1:5]
    old = open(a, "rb").read()
    if cmd == "make":
        new = open(b, "rb").read()
        patch = make(old, new)
        if apply(old, patch) != new:
            raise SystemExit("the patch does not rebuild the new APK")
        open(out, "wb").write(patch)
        print(f"{out}: {len(patch)} bytes for {len(new)}")
    elif cmd == "apply":
        open(out, "wb").write(apply(old, open(b, "rb").read()))
    else:
        raise SystemExit(__doc__)


if __name__ == "__main__":
    main()
