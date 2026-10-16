"""Run the three current JVM tests; supply an org.json jar via JSON_JAR."""
from pathlib import Path
import os
import shutil
import subprocess
from deployment import generate

ROOT = Path(__file__).resolve().parent
java = Path(os.environ['JAVA_HOME']) / 'bin' if os.environ.get('JAVA_HOME') else Path(shutil.which('javac') or '').resolve().parent
jar = Path(os.environ.get('JSON_JAR', ROOT.parent / 'tooling/json-20240303.jar')).resolve()
if not jar.is_file():
    raise SystemExit('Set JSON_JAR to an org.json:json 20240303 jar before running tests.')
out = ROOT / 'build/test-classes'
out.mkdir(parents=True, exist_ok=True)
names = ['BackupLocator', 'MonthlySummary', 'FeishuClient']
sources = [ROOT / f'src/cn/personal/worktimesync/{name}.java' for name in names]
tests = [ROOT / f'tests/cn/personal/worktimesync/{name}Test.java' for name in names]
subprocess.run([str(java / 'javac.exe'), '-encoding', 'UTF-8', '-cp', str(jar), '-d', str(out), str(generate(ROOT, test=True)), *map(str, sources + tests)], check=True)
for name in names:
    subprocess.run([str(java / 'java.exe'), '-cp', str(out) + os.pathsep + str(jar), 'cn.personal.worktimesync.' + name + 'Test'], check=True)
