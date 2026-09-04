#!/usr/bin/env python3
from pathlib import Path
import re
import runpy

root = Path(__file__).resolve().parents[1]
main = root / 'app/src/main/java/vn/com/vhb/qmsdisplay/MainActivity.java'
build = root / 'app/build.gradle'

src = main.read_text(encoding='utf-8')
gradle = build.read_text(encoding='utf-8')

required = [
    'private void startOperationalMode()',
    'private void initializeOperationalPrefs()',
    'private void syncBootFlags(boolean enabled)',
    'operationalRetryDelayMs',
]
missing = [marker for marker in required if marker not in src]
if missing:
    raise SystemExit('Expected flattened 2.2.10 architecture, missing: ' + ', '.join(missing))
if "versionCode 2021000" not in gradle or "versionName '2.2.10'" not in gradle:
    raise SystemExit('Expected Gradle metadata 2.2.10 / 2021000')

src, count = re.subn(
    r'private static final String VERSION = "[^"]+";',
    'private static final String VERSION = "2.2.10";',
    src,
    count=1,
)
if count != 1:
    raise SystemExit('Could not normalize 2.2.10 VERSION metadata')
main.write_text(src, encoding='utf-8')

runpy.run_path(str(root / 'tools/apply_231.py'), run_name='__main__')
