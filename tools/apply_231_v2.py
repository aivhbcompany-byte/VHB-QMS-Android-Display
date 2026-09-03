#!/usr/bin/env python3
from pathlib import Path
import re
import shutil
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PAYLOAD = ROOT / "feature_231/source_payload.zip"
PACK = ROOT / "feature_231/default_audio_pack.zip"
MAIN = ROOT / "app/src/main/java/vn/com/vhb/qmsdisplay/MainActivity.java"
BUILD = ROOT / "app/build.gradle"
SETTINGS = ROOT / "settings.gradle"

if not PAYLOAD.is_file() or not PACK.is_file() or not MAIN.is_file() or not BUILD.is_file():
    raise SystemExit("2.3.1 payload/audio pack or 2.2.10 baseline files are missing")

build_text = BUILD.read_text(encoding="utf-8")
main_text = MAIN.read_text(encoding="utf-8")
if "versionCode 2021000" not in build_text or "versionName '2.2.10'" not in build_text:
    raise SystemExit("apply_231_v2 requires app/build.gradle version 2.2.10 / 2021000")
if "startOperationalMode()" not in main_text:
    raise SystemExit("apply_231_v2 requires the flattened 2.2.10 Hybrid Fast Boot source")

# Extract only the known source payload. Reject path traversal or unexpected roots.
allowed_prefixes = (
    "app/src/main/java/vn/com/vhb/qmsdisplay/",
    "app/src/main/assets/qms_audio_bridge/",
)
with zipfile.ZipFile(PAYLOAD) as zf:
    for info in zf.infolist():
        name = info.filename.replace("\\", "/")
        if info.is_dir():
            continue
        if name == "VERSION.txt":
            pass
        elif not name.startswith(allowed_prefixes):
            raise SystemExit(f"Unexpected source payload entry: {name}")
        parts = Path(name).parts
        if name.startswith("/") or ".." in parts:
            raise SystemExit(f"Unsafe source payload entry: {name}")
        target = ROOT / name
        target.parent.mkdir(parents=True, exist_ok=True)
        with zf.open(info) as src, target.open("wb") as dst:
            shutil.copyfileobj(src, dst)

# The user's exact audio(1).zip is the built-in default pack.
audio_dir = ROOT / "app/src/main/assets/audio"
audio_dir.mkdir(parents=True, exist_ok=True)
shutil.copy2(PACK, audio_dir / "default_audio_pack.zip")

build_text = BUILD.read_text(encoding="utf-8")
build_text = build_text.replace("versionCode 2021000", "versionCode 2031000")
build_text = build_text.replace("versionName '2.2.10'", "versionName '2.3.1'")
BUILD.write_text(build_text, encoding="utf-8")

if SETTINGS.is_file():
    settings_text = SETTINGS.read_text(encoding="utf-8")
    settings_text = re.sub(
        r'rootProject\.name\s*=\s*"[^"]+"',
        'rootProject.name = "VHB_QMS_Android_Display_2.3.1_GeckoView"',
        settings_text,
    )
    SETTINGS.write_text(settings_text, encoding="utf-8")

# Never carry historical patch backups into a clean source baseline.
orig = ROOT / "app/src/main/java/vn/com/vhb/qmsdisplay/MainActivity.java.orig"
if orig.exists():
    orig.unlink()

# Fail fast if the payload did not land exactly as expected.
new_main = MAIN.read_text(encoding="utf-8")
if 'private static final String VERSION = "2.3.1";' not in new_main:
    raise SystemExit("2.3.1 MainActivity payload validation failed")
for rel in (
    "app/src/main/java/vn/com/vhb/qmsdisplay/AudioPackManager.java",
    "app/src/main/java/vn/com/vhb/qmsdisplay/CounterAudioEngine.java",
    "app/src/main/assets/qms_audio_bridge/manifest.json",
    "app/src/main/assets/qms_audio_bridge/content.js",
    "VERSION.txt",
):
    if not (ROOT / rel).is_file():
        raise SystemExit(f"Missing transformed file: {rel}")

print("Applied VHB QMS Android Display 2.3.1 Audio File Engine from clean source payload")
