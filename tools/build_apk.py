#!/usr/bin/env python3
"""Build this dependency-free Java app with official Android SDK tools, without Gradle."""
import hashlib
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile

root = pathlib.Path(__file__).resolve().parents[1]
# A historical suite can pass while known note-loss regressions still fail.
# Never package a revision that fails the complete, current behavioral gate.
subprocess.run([sys.executable, str(root / 'tools/check.py')], cwd=root, check=True)
env = os.environ.copy()
sdk_candidates = [env.get('ANDROID_HOME'), env.get('ANDROID_SDK_ROOT'),
                  str(pathlib.Path.home() / 'Android/Sdk'),
                  str(pathlib.Path.home() / 'Library/Android/sdk'),
                  str(pathlib.Path.home() / 'AppData/Local/Android/Sdk')]
sdk = next((pathlib.Path(p) for p in sdk_candidates if p and
            (pathlib.Path(p) / 'platforms/android-35/android.jar').is_file()), None)
if not sdk:
    raise SystemExit('Installa Android SDK Platform 35 dal SDK Manager di Android Studio, oppure imposta ANDROID_HOME.')
bt = sdk / 'build-tools/35.0.0'
exe = '.exe' if os.name == 'nt' else ''
for item in [bt / ('aapt2' + exe), bt / ('zipalign' + exe), bt / 'lib/d8.jar', bt / 'lib/apksigner.jar']:
    if not item.is_file():
        raise SystemExit('Mancano strumenti SDK: installa Android SDK Build-Tools 35.0.0 dal SDK Manager.')

jdk_candidates = [env.get('JAVA_HOME'), '/opt/android-studio/jbr',
                  '/Applications/Android Studio.app/Contents/jbr/Contents/Home',
                  str(pathlib.Path(env.get('ProgramFiles', 'C:/Program Files')) / 'Android/Android Studio/jbr')]
jdk = next((pathlib.Path(p) for p in jdk_candidates if p and
            (pathlib.Path(p) / 'bin' / ('java' + exe)).is_file()), None)
java = str(jdk / 'bin' / ('java' + exe)) if jdk else shutil.which('java')
if not java:
    raise SystemExit('Serve Java 17 o superiore con il compilatore. Imposta JAVA_HOME sul JDK di Android Studio.')

key = root / 'signing/beatpilot-debug.jks'
if not key.is_file():
    raise SystemExit('Manca la chiave di sviluppo del progetto: estrai anche la cartella signing dall’archivio sorgenti.')

def run(args):
    subprocess.run([str(x) for x in args], cwd=root, env=env, check=True)

run([java, 'com.sun.tools.javac.Main', '-version'])
build_root = root / 'build'
build_root.mkdir(exist_ok=True)
temporary_build = tempfile.TemporaryDirectory(prefix='manual-', dir=build_root)
build = pathlib.Path(temporary_build.name)
ns = 'http://schemas.android.com/apk/res/android'
ET.register_namespace('android', ns)
manifest = ET.parse(root / 'app/src/main/AndroidManifest.xml')
manifest.getroot().set('package', 'it.dave.beatpilot')
manifest.getroot().find('application').set('{' + ns + '}debuggable', 'true')
manifest.write(build / 'AndroidManifest.xml', encoding='utf-8', xml_declaration=True)
android_jar = sdk / 'platforms/android-35/android.jar'
print('Compilo le risorse Android.', flush=True)
run([bt / ('aapt2' + exe), 'compile', '--dir', root / 'app/src/main/res', '-o', build / 'resources.zip'])
run([bt / ('aapt2' + exe), 'link', '-o', build / 'unsigned.apk', '-I', android_jar,
     '--manifest', build / 'AndroidManifest.xml', '--java', build / 'generated',
     '--min-sdk-version', '34', '--target-sdk-version', '35', '--version-code', '11',
     '--version-name', '0.1.15', build / 'resources.zip'])
print('Compilo Java e converto in DEX.', flush=True)
sources = sorted((root / 'app/src/main/java').rglob('*.java')) + sorted((build / 'generated').rglob('*.java'))
classes = build / 'classes'; classes.mkdir()
run([java, 'com.sun.tools.javac.Main', '--release', '17', '-classpath', android_jar,
     '-d', classes, *sources])
dex = build / 'dex'; dex.mkdir()
run([java, '-cp', bt / 'lib/d8.jar', 'com.android.tools.r8.D8', '--lib', android_jar,
     '--min-api', '34', '--output', dex, *sorted(classes.rglob('*.class'))])
with zipfile.ZipFile(build / 'unsigned.apk', 'a') as archive:
    for file in sorted(dex.glob('*.dex')):
        archive.write(file, file.name, compress_type=zipfile.ZIP_DEFLATED)
print('Allineo, firmo e verifico l’APK.', flush=True)
run([bt / ('zipalign' + exe), '-P', '16', '-f', '4', build / 'unsigned.apk', build / 'aligned.apk'])
output = root / 'BeatPilot-0.1.15.apk'
run([java, '-jar', bt / 'lib/apksigner.jar', 'sign', '--ks', key, '--ks-key-alias', 'androiddebugkey',
     '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--out', output, build / 'aligned.apk'])
run([java, '-jar', bt / 'lib/apksigner.jar', 'verify', '--verbose', output])
run([bt / ('zipalign' + exe), '-c', '-P', '16', '4', output])
print('APK generato: ' + str(output))
print('SHA-256: ' + hashlib.sha256(output.read_bytes()).hexdigest())
print('Compilazione verificata. Funzionamento su Beatstar e sul telefono ancora da provare.')
temporary_build.cleanup()
