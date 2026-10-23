#!/usr/bin/env python3
"""Restore the three bundled libraries from the pinned, checksum-verified release APK.

Usage: python3 tools/vendor_mobileglues.py [path/to/MobileGlues_2.0.0.apk]
The optional local APK avoids a network download. Never vendors plugin code or UI.
"""
import hashlib
import io
import json
from pathlib import Path
import sys
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
metadata = json.loads((ROOT / 'FCL/src/main/assets/licenses/mobileglues/provenance.json').read_text())
if len(sys.argv) > 1:
    data = Path(sys.argv[1]).read_bytes()
else:
    with urllib.request.urlopen(metadata['apk_url'], timeout=60) as response:
        data = response.read()
if hashlib.sha256(data).hexdigest() != metadata['apk_sha256']:
    raise SystemExit('MobileGlues release APK checksum mismatch')
with zipfile.ZipFile(io.BytesIO(data)) as archive:
    # Validate everything before writing any file.
    libraries = {}
    for abi, expected in metadata['libraries'].items():
        library = archive.read(f'lib/{abi}/libmobileglues.so')
        if hashlib.sha256(library).hexdigest() != expected:
            raise SystemExit(f'MobileGlues library checksum mismatch: {abi}')
        libraries[abi] = library
    for abi, library in libraries.items():
        target = ROOT / 'FCL/src/main/jniLibs' / abi / 'libmobileglues.so'
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(library)
        print(f'Verified MobileGlues {metadata["version"]}: {abi}')
