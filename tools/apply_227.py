from pathlib import Path

java_path = Path('app/src/main/java/vn/com/vhb/qmsdisplay/MainActivity.java')
gradle_path = Path('app/build.gradle')

src = java_path.read_text(encoding='utf-8')

src = src.replace('private static final String VERSION = "2.2.6";', 'private static final String VERSION = "2.2.7";')
src = src.replace('VHB QMS Display 2.2.6 • ONE-TOUCH HOME', 'VHB QMS Display 2.2.7 • ONE-TOUCH HOME')

old = '''            final int userId = 0;\n            final String pkg = getPackageName();\n            final String setHome = "cmd package set-home-activity --user " + userId + " " + pkg;\n            final String setRole = "cmd role add-role-holder --user " + userId\n                    + " android.app.role.HOME " + pkg + " 0";\n'''
new = '''            final int userId = 0;\n            final String pkg = getPackageName();\n            final String component = pkg + "/vn.com.vhb.qmsdisplay.MainActivity";\n            // PANAPLAYBOX yêu cầu component đầy đủ package/activity, không chấp nhận chỉ package.\n            final String setHome = "cmd package set-home-activity --user " + userId + " " + component;\n            final String setRole = "cmd role add-role-holder --user " + userId\n                    + " android.app.role.HOME " + pkg + " 0";\n'''
if old not in src:
    raise SystemExit('Could not find setHome/setRole block from 2.2.6')
src = src.replace(old, new, 1)

old2 = '''            try {\n                directAttempted = true;\n                diagnostics.append("DIRECT: ").append(execShell(setHome, false)).append('\\n');\n                sleepQuiet(400L);\n                success = isDefaultHome() || resolveHomeViaShell(false).contains(pkg);\n            } catch (Throwable t) {\n                diagnostics.append("DIRECT_ERR: ").append(t.getClass().getSimpleName()).append('\\n');\n            }\n\n            if (!success) {\n'''
new2 = '''            try {\n                directAttempted = true;\n                diagnostics.append("COMPONENT: ").append(component).append('\\n');\n                diagnostics.append("HOME_BEFORE: ").append(resolveHomeViaShell(false)).append('\\n');\n                diagnostics.append("DIRECT_HOME: ").append(execShell(setHome, false)).append('\\n');\n                sleepQuiet(500L);\n                success = isDefaultHome() || resolveHomeViaShell(false).contains(pkg);\n            } catch (Throwable t) {\n                diagnostics.append("DIRECT_HOME_ERR: ").append(t.getClass().getSimpleName()).append('\\n');\n            }\n\n            // Một số firmware chặn set-home-activity nhưng vẫn mở Role service cho app OEM.\n            if (!success) {\n                try {\n                    diagnostics.append("DIRECT_ROLE: ").append(execShell(setRole, false)).append('\\n');\n                    sleepQuiet(500L);\n                    success = isDefaultHome() || resolveHomeViaShell(false).contains(pkg);\n                } catch (Throwable t) {\n                    diagnostics.append("DIRECT_ROLE_ERR: ").append(t.getClass().getSimpleName()).append('\\n');\n                }\n            }\n\n            if (!success) {\n'''
if old2 not in src:
    raise SystemExit('Could not find DIRECT block from 2.2.6')
src = src.replace(old2, new2, 1)

old3 = '''                    } else if (triedDirect) {\n                        message = "Firmware không cho ứng dụng thường tự thay HOME và thiết bị chưa cấp quyền root cho VHB. "\n                                + "Nếu xuất hiện hộp xin quyền Superuser/Root, hãy chọn Cho phép rồi bấm lại một lần.";\n                    } else {\n'''
new3 = '''                    } else if (triedDirect && (diag.contains("SecurityException")\n                            || diag.contains("Permission Denial")\n                            || diag.contains("Permission denied"))) {\n                        message = "PANAPLAYBOX đã nhận đúng component VHB nhưng firmware khóa quyền thay HOME đối với ứng dụng thường. "\n                                + "Thiết bị cũng không cho VHB dùng su/root.";\n                    } else if (triedDirect && diag.contains("component name not specified or invalid")) {\n                        message = "Firmware vẫn không chấp nhận component HOME theo cú pháp Android chuẩn. "\n                                + "Hãy mở Chẩn đoán để kiểm tra component mà box nhận được.";\n                    } else if (triedDirect) {\n                        message = "Đã thử HOME bằng component đầy đủ và Role service nhưng firmware chưa áp dụng. "\n                                + "Hãy mở Chẩn đoán để xem lỗi chính xác.";\n                    } else {\n'''
if old3 not in src:
    raise SystemExit('Could not find failure message block from 2.2.6')
src = src.replace(old3, new3, 1)

# Ghi thêm HOME cuối cùng vào chẩn đoán trước khi cập nhật UI.
needle = '''            final boolean ok = success;\n            final boolean hasRoot = rootAvailable;\n'''
replacement = '''            try {\n                diagnostics.append("HOME_AFTER: ").append(resolveHomeViaShell(false)).append('\\n');\n            } catch (Throwable ignored) { }\n\n            final boolean ok = success;\n            final boolean hasRoot = rootAvailable;\n'''
if needle not in src:
    raise SystemExit('Could not find final result block')
src = src.replace(needle, replacement, 1)

java_path.write_text(src, encoding='utf-8')

gradle = gradle_path.read_text(encoding='utf-8')
gradle = gradle.replace("versionCode 2020600", "versionCode 2020700")
gradle = gradle.replace("versionName '2.2.6'", "versionName '2.2.7'")
gradle_path.write_text(gradle, encoding='utf-8')

print('Applied VHB QMS Display 2.2.7 PANAPLAYBOX full-component HOME fix')
