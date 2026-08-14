from pathlib import Path

java_path = Path('app/src/main/java/vn/com/vhb/qmsdisplay/MainActivity.java')
gradle_path = Path('app/build.gradle')

src = java_path.read_text(encoding='utf-8')

src = src.replace('private static final String VERSION = "2.2.5";', 'private static final String VERSION = "2.2.6";')

needle = '''        Button setHome = new Button(this);\n        setHome.setText("Đặt / đổi HOME mặc định");\n        setHome.setOnClickListener(v -> {\n'''
replacement = '''        Button autoHome = new Button(this);\n        autoHome.setText("CÀI HOME TỰ ĐỘNG (1 CHẠM)");\n        autoHome.setOnClickListener(v -> {\n            url.clearFocus();\n            pin.clearFocus();\n            hideKeyboard(v);\n            provisionHomeOneTouch(autoHome, homeState);\n        });\n\n        Button setHome = new Button(this);\n        setHome.setText("Cách chuẩn / chọn HOME thủ công");\n        setHome.setOnClickListener(v -> {\n'''
if needle not in src:
    raise SystemExit('Could not find setHome block')
src = src.replace(needle, replacement, 1)

needle2 = '''        box.addView(homeState);\n        box.addView(setHome);\n'''
replacement2 = '''        box.addView(homeState);\n        box.addView(autoHome);\n        box.addView(setHome);\n'''
if needle2 not in src:
    raise SystemExit('Could not find homeState layout block')
src = src.replace(needle2, replacement2, 1)

src = src.replace('VHB QMS Display 2.2.5 • HOME Launcher', 'VHB QMS Display 2.2.6 • ONE-TOUCH HOME')

marker = '    private void requestHomeRole() {'
if marker not in src:
    raise SystemExit('Could not find requestHomeRole marker')

