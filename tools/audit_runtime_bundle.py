#!/usr/bin/env python3
"""Check the supported runtime bundle in source and, optionally, an assembled APK."""
import argparse
from pathlib import Path
import tarfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
REMOVED = {'libgl4es_114.so', 'libvgpu.so', 'libng_gl4es.so'}
RENDER_LIBS = {'libOSMesa_81.so', 'libOSMesa_8.so', 'libglxshim.so', 'libEGL_mesa.so'}
SUPPORTED = {'armeabi-v7a': 'arm', 'arm64-v8a': 'arm64', 'x86_64': 'x86_64'}


def check_source():
    java = ROOT / 'FCL/src/main/jreAssets/app_runtime/java'
    assert {p.name for p in java.iterdir()} == {'jre25'}, 'Only jre25 may be bundled'
    expected = {'version', 'universal.tar.xz'} | {f'bin-{a}.tar.xz' for a in SUPPORTED.values()}
    assert {p.name for p in (java / 'jre25').iterdir()} == expected
    for arch in SUPPORTED.values():
        with tarfile.open(java / 'jre25' / f'bin-{arch}.tar.xz', 'r:xz') as archive:
            release = archive.extractfile('./release').read().decode()
            assert 'JAVA_VERSION="25' in release, (arch, release)
    assert not (ROOT / 'FCL/libs/NG-GL4ES-release.aar').exists()
    for lib in (ROOT / 'FCL/src/main/jniLibs').glob('*/*.so'):
        assert lib.name not in REMOVED, lib
    print('PASS source: one JRE 25, three supported architectures, removed renderer binaries absent')


def check_apk(path):
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        java = [n for n in names if n.startswith('assets/app_runtime/java/')]
        assert java and {n.split('/')[3] for n in java} == {'jre25'}, java
        assert not any(Path(n).name in REMOVED for n in names), 'Obsolete renderer in APK'
        assert not any(n.startswith('assets/app_runtime/caciocavallo/') for n in names)
        libs = [n for n in names if n.startswith('lib/') and n.endswith('.so')]
        abis = {n.split('/')[1] for n in libs}
        assert abis and abis <= SUPPORTED.keys(), abis
        for abi in abis:
            required = {'version', 'universal.tar.xz', f'bin-{SUPPORTED[abi]}.tar.xz'}
            assert all(f'assets/app_runtime/java/jre25/{n}' in names for n in required), abi
            packaged = {Path(n).name for n in libs if n.startswith(f'lib/{abi}/')}
            assert RENDER_LIBS <= packaged, (abi, RENDER_LIBS - packaged)
        print(f'PASS APK {path}: only JRE 25, supported renderers present, obsolete binaries absent ({", ".join(sorted(abis))})')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', nargs='*', type=Path)
    args = parser.parse_args()
    check_source()
    for apk in args.apk:
        check_apk(apk)
