#!/usr/bin/env python3
"""Task-specific defense-in-depth for the Claude handover; not a general shell sandbox.

OS sandboxing provides filesystem isolation. This hook additionally rejects common
signing, publication and unscoped-device commands without interrupting for approval.
"""
import json
import os
import re
import sys

ROOT = "/Users/sujithsampath/work/kaavalan-note-dev"


def reject(reason):
    print(json.dumps({"hookSpecificOutput": {
        "hookEventName": "PreToolUse", "permissionDecision": "deny",
        "permissionDecisionReason": reason + " Continue with safe in-scope work; do not bypass this guard."
    }}))
    sys.exit(0)


event = json.load(sys.stdin)
data = event.get("tool_input", {})
tool = event.get("tool_name", "")
if os.path.realpath(event.get("cwd", ROOT)) != ROOT:
    reject("Keep the primary working directory at the approved dev repository.")

if tool in {"Read", "Write", "Edit", "Glob", "Grep"}:
    target = data.get("file_path") or data.get("path") or ROOT
    resolved = os.path.realpath(os.path.join(ROOT, target))
    if resolved != ROOT and not resolved.startswith(ROOT + os.sep):
        reject("Reading or editing unrelated user files is outside this handover.")
    if re.search(r"(?:\.jks$|release[^/]*\.keystore$|(?:key|keystore|signing[^/]*)\.properties$|/\.env)", resolved, re.I):
        reject("Signing credentials and secrets are not authorized.")

if tool == "Bash":
    command = data.get("command", "")
    if data.get("dangerouslyDisableSandbox"):
        reject("Keep the filesystem sandbox enabled.")
    if "kaavalan-note-prod" in command or re.search(r"enableReleaseSigning\s*[= ]\s*true|apksigner\s+sign|jarsigner\s|(?:^|[\s/])security\s", command):
        reject("Production access and release signing need fresh user approval.")
    if re.search(r"\bgit\b[^\n;&|]*\b(?:push|send-pack)\b|\bgh\s+(?:release|pr|api)\b", command):
        reject("Push, PR and publication are not authorized.")
    if re.search(r"\b(?:adb|adb\.exe)\b", command) and not re.search(r"\badb(?:\.exe)?\s+-s\s+emulator-5596\b", command):
        reject("ADB must explicitly target the task-owned emulator: adb -s emulator-5596.")
    if "connected" in command and "gradle" in command and "ANDROID_SERIAL=emulator-5596" not in command:
        reject("Connected Gradle tests must explicitly set ANDROID_SERIAL=emulator-5596.")
    if "gradlew" in command and "-Pkaavalan.enableReleaseSigning=false" not in command:
        reject("Every Gradle invocation must explicitly disable release signing.")
    if re.search(r"\b(?:rm\s+-[^\n ]*r|git\s+reset\s+--hard|git\s+clean\s+-)", command):
        reject("Destructive cleanup is outside this handover.")

# No allow result: normal permission rules and sandbox checks still apply.
