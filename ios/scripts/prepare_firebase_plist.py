#!/usr/bin/env python3
"""Prepare the Firebase iOS plist without committing production credentials."""
import argparse
import base64
import os
import plistlib
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("--allow-placeholder", action="store_true")
parser.add_argument("--output", default="ios/OilaNazoratiIOS/Resources/GoogleService-Info.plist")
args = parser.parse_args()

output = Path(args.output)
output.parent.mkdir(parents=True, exist_ok=True)
encoded = "".join(os.environ.get("GOOGLE_SERVICE_INFO_PLIST_BASE64", "").split())

if encoded:
    try:
        raw = base64.b64decode(encoded, validate=True)
        data = plistlib.loads(raw)
    except Exception as exc:
        raise SystemExit(f"GOOGLE_SERVICE_INFO_PLIST_BASE64 is not a valid base64 plist: {exc}")
    if data.get("BUNDLE_ID") != "uz.oilanazorati.parentcontrol":
        raise SystemExit("GoogleService-Info.plist BUNDLE_ID must be uz.oilanazorati.parentcontrol")
    required = ("API_KEY", "GOOGLE_APP_ID", "GCM_SENDER_ID", "PROJECT_ID")
    missing = [key for key in required if not data.get(key)]
    if missing:
        raise SystemExit("GoogleService-Info.plist is missing required keys: " + ", ".join(missing))
    output.write_bytes(raw)
    print(f"Restored production Firebase iOS configuration: {output}")
elif output.exists():
    try:
        data = plistlib.loads(output.read_bytes())
    except Exception as exc:
        raise SystemExit(f"Existing GoogleService-Info.plist is invalid: {exc}")
    if data.get("BUNDLE_ID") != "uz.oilanazorati.parentcontrol":
        raise SystemExit("Existing GoogleService-Info.plist has the wrong BUNDLE_ID")
    print(f"Using existing local Firebase iOS configuration: {output}")
elif args.allow_placeholder:
    placeholder = {
        "API_KEY": "BUILD_ONLY_PLACEHOLDER",
        "GCM_SENDER_ID": "000000000000",
        "GOOGLE_APP_ID": "1:000000000000:ios:0000000000000000000000",
        "PROJECT_ID": "oilanazorat-3c8de",
        "BUNDLE_ID": "uz.oilanazorati.parentcontrol",
        "IS_ANALYTICS_ENABLED": False,
        "IS_APPINVITE_ENABLED": False,
        "IS_GCM_ENABLED": True,
        "IS_SIGNIN_ENABLED": True,
        "IS_ADS_ENABLED": False,
    }
    output.write_bytes(plistlib.dumps(placeholder))
    print("WARNING: created a build-only placeholder Firebase plist; do not install or distribute this build.")
else:
    raise SystemExit(
        "Missing GoogleService-Info.plist. Set GOOGLE_SERVICE_INFO_PLIST_BASE64 "
        "or place the real Firebase plist at " + str(output)
    )