helpers = r'''    private void provisionHomeOneTouch(Button button, TextView homeState) {
        if (isDefaultHome()) {
            homeState.setText("HOME hiện tại: VHB QMS đang là HOME mặc định");
            Toast.makeText(this, "VHB QMS đã là HOME mặc định", Toast.LENGTH_SHORT).show();
            return;
        }

        button.setEnabled(false);
        button.setText("ĐANG CÀI HOME...");

        new Thread(() -> {
            final int userId = 0;
            final String pkg = getPackageName();
            final String setHome = "cmd package set-home-activity --user " + userId + " " + pkg;
            final String setRole = "cmd role add-role-holder --user " + userId
                    + " android.app.role.HOME " + pkg + " 0";

            boolean directAttempted = false;
            boolean rootAvailable = false;
            boolean success = false;
            StringBuilder diagnostics = new StringBuilder();

            try {
                directAttempted = true;
                diagnostics.append("DIRECT: ").append(execShell(setHome, false)).append('\n');
                sleepQuiet(400L);
                success = isDefaultHome() || resolveHomeViaShell(false).contains(pkg);
            } catch (Throwable t) {
                diagnostics.append("DIRECT_ERR: ").append(t.getClass().getSimpleName()).append('\n');
            }

            if (!success) {
                try {
                    String rootProbe = execShell("id", true);
                    rootAvailable = rootProbe.contains("uid=0");
                    diagnostics.append("ROOT_PROBE: ").append(rootProbe).append('\n');
                } catch (Throwable t) {
                    diagnostics.append("ROOT_PROBE_ERR: ").append(t.getClass().getSimpleName()).append('\n');
                }
            }

            if (!success && rootAvailable) {
                try {
                    diagnostics.append("ROOT_HOME: ").append(execShell(setHome, true)).append('\n');
                    sleepQuiet(500L);
                    success = isDefaultHome() || resolveHomeViaShell(true).contains(pkg);
                } catch (Throwable t) {
                    diagnostics.append("ROOT_HOME_ERR: ").append(t.getClass().getSimpleName()).append('\n');
                }
            }

            if (!success && rootAvailable) {
                try {
                    diagnostics.append("ROOT_ROLE: ").append(execShell(setRole, true)).append('\n');
                    sleepQuiet(500L);
                    success = isDefaultHome() || resolveHomeViaShell(true).contains(pkg);
                } catch (Throwable t) {
                    diagnostics.append("ROOT_ROLE_ERR: ").append(t.getClass().getSimpleName()).append('\n');
                }
            }

            final boolean ok = success;
            final boolean hasRoot = rootAvailable;
            final boolean triedDirect = directAttempted;
            final String diag = diagnostics.toString();

            main.post(() -> {
                button.setEnabled(true);
                button.setText("CÀI HOME TỰ ĐỘNG (1 CHẠM)");

                if (ok) {
                    prefs.edit().putBoolean("dedicated_home", true).apply();
                    homeState.setText("HOME hiện tại: VHB QMS đang là HOME mặc định");
                    Toast.makeText(this,
                            "Đã đặt VHB QMS làm HOME mặc định",
                            Toast.LENGTH_LONG).show();
                } else {
                    String message;
                    if (hasRoot) {
                        message = "Thiết bị có quyền root nhưng firmware vẫn từ chối đổi HOME. "
                                + "Bạn có thể thử nút cách chuẩn bên dưới.";
                    } else if (triedDirect) {
                        message = "Firmware không cho ứng dụng thường tự thay HOME và thiết bị chưa cấp quyền root cho VHB. "
                                + "Nếu xuất hiện hộp xin quyền Superuser/Root, hãy chọn Cho phép rồi bấm lại một lần.";
                    } else {
                        message = "Không thể áp dụng HOME tự động trên firmware này.";
                    }

                    new AlertDialog.Builder(this)
                            .setTitle("Chưa đặt được HOME tự động")
                            .setMessage(message)
                            .setPositiveButton("THỬ CÁCH CHUẨN", (d, w) -> requestHomeRole())
                            .setNeutralButton("XEM CHẨN ĐOÁN", (d, w) ->
                                    new AlertDialog.Builder(this)
                                            .setTitle("Chẩn đoán HOME")
                                            .setMessage(diag.isEmpty() ? "Không có dữ liệu" : diag)
                                            .setPositiveButton("Đóng", null)
                                            .show())
                            .setNegativeButton("Đóng", null)
                            .show();
                }
            });
        }, "vhb-home-provision").start();
    }

    private String resolveHomeViaShell(boolean asRoot) {
        return execShell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME", asRoot);
    }

    private String execShell(String command, boolean asRoot) {
        Process process = null;
        try {
            ProcessBuilder builder;
            if (asRoot) {
                builder = new ProcessBuilder("su", "-c", command);
            } else {
                builder = new ProcessBuilder("sh", "-c", command);
            }
            builder.redirectErrorStream(true);
            process = builder.start();

            StringBuilder out = new StringBuilder();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (out.length() > 0) out.append(" | ");
                    out.append(line);
                    if (out.length() > 2000) break;
                }
            }

            int exit = process.waitFor();
            return "exit=" + exit + (out.length() == 0 ? "" : " | " + out);
        } catch (Throwable t) {
            return "ERR=" + t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage());
        } finally {
            if (process != null) {
                try { process.destroy(); } catch (Throwable ignored) { }
            }
        }
    }

    private void sleepQuiet(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

'''

src = src.replace(marker, helpers + marker, 1)
java_path.write_text(src, encoding='utf-8')

gradle = gradle_path.read_text(encoding='utf-8')
gradle = gradle.replace("versionCode 2020500", "versionCode 2020600")
gradle = gradle.replace("versionName '2.2.5'", "versionName '2.2.6'")
gradle_path.write_text(gradle, encoding='utf-8')

print('Applied VHB QMS Display 2.2.6 one-touch HOME provisioning')
