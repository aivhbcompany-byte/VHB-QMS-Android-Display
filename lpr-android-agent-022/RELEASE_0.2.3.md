# VHB LPR Android Edge Agent 0.2.3 TVBOX

Fix camera credential UX and RTSP authentication diagnostics.

- Camera username/password are now visible in the normal configuration screen.
- Odoo camera credentials are still preferred when supplied by config API.
- Local credentials are preserved when Odoo only supplies RTSP URL.
- RTSP 401 automatically prompts for camera username/password instead of only showing a generic error.
- Expanded Odoo config credential key compatibility.
- Android 5.0+ / target SDK 27.
