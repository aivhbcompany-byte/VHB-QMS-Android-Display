from pathlib import Path
import re

java_path = Path('app/src/main/java/vn/com/vhb/qmsdisplay/MainActivity.java')
gradle_path = Path('app/build.gradle')

src = java_path.read_text(encoding='utf-8')
required_2210 = [
    'private void startOperationalMode()',
    'private void initializeOperationalPrefs()',
    'private void syncBootFlags(boolean enabled)',
    'operationalRetryDelayMs',
]
missing = [marker for marker in required_2210 if marker not in src]
if missing:
    raise SystemExit('Expected VHB QMS Android Display 2.2.10 architecture, missing: ' + ', '.join(missing))

src, count = re.subn(
    r'private static final String VERSION = "[^"]+";',
    'private static final String VERSION = "2.3.1";',
    src,
    count=1,
)
if count != 1:
    raise SystemExit('Could not normalize MainActivity VERSION')

src, _ = re.subn(
    r'VHB QMS Display 2\.2\.(?:9|10)\s*•\s*[^"\\n]+',
    'VHB QMS Display 2.3.1 • WAV COUNTER AUDIO',
    src,
    count=1,
)

field_anchor = '    private ConnectivityManager.NetworkCallback networkCallback;\n'
if field_anchor not in src:
    raise SystemExit('Could not find networkCallback field')
src = src.replace(field_anchor, field_anchor + '    private CounterAudioEdge counterAudioEdge;\n', 1)

start_anchor = '''        try {\n            initializeOperationalPrefs();\n            ensureRuntime();\n            createSession();\n'''
start_new = '''        try {\n            initializeOperationalPrefs();\n            if (counterAudioEdge == null) counterAudioEdge = new CounterAudioEdge(this, main);\n            counterAudioEdge.initialize(prefs);\n            ensureRuntime();\n            createSession();\n'''
if start_anchor not in src:
    raise SystemExit('Could not find 2.2.10 startOperationalMode')
src = src.replace(start_anchor, start_new, 1)

session_anchor = '''        session.open(runtime);\n        view.setSession(session);\n        session.setActive(true);\n    }\n'''
session_new = '''        session.open(runtime);\n        view.setSession(session);\n        session.setActive(true);\n        if (counterAudioEdge != null) {\n            counterAudioEdge.bind(runtime, session, configuredUrl());\n        }\n    }\n'''
if session_anchor not in src:
    raise SystemExit('Could not find createSession tail')
src = src.replace(session_anchor, session_new, 1)

settings_anchor = '''        Button settings = new Button(this);\n        settings.setText("Mở cài đặt Android");\n'''
settings_new = '''        CounterAudioEdge.AdminControls counterAudioControls =\n                counterAudioEdge != null ? counterAudioEdge.createAdminControls() : null;\n\n        Button settings = new Button(this);\n        settings.setText("Mở cài đặt Android");\n'''
if settings_anchor not in src:
    raise SystemExit('Could not find admin settings button')
src = src.replace(settings_anchor, settings_new, 1)

layout_anchor = '''        box.addView(bootInfo);\n        box.addView(settings);\n'''
layout_new = '''        box.addView(bootInfo);\n        if (counterAudioControls != null) counterAudioControls.addTo(box);\n        box.addView(settings);\n'''
if layout_anchor not in src:
    raise SystemExit('Could not find admin layout')
src = src.replace(layout_anchor, layout_new, 1)

save_anchor = '''                    syncBootFlags(auto.isChecked());\n\n                    try {\n'''
save_new = '''                    syncBootFlags(auto.isChecked());\n                    if (counterAudioControls != null) counterAudioControls.save();\n\n                    try {\n'''
if save_anchor not in src:
    raise SystemExit('Could not find 2.2.10 admin save tail')
src = src.replace(save_anchor, save_new, 1)

activity_result_anchor = '''    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {\n        super.onActivityResult(requestCode, resultCode, data);\n        if (requestCode == REQ_HOME_ROLE) {\n'''
activity_result_new = '''    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {\n        super.onActivityResult(requestCode, resultCode, data);\n        if (counterAudioEdge != null && counterAudioEdge.handleActivityResult(requestCode, resultCode, data)) {\n            return;\n        }\n        if (requestCode == REQ_HOME_ROLE) {\n'''
if activity_result_anchor not in src:
    raise SystemExit('Could not find onActivityResult')
src = src.replace(activity_result_anchor, activity_result_new, 1)

exit_anchor = '    private void exitApplication() {\n'
if exit_anchor not in src:
    raise SystemExit('Could not find exitApplication')
src = src.replace(exit_anchor, exit_anchor + '        if (counterAudioEdge != null) counterAudioEdge.shutdown();\n', 1)

destroy_anchor = '''    @Override protected void onDestroy() {\n        main.removeCallbacksAndMessages(null);\n'''
destroy_new = '''    @Override protected void onDestroy() {\n        main.removeCallbacksAndMessages(null);\n        if (counterAudioEdge != null) counterAudioEdge.shutdown();\n'''
if destroy_anchor not in src:
    raise SystemExit('Could not find onDestroy')
src = src.replace(destroy_anchor, destroy_new, 1)

java_path.write_text(src, encoding='utf-8')

gradle = gradle_path.read_text(encoding='utf-8')
if "versionCode 2021000" not in gradle or "versionName '2.2.10'" not in gradle:
    raise SystemExit('Expected Gradle metadata for 2.2.10 before applying 2.3.1')
gradle = gradle.replace("versionCode 2021000", "versionCode 2030100", 1)
gradle = gradle.replace("versionName '2.2.10'", "versionName '2.3.1'", 1)
gradle_path.write_text(gradle, encoding='utf-8')

print('Applied VHB QMS Android Display 2.3.1 WAV Counter Audio Engine')
