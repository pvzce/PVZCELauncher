#!/usr/bin/env python3
"""Verify actual merged or decoded APK manifests, not placeholder substitution."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ANDROID = '{http://schemas.android.com/apk/res/android}'
LEGACY = {
    'com.tungsten.fcl.activity.SplashActivity',
    'com.tungsten.fcl.activity.MainActivity',
    'com.tungsten.fcl.activity.WebActivity',
    'com.tungsten.fcl.activity.ControllerActivity',
    'com.tungsten.fcl.activity.ShellActivity',
    'com.tungsten.fcl.activity.JVMActivity',
    'com.tungsten.fcl.ImportActivity',
    'com.tungsten.fclcore.download.ProcessService',
    'com.mio.download.DownloadService',
    'com.tungsten.fcl.terracotta.TerracottaVPNService',
}


def audit(path):
    manifest = ET.parse(path).getroot()
    package = manifest.get('package')
    assert package in {'com.pvzce.pvzl', 'com.pvzce.pvzl.debug'}, package
    app = manifest.find('application')
    assert app.get(ANDROID + 'name') == 'com.tungsten.fcl.FCLApp'
    launchers = []
    observed_legacy = set()
    for component in app:
        name = component.get(ANDROID + 'name')
        if name in LEGACY:
            assert component.get(ANDROID + 'enabled') == 'false', name
            observed_legacy.add(name)
        for intent in component.findall('intent-filter'):
            for category in intent.findall('category'):
                if category.get(ANDROID + 'name') == 'android.intent.category.LAUNCHER':
                    launchers.append(name)
    assert observed_legacy == LEGACY, LEGACY - observed_legacy
    assert launchers == ['com.tungsten.fcl.generic.GenericLauncherActivity'], launchers
    jvm = next(c for c in app if c.get(ANDROID + 'name') == 'com.tungsten.fcl.generic.GenericJvmActivity')
    assert jvm.get(ANDROID + 'exported') == 'false'
    authorities = [p.get(ANDROID + 'authorities') for p in app.findall('provider')]
    assert len(authorities) == len(set(authorities))
    assert all(a.startswith(package + '.') for a in authorities), authorities
    assert package + '.provider' in authorities
    assert package + '.document.provider' in authorities
    assert package + '.crashreporterinitprovider' in authorities
    permissions = [p.get(ANDROID + 'name') for p in manifest.findall('permission')]
    assert all(p.startswith(package + '.') for p in permissions), permissions
    assert not any('com.tungsten.fcl' in p for p in permissions)
    assert not any('${' in value for e in manifest.iter() for value in e.attrib.values())
    print('PASS', path, '\n package:', package, '\n providers:', ', '.join(authorities),
          '\n custom permissions:', ', '.join(permissions), '\n launcher:', launchers[0])
    return package, set(authorities), set(permissions)


if len(sys.argv) < 2:
    raise SystemExit('Usage: python tools/audit_built_manifests.py MERGED_OR_DECODED_MANIFEST.xml [...]')
seen = {}
for argument in sys.argv[1:]:
    package, providers, permissions = audit(Path(argument))
    for other, (other_providers, other_permissions) in seen.items():
        if other != package:
            assert not providers & other_providers
            assert not permissions & other_permissions
    seen[package] = providers, permissions
