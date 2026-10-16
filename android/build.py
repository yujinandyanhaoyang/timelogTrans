"""使用已下载并校验的官方SDK编译本地验证APK。"""
from pathlib import Path
import subprocess
import zipfile
import tempfile
import shutil
import os
from deployment import generate

ROOT = Path(__file__).resolve().parent
SDK = Path(os.environ.get('ANDROID_SDK_ROOT', os.environ.get('ANDROID_HOME', ROOT.parent / 'tooling' / 'sdk')))
JAVA = Path(os.environ['JAVA_HOME']) / 'bin' if os.environ.get('JAVA_HOME') else Path(shutil.which('javac') or '').resolve().parent
TOOLS = SDK / 'build-tools' / os.environ.get('ANDROID_BUILD_TOOLS_VERSION', '35.0.0')
if not TOOLS.exists() and (SDK / 'android-15').exists():
    TOOLS = SDK / 'android-15'
PLATFORM = SDK / 'platforms/android-35/android.jar'
if not PLATFORM.exists():
    PLATFORM = SDK / 'android-35/android.jar'
DEPLOYMENT = generate(ROOT)
OUT = ROOT / 'build'
OUT.mkdir(exist_ok=True)
CLASSES = OUT / 'classes'
DEX = OUT / 'dex'
CLASSES.mkdir(exist_ok=True)
DEX.mkdir(exist_ok=True)
for old_class in CLASSES.rglob('*.class'):
    old_class.unlink()


def run(*args):
    subprocess.run([str(arg) for arg in args], check=True)


run(JAVA / 'javac.exe', '--release', '8', '-encoding', 'UTF-8', '-classpath', PLATFORM,
    '-d', CLASSES, DEPLOYMENT, *ROOT.glob('src/**/*.java'))
# aapt2 on Windows cannot reliably open Unicode resource paths; stage only build inputs.
with tempfile.TemporaryDirectory(prefix='worktimesync-res-') as temporary:
    stage = Path(temporary)
    shutil.copytree(ROOT / 'res', stage / 'res')
    shutil.copy2(PLATFORM, stage / 'android.jar')
    shutil.copy2(ROOT / 'AndroidManifest.xml', stage / 'AndroidManifest.xml')
    subprocess.run([str(TOOLS / 'aapt2.exe'), 'compile', '--dir', 'res', '-o', 'compiled-res.zip'], cwd=stage, check=True)
    subprocess.run([str(TOOLS / 'aapt2.exe'), 'link', '-I', 'android.jar', '--manifest', 'AndroidManifest.xml', '-o', 'resources.apk', 'compiled-res.zip'], cwd=stage, check=True)
    shutil.copy2(stage / 'resources.apk', OUT / 'resources.apk')
run(TOOLS / 'd8.bat', '--lib', PLATFORM, '--min-api', '26', '--output', DEX, *CLASSES.glob('**/*.class'))
with zipfile.ZipFile(OUT / 'resources.apk', 'a', compression=zipfile.ZIP_DEFLATED) as apk:
    apk.write(DEX / 'classes.dex', 'classes.dex')
run(TOOLS / 'zipalign.exe', '-f', '4', OUT / 'resources.apk', OUT / 'aligned.apk')
key = Path(os.environ.get('WORKTIME_KEYSTORE', OUT / 'debug.keystore'))
if not key.exists():
    run(JAVA / 'keytool.exe', '-genkeypair', '-keystore', key, '-storepass', 'android',
        '-keypass', 'android', '-alias', 'androiddebugkey', '-keyalg', 'RSA', '-keysize', '2048',
        '-validity', '3650', '-dname', 'CN=Local Android Debug')
run(TOOLS / 'apksigner.bat', 'sign', '--ks', key, '--ks-pass', 'pass:android',
    '--out', OUT / 'work-time-sync-validation.apk', OUT / 'aligned.apk')
run(TOOLS / 'apksigner.bat', 'verify', OUT / 'work-time-sync-validation.apk')
print(OUT / 'work-time-sync-validation.apk')
