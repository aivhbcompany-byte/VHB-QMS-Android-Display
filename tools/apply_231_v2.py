#!/usr/bin/env python3
from pathlib import Path
import binascii
import re
import shutil
import struct
import zipfile
import zlib

ROOT = Path(__file__).resolve().parents[1]
PAYLOAD = ROOT / "feature_231/source_payload.zip"
PACK = ROOT / "feature_231/default_audio_pack.zip"
MAIN = ROOT / "app/src/main/java/vn/com/vhb/qmsdisplay/MainActivity.java"
BUILD = ROOT / "app/build.gradle"
SETTINGS = ROOT / "settings.gradle"


def recover_local_entries(path: Path):
    """Recover ZIP members from local headers without trusting a damaged central directory."""
    data = path.read_bytes()
    pos = 0
    result = []
    signature = b"PK\x03\x04"
    while True:
        idx = data.find(signature, pos)
        if idx < 0:
            break
        if idx + 30 > len(data):
            raise SystemExit(f"Truncated ZIP local header in {path.name}")
        (sig, version, flags, method, mtime, mdate, crc, csize, usize,
         name_len, extra_len) = struct.unpack_from("<IHHHHHIIIHH", data, idx)
        if sig != 0x04034B50:
            raise SystemExit(f"Invalid local ZIP signature in {path.name}")
        if flags & 0x08:
            raise SystemExit(f"Unsupported data-descriptor ZIP entry in {path.name}")
        name_start = idx + 30
        name_end = name_start + name_len
        payload_start = name_end + extra_len
        payload_end = payload_start + csize
        if payload_end > len(data):
            raise SystemExit(f"Truncated ZIP payload in {path.name}")
        raw_name = data[name_start:name_end]
        try:
            name = raw_name.decode("utf-8")
        except UnicodeDecodeError:
            name = raw_name.decode("cp437")
        compressed = data[payload_start:payload_end]
        if method == 0:
            raw = compressed
        elif method == 8:
            raw = zlib.decompress(compressed, -15)
        else:
            raise SystemExit(f"Unsupported ZIP compression method {method}: {name}")
        if len(raw) != usize:
            raise SystemExit(f"Recovered size mismatch: {name}")
        if (binascii.crc32(raw) & 0xffffffff) != crc:
            raise SystemExit(f"Recovered CRC mismatch: {name}")
        result.append((name.replace("\\", "/"), raw))
        pos = payload_end
    if not result:
        raise SystemExit(f"No recoverable ZIP entries in {path.name}")
    return result


if not PAYLOAD.is_file() or not PACK.is_file() or not MAIN.is_file() or not BUILD.is_file():
    raise SystemExit("2.3.1 payload/audio pack or 2.2.10 baseline files are missing")

build_text = BUILD.read_text(encoding="utf-8")
main_text = MAIN.read_text(encoding="utf-8")
required_2210 = [
    "private void startOperationalMode()",
    "private void initializeOperationalPrefs()",
    "private void syncBootFlags(boolean enabled)",
    "operationalRetryDelayMs",
]
missing = [marker for marker in required_2210 if marker not in main_text]
if missing:
    raise SystemExit("apply_231_v2 requires flattened 2.2.10 architecture, missing: " + ", ".join(missing))
if "versionCode 2021000" not in build_text or "versionName '2.2.10'" not in build_text:
    raise SystemExit("apply_231_v2 requires app/build.gradle version 2.2.10 / 2021000")

allowed_prefixes = (
    "app/src/main/java/vn/com/vhb/qmsdisplay/",
    "app/src/main/assets/qms_audio_bridge/",
)
source_count = 0
for name, raw in recover_local_entries(PAYLOAD):
    if name.endswith("/"):
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
    target.write_bytes(raw)
    source_count += 1

# Rebuild the user's original audio pack from recovered local entries so the APK
# contains a standards-compliant ZIP even if the historical central directory is damaged.
audio_entries = recover_local_entries(PACK)
audio_dir = ROOT / "app/src/main/assets/audio"
audio_dir.mkdir(parents=True, exist_ok=True)
audio_out = audio_dir / "default_audio_pack.zip"
with zipfile.ZipFile(audio_out, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as zf:
    for name, raw in audio_entries:
        if name.endswith("/"):
            continue
        parts = Path(name).parts
        if name.startswith("/") or ".." in parts:
            raise SystemExit(f"Unsafe audio pack entry: {name}")
        zf.writestr(name, raw)

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

orig = ROOT / "app/src/main/java/vn/com/vhb/qmsdisplay/MainActivity.java.orig"
if orig.exists():
    orig.unlink()

new_main = MAIN.read_text(encoding="utf-8")
if 'private static final String VERSION = "2.3.1";' not in new_main:
    raise SystemExit("2.3.1 MainActivity payload validation failed")
for rel in (
    "app/src/main/java/vn/com/vhb/qmsdisplay/AudioPackManager.java",
    "app/src/main/java/vn/com/vhb/qmsdisplay/CounterAudioEngine.java",
    "app/src/main/assets/qms_audio_bridge/manifest.json",
    "app/src/main/assets/qms_audio_bridge/content.js",
    "app/src/main/assets/audio/default_audio_pack.zip",
    "VERSION.txt",
):
    if not (ROOT / rel).is_file():
        raise SystemExit(f"Missing transformed file: {rel}")

print(f"Applied VHB QMS Android Display 2.3.1; recovered {source_count} source files and {len(audio_entries)} audio-pack entries")
