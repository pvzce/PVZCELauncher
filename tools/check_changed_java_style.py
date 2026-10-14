#!/usr/bin/env python3
"""Run the real repository Checkstyle rules using already resolved Gradle-cache artifacts.
Does not download dependencies, alter rules, or substitute for Android compilation.
"""
import os
from pathlib import Path
import subprocess

repo = Path(__file__).resolve().parents[1]
cache = Path(os.environ.get('GRADLE_USER_HOME', str(Path.home() / '.gradle'))) / 'caches/modules-2/files-2.1'
groups = {'com.puppycrawl.tools', 'info.picocli', 'org.antlr', 'commons-beanutils',
          'com.google.guava', 'commons-logging', 'commons-collections', 'org.reflections',
          'org.javassist', 'org.slf4j', 'com.google.code.findbugs', 'org.checkerframework',
          'com.google.errorprone', 'com.google.j2objc'}
jars = [str(p) for p in cache.rglob('*.jar') if p.relative_to(cache).parts[0] in groups]
if not any('checkstyle-10.12.5.jar' in jar for jar in jars):
    raise SystemExit('Checkstyle 10.12.5 is not cached; run the normal Gradle Checkstyle task to resolve it first.')
files = subprocess.check_output(['git', 'diff', '--name-only', os.environ.get('PVZCE_STYLE_BASE', 'HEAD')], cwd=repo, text=True).splitlines()
files += subprocess.check_output(['git', 'ls-files', '--others', '--exclude-standard'], cwd=repo, text=True).splitlines()
files = sorted({str(repo / f) for f in files if f.endswith('.java') and (repo / f).is_file()})
if not files:
    raise SystemExit('No changed Java files; set PVZCE_STYLE_BASE to the implementation base SHA when checking committed changes.')
command = [os.environ.get('PVZCE_JAVA', 'java'), '-cp', os.pathsep.join(jars),
           'com.puppycrawl.tools.checkstyle.Main', '-c', str(repo / 'config/checkstyle/checkstyle.xml')] + files
print('Checking', len(files), 'changed/new Java files with repository Checkstyle 10.12.5 rules', flush=True)
raise SystemExit(subprocess.call(command, cwd=repo))
