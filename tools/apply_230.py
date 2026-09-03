from pathlib import Path

java_path = Path('app/src/main/java/vn/com/vhb/qmsdisplay/MainActivity.java')
gradle_path = Path('app/build.gradle')

src = java_path.read_text(encoding='utf-8')

if 'private static final String VERSION = "2.2.10";' not in src:
    raise SystemExit('Expected flattened VHB QMS Android Display 2.2.10 source')

src = src.replace('private static final String VERSION = "2.2.10";',
                  'private static final String VERSION = "2.3.0";', 1)
src = src.replace('VHB QMS Display 2.2.10 • HYBRID FAST BOOT',
                  'VHB QMS Display 2.3.0 • COUNTER AUDIO EDGE', 1)

field_anchor = '    private ConnectivityManager.NetworkCallback networkCallback;\n'
if field_anchor not in src:
    raise SystemExit('Could not find networkCallback field')
src = src.replace(field_anchor,
                  field_anchor + '    private CounterAudioEdge counterAudioEdge;\n', 1)

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

exit_anchor = '    private void exitApplication() {\n'
if exit_anchor not in src:
    raise SystemExit('Could not find exitApplication')
src = src.replace(exit_anchor,
                  exit_anchor + '        if (counterAudioEdge != null) counterAudioEdge.shutdown();\n', 1)

destroy_anchor = '''    @Override protected void onDestroy() {\n        main.removeCallbacksAndMessages(null);\n'''
destroy_new = '''    @Override protected void onDestroy() {\n        main.removeCallbacksAndMessages(null);\n        if (counterAudioEdge != null) counterAudioEdge.shutdown();\n'''
if destroy_anchor not in src:
    raise SystemExit('Could not find onDestroy')
src = src.replace(destroy_anchor, destroy_new, 1)

java_path.write_text(src, encoding='utf-8')

gradle = gradle_path.read_text(encoding='utf-8')
if "versionCode 2021000" not in gradle or "versionName '2.2.10'" not in gradle:
    raise SystemExit('Expected Gradle version 2.2.10')
gradle = gradle.replace("versionCode 2021000", "versionCode 2030000", 1)
gradle = gradle.replace("versionName '2.2.10'", "versionName '2.3.0'", 1)
gradle_path.write_text(gradle, encoding='utf-8')

print('Applied VHB QMS Android Display 2.3.0 Counter Audio Edge')
