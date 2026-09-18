#!/usr/bin/env python3
"""No external dependencies: compile/test the core and parse Java/XML project files."""
import pathlib
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET

root = pathlib.Path(__file__).resolve().parents[1]
java = shutil.which('java')
if not java:
    raise SystemExit('Serve Java 17 o superiore con modulo jdk.compiler.')
sources = sorted((root / 'app/src/main/java').rglob('*.java'))
core = sorted((root / 'app/src/main/java/it/dave/beatpilot/core').glob('*.java'))
with tempfile.TemporaryDirectory(prefix='beatpilot-check-') as directory:
    subprocess.run([java, 'com.sun.tools.javac.Main', '--release', '17', '-d', directory,
                    *map(str, core), str(root / 'tests/CoreTests.java'),
                    str(root / 'tests/RecordedFrameTests.java'), str(root / 'tests/RecordingTests.java'),
                    str(root / 'tests/TimingRegressionTests.java'), str(root / 'tests/RapidNotesTests.java'),
                    str(root / 'tests/ThemeRegressionTests.java'), str(root / 'tests/PrecisionTests.java'),
                    str(root / 'tests/HoldTests.java'), str(root / 'tests/ContactTests.java'),
                    str(root / 'tests/HoldTailTests.java'), str(root / 'tests/SceneContinuityTests.java'),
                    str(root / 'tests/ReliabilityAudit.java')], check=True)
    subprocess.run([java, '-cp', directory, 'CoreTests'], check=True)
    subprocess.run([java, '-cp', directory, 'RecordedFrameTests', str(root / 'tests/fixtures')], check=True)
    subprocess.run([java, '-cp', directory, 'RecordingTests'], check=True)
    subprocess.run([java, '-cp', directory, 'TimingRegressionTests',
                    str(root / 'tests/fixtures/bot_last_arrow.csv')], check=True)
    subprocess.run([java, '-cp', directory, 'RapidNotesTests', str(root / 'tests/fixtures/rapid')], check=True)
    subprocess.run([java, '-cp', directory, 'ThemeRegressionTests', str(root / 'tests/fixtures/themes')], check=True)
    subprocess.run([java, '-cp', directory, 'PrecisionTests', str(root / 'tests/fixtures/precision')], check=True)
    subprocess.run([java, '-cp', directory, 'HoldTests', str(root / 'tests/fixtures')], check=True)
    subprocess.run([java, '-cp', directory, 'ContactTests', str(root / 'tests/fixtures/contacts')], check=True)
    subprocess.run([java, '-cp', directory, 'HoldTailTests', str(root / 'tests/fixtures/holds/bright-caps')], check=True)
    subprocess.run([java, '-cp', directory, 'SceneContinuityTests'], check=True)
    # Keep the known note-loss cases red. Passing the historical suites must
    # never turn this incomplete working revision into a release candidate.
    reliability = subprocess.run([java, '-cp', directory, 'ReliabilityAudit'], check=False)
subprocess.run([java, str(root / 'tools/SyntaxCheck.java'), *map(str, sources)], check=True)
xml_files = sorted((root / 'app/src/main').rglob('*.xml'))
for path in xml_files:
    ET.parse(path)
print(f'PASS: {len(xml_files)} XML files parse correctly (no resource linking)', flush=True)
manifest = ET.parse(root / 'app/src/main/AndroidManifest.xml').getroot()
android_name = '{http://schemas.android.com/apk/res/android}name'
assert 'android.permission.INTERNET' not in [p.get(android_name) for p in manifest.findall('uses-permission')]
print('PASS: app declares no Internet permission', flush=True)
print('APK build and physical-device tests NOT performed by this check.', flush=True)
if reliability.returncode:
    raise SystemExit('BLOCKED: unresolved note-loss cases; this revision is not ready for release.')
