#!/usr/bin/env python3
"""Opens a Lock Me In review export (.lmib) made in the Screen filter page's review window.

    python3 scripts/judge/decrypt.py lockmein-review-2027-01-10.lmib out/      # asks for the passphrase shown on the phone

Writes out/blocks.json and out/images/<id>.jpg (false alarms only). Needs `pip install cryptography`.
Format (core Bundle): b"LMIB1" | salt 16 | iv 12 | AES-256-GCM ciphertext+tag, key = PBKDF2-SHA256(passphrase, salt, 200000).
"""
import getpass
import io
import os
import sys
import zipfile

MAGIC = b"LMIB1"
ROUNDS = 200_000


def open_bundle(data: bytes, passphrase: str) -> bytes:
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC

    if len(data) <= 33 or data[:5] != MAGIC:
        raise ValueError("not a Lock Me In export")
    salt, iv, body = data[5:21], data[21:33], data[33:]
    key = PBKDF2HMAC(algorithm=hashes.SHA256(), length=32, salt=salt, iterations=ROUNDS).derive(passphrase.encode())
    return AESGCM(key).decrypt(iv, body, None)


def extract(path: str, passphrase: str, out: str) -> zipfile.ZipFile:
    z = zipfile.ZipFile(io.BytesIO(open_bundle(open(path, "rb").read(), passphrase)))
    if out:
        os.makedirs(out, exist_ok=True)
        z.extractall(out)
    return z


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    phrase = os.environ.get("LMI_PASSPHRASE") or getpass.getpass("Passphrase: ")
    target = sys.argv[2] if len(sys.argv) > 2 else os.path.splitext(sys.argv[1])[0]
    names = extract(sys.argv[1], phrase.strip(), target).namelist()
    print(f"{len(names)} files in {target}")
