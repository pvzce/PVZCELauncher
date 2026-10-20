#!/usr/bin/env python3
"""Source-level identity/isolation audit. This does not replace merged-APK verification."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
android = '{http://schemas.android.com/apk/res/android}'
manifest = ET.parse(root / 'FCL/src/main/AndroidManifest.xml').getroot()
app = manifest.find('application')
build = (root / 'FCL/build.gradle.kts').read_text()
assert 'namespace = "com.tungsten.fcl"' in build
assert 'applicationId = "com.pvzce.pvzl"' in build
assert 'buildConfigField("boolean", "MINECRAFT_FEATURES", "false")' in build
assert 'manifestPlaceholders["minecraftEnabled"] = "false"' in build
assert 'FCL_KEYSTORE_PASSWORD' not in build and 'key-store.jks' not in build and 'debug-key.jks' not in build
for kind, package in [('release', 'com.pvzce.pvzl'), ('debug', 'com.pvzce.pvzl'), ('fordebug', 'com.pvzce.pvzl.debug')]:
    authorities = [p.get(android + 'authorities').replace('${applicationId}', package) for p in app.findall('provider')]
    assert len(authorities) == len(set(authorities))
    assert all(a.startswith(package + '.') for a in authorities)
    assert '"' + package + '.provider"' in build
    print(kind + ': ' + package + '; ' + ', '.join(authorities))
launchers = []
for component in app:
    for intent in component.findall('intent-filter'):
        if any(c.get(android + 'name') == 'android.intent.category.LAUNCHER' for c in intent.findall('category')):
            launchers.append(component.get(android + 'name'))
assert launchers == ['.generic.GenericLauncherActivity']
legacy = ['.activity.SplashActivity', '.activity.MainActivity', '.activity.WebActivity', '.activity.ControllerActivity',
          '.activity.ShellActivity', '.activity.JVMActivity', '.ImportActivity',
          'com.tungsten.fclcore.download.ProcessService', 'com.mio.download.DownloadService', '.terracotta.TerracottaVPNService']
for name in legacy:
    component = next(c for c in app if c.get(android + 'name') == name)
    assert component.get(android + 'enabled') == '${minecraftEnabled}', name
for name in ['.generic.PvzceWorkshopActivity', '.generic.WorkshopLoginActivity']:
    component = next(c for c in app if c.get(android + 'name') == name)
    assert component.get(android + 'exported') == 'false', name
login = next(c for c in app if c.get(android + 'name') == '.generic.WorkshopLoginActivity')
assert login.get(android + 'process') == ':pvzce_login'
assert not manifest.findall('permission'), 'Review any new custom permissions'
for path in (root / 'FCL/src/main/res').glob('values*/strings.xml'):
    resources = ET.parse(path).getroot()
    names = [s.get('name') for s in resources]
    assert len(names) == len(set(names)), path
    for string in resources.findall('string'):
        if string.get('name') == 'app_name': assert string.text == 'PVZCE Launcher'
for path in (root / 'FCL/src/main').rglob('*.xml'):
    ET.parse(path)
for path in (root / 'FCL/src/main/java').rglob('*'):
    if path.suffix in ['.java', '.kt']:
        assert '"/FCL' not in path.read_text(), path
paths = (root / 'FCL/src/main/java/com/tungsten/fclauncher/utils/FCLPath.java').read_text()
assert 'LOG_DIR = new File(context.getFilesDir(), "logs")' in paths
assert 'SHARED_COMMON_DIR = PRIVATE_COMMON_DIR' in paths
network = (root / 'FCL/src/main/java/com/tungsten/fclcore/util/io/NetworkUtils.java').read_text()
assert network.index('LauncherFeatures.minecraftEnabled()') < network.index('connection = url.openConnection()')
for name in ['GenericLauncherActivity.kt', 'GenericJavaLauncher.kt', 'GenericJvmActivity.java', 'JavaLaunchSpec.java']:
    source = (root / 'FCL/src/main/java/com/tungsten/fcl/generic' / name).read_text()
    assert not re.search(r'\b(Profiles|Accounts|Version|GameOption|LauncherHelper)\b', source), name
bridge = root / 'FCL/src/main/java/com/tungsten/fclauncher/bridge/FCLBridge.java'
assert 'package com.tungsten.fclauncher.bridge;' in bridge.read_text()
assert 'Java_com_tungsten_fclauncher_bridge_FCLBridge' in (root / 'FCL/src/main/jni/fcl/fcl_loader.c').read_text()
print('PASS source XML/identity/legacy-entry/network/default-data/JNI-name audit (not a merged Manifest test).')
