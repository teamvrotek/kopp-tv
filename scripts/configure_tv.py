#!/usr/bin/env python3
"""Preview or apply reversible Android TV settings through an authorized ADB connection."""

import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import uuid

APP = "ee.kalle.minimaltv"
ACTIVITY = APP + "/.MainActivity"
SERVICE = APP + "/" + APP + ".HomeService"
HOME_ROLE = "android.app.role.HOME"
DEFAULTS = {
    "global/window_animation_scale": "0",
    "global/transition_animation_scale": "0",
    "global/animator_duration_scale": "0",
    "global/stay_on_while_plugged_in": "0",
    "secure/screensaver_enabled": "0",
    "secure/screensaver_activate_on_sleep": "0",
    "secure/screensaver_activate_on_dock": "0",
    "system/screen_off_timeout": "600000",
    "secure/sleep_timeout": "600000",
}
SERVICES_KEY = "secure/enabled_accessibility_services"
ACCESSIBILITY_KEY = "secure/accessibility_enabled"
CHANGED_KEYS = frozenset(DEFAULTS) | {SERVICES_KEY, ACCESSIBILITY_KEY}
ERROR_OUTPUT = re.compile(
    r"(?im)^(?:adb: error|error\b|exception\b|securityexception\b|permission denial\b"
    r"|failure\b|failed\b|unknown command\b|invalid argument\b|can't\b|cannot\b)"
)


class ConfigurationError(RuntimeError):
    pass


class Adb:
    def __init__(self, executable="adb", serial=None, timeout=20, runner=subprocess.run):
        self.executable = executable
        self.serial = serial
        self.timeout = timeout
        self.runner = runner

    def command(self, *arguments):
        command = [self.executable]
        if self.serial is not None:
            command += ["-s", self.serial]
        command += list(arguments)
        try:
            result = self.runner(command, capture_output=True, text=True, timeout=self.timeout)
        except FileNotFoundError as error:
            raise ConfigurationError("ADB was not found. Install Android SDK Platform Tools.") from error
        except OSError as error:
            raise ConfigurationError("Could not run ADB: " + str(error)) from error
        except subprocess.TimeoutExpired as error:
            raise ConfigurationError("ADB timed out; a remote write may have completed.") from error
        output = result.stdout.strip()
        errors = result.stderr.strip()
        if result.returncode != 0 or ERROR_OUTPUT.search(output) or ERROR_OUTPUT.search(errors):
            detail = errors or output or "no error detail"
            raise ConfigurationError("ADB command failed: " + detail[:1500])
        return output

    def shell(self, *arguments):
        # ADB invokes a remote shell. Quote each argument even though the host uses no shell.
        return self.command("shell", shlex.join(str(argument) for argument in arguments))


def select_device(adb, requested):
    devices = {}
    for line in adb.command("devices", "-l").splitlines():
        fields = line.split()
        if len(fields) >= 2 and not line.startswith(("List of devices", "*")):
            devices[fields[0]] = fields[1]
    if requested:
        if devices.get(requested) != "device":
            raise ConfigurationError("Selected device is not connected and authorized: "
                                     + devices.get(requested, "not listed"))
        return requested
    if len(devices) != 1:
        raise ConfigurationError("Connect one authorized device or select it explicitly with --serial.")
    serial, state = next(iter(devices.items()))
    if state != "device":
        raise ConfigurationError("The connected device is " + state + "; authorize it on the TV first.")
    return serial


def read_settings(adb, user):
    values = {}
    attentive = None
    for namespace in ("global", "secure", "system"):
        for line in adb.shell("settings", "--user", user, "list", namespace).splitlines():
            if "=" not in line:
                continue
            key, value = line.split("=", 1)
            qualified = namespace + "/" + key
            if qualified in CHANGED_KEYS:
                values[qualified] = value
            elif qualified == "secure/attentive_timeout":
                attentive = value
    return {key: values.get(key) for key in sorted(CHANGED_KEYS)}, attentive


def read_home(adb, user):
    holders = adb.shell("cmd", "role", "get-role-holders", "--user", user, HOME_ROLE).splitlines()
    holders = sorted(holder.strip() for holder in holders if holder.strip())
    if any(not re.fullmatch(r"[A-Za-z0-9_.]+", holder) for holder in holders):
        raise ConfigurationError("Could not read the current Home role safely.")
    resolved = adb.shell("cmd", "package", "resolve-activity", "--brief", "--user", user,
                         "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME")
    components = [line.strip() for line in resolved.splitlines()
                  if re.fullmatch(r"[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+", line.strip())]
    if len(components) != 1:
        raise ConfigurationError("Could not determine the current Home activity safely.")
    return {"role_holders": holders, "resolved_activity": components[0]}


def snapshot(adb):
    user = adb.shell("am", "get-current-user")
    if not user.isdigit():
        raise ConfigurationError("Could not determine the active Android user.")
    installed = adb.shell("pm", "path", "--user", user, APP)
    if not any(line.startswith("package:") for line in installed.splitlines()):
        raise ConfigurationError("Kopp TV is not installed for the active user. Nothing was changed.")
    serial = adb.shell("getprop", "ro.serialno").strip()
    if not serial or serial.lower() in {"unknown", "null"}:
        raise ConfigurationError("Could not identify this device for a safe backup and restore.")
    settings, attentive = read_settings(adb, user)
    return {
        "user": int(user),
        "device_identity_sha256": hashlib.sha256(serial.encode()).hexdigest(),
        "settings": settings,
        "home": read_home(adb, user),
        "attentive_timeout_unchanged": attentive,
    }


def is_our_service(component):
    package, separator, service = component.partition("/")
    if service.startswith("."):
        service = package + service
    return separator and package == APP and service == APP + ".HomeService"


def desired_settings(before):
    target = dict(DEFAULTS)
    original = before["settings"][SERVICES_KEY] or ""
    components = [component for component in original.split(":") if component]
    if not any(is_our_service(component) for component in components):
        components.append(SERVICE)
    target[SERVICES_KEY] = ":".join(components)
    target[ACCESSIBILITY_KEY] = "1"
    return target


def difference(before, target):
    return [{"setting": key, "before": before["settings"][key], "after": target[key]}
            for key in sorted(target) if before["settings"][key] != target[key]]


def save_backup(directory, before, target, operation):
    directory = Path(directory)
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.chmod(directory, 0o700)
    stamp = datetime.datetime.now(datetime.timezone.utc)
    filename = stamp.strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8] + ".json"
    path = directory / filename
    data = {"schema": 1, "app": APP, "created_at": stamp.isoformat(), "operation": operation,
            "before": before, "after_settings": target}
    with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as stream:
        json.dump(data, stream, indent=2, sort_keys=True)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())
    return path


def read_backup(path):
    try:
        data = json.loads(Path(path).read_text())
        before = data["before"]
        if data["schema"] != 1 or data["app"] != APP:
            raise ValueError("Unknown backup schema or app")
        if type(before["user"]) is not int or before["user"] < 0:
            raise ValueError("Invalid Android user")
        if not re.fullmatch(r"[0-9a-f]{64}", before["device_identity_sha256"]):
            raise ValueError("Invalid device identity")
        for settings in (before["settings"], data["after_settings"]):
            if not isinstance(settings, dict) or set(settings) != CHANGED_KEYS:
                raise ValueError("Unsupported settings")
            if not all(value is None or (isinstance(value, str) and len(value) <= 8192
                                        and "\x00" not in value and "\n" not in value)
                       for value in settings.values()):
                raise ValueError("Invalid setting value")
        if (not isinstance(before["home"]["role_holders"], list)
                or not isinstance(before["home"]["resolved_activity"], str)):
            raise ValueError("Invalid Home role")
        return data
    except (OSError, ValueError, KeyError, TypeError) as error:
        raise ConfigurationError("Backup is unreadable or does not match the Kopp TV backup format.") from error


def restore_target(before, backup):
    saved = backup["before"]
    if (before["user"] != saved["user"]
            or before["device_identity_sha256"] != saved["device_identity_sha256"]):
        raise ConfigurationError("Backup belongs to a different device or Android user. Nothing was changed.")
    if before["home"] != saved["home"]:
        raise ConfigurationError("The Home role changed independently. This tool never changes Home roles; "
                                 "review that difference before restoring settings.")
    allowed_services = (saved["settings"][SERVICES_KEY], backup["after_settings"][SERVICES_KEY])
    if before["settings"][SERVICES_KEY] not in allowed_services:
        raise ConfigurationError("Accessibility services changed since the backup. Restore stopped "
                                 "to preserve those services; no settings were changed.")
    return dict(saved["settings"])


def write_setting(adb, user, key, value):
    if key not in CHANGED_KEYS:
        raise ConfigurationError("Refusing to change an unsupported setting.")
    namespace, name = key.split("/", 1)
    if value is None:
        adb.shell("settings", "--user", user, "delete", namespace, name)
    else:
        adb.shell("settings", "--user", user, "put", namespace, name, value)


def transact(adb, before, target, directory, operation):
    changes = difference(before, target)
    if not changes:
        return {"changed": False, "message": "Already configured; no settings were changed."}
    try:
        path = save_backup(directory, before, target, operation)
    except OSError as error:
        raise ConfigurationError("Could not save the backup; nothing was changed: " + str(error)) from error
    print("Backup saved before changes: " + str(path))
    attempted = []
    try:
        if operation == "configure":
            # Opening the app first checks the entry point before enabling its Home service.
            adb.shell("am", "start", "-W", "--user", before["user"], "-n", ACTIVITY)
        # Install the merged service list before turning accessibility on.
        ordered = sorted(changes, key=lambda change: change["setting"] == ACCESSIBILITY_KEY)
        for change in ordered:
            attempted.append(change["setting"])
            write_setting(adb, before["user"], change["setting"], change["after"])
        actual, _ = read_settings(adb, before["user"])
        if actual != target:
            raise ConfigurationError("Android did not retain all requested setting values.")
        if read_home(adb, before["user"]) != before["home"]:
            raise ConfigurationError("The Home role changed during configuration.")
    except (ConfigurationError, KeyboardInterrupt) as error:
        failures = []
        for key in reversed(attempted):
            try:
                write_setting(adb, before["user"], key, before["settings"][key])
            except (ConfigurationError, KeyboardInterrupt) as rollback_error:
                failures.append(str(rollback_error))
        try:
            actual, _ = read_settings(adb, before["user"])
            if any(actual[key] != before["settings"][key] for key in attempted):
                failures.append("Restored setting values could not all be verified.")
        except ConfigurationError as rollback_error:
            failures.append(str(rollback_error))
        if failures:
            recovery = "Rollback incomplete: " + "; ".join(failures)
        else:
            recovery = "Attempted settings were restored and verified."
        raise ConfigurationError(str(error) + " " + recovery + " Backup: " + str(path)) from error
    return {"changed": True, "backup": str(path), "settings_verified": True,
            "home_role_preserved": True,
            "message": "Settings applied. Check the physical Home button and idle behavior on your TV."}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", help="ADB device serial or current wireless debugging address")
    parser.add_argument("--adb", default="adb", help="ADB executable, default: adb")
    parser.add_argument("--timeout", type=float, default=20, help="Timeout per ADB command in seconds")
    parser.add_argument("--backup-dir", type=Path, default=Path(__file__).resolve().parents[1] / ".kopp-tv")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--apply", action="store_true", help="Apply changes after writing a local backup")
    mode.add_argument("--dry-run", action="store_true", help="Preview only, which is the default")
    mode.add_argument("--status", action="store_true", help="Read current settings without changing anything")
    parser.add_argument("--restore", type=Path, metavar="BACKUP", help="Restore a backup; also needs --apply to write")
    args = parser.parse_args(argv)
    if args.timeout <= 0 or args.timeout > 300:
        parser.error("--timeout must be greater than zero and at most 300 seconds")
    if args.status and args.restore:
        parser.error("--status and --restore cannot be combined")
    try:
        backup = read_backup(args.restore) if args.restore else None
        adb = Adb(args.adb, timeout=args.timeout)
        adb.serial = select_device(adb, args.serial)
        before = snapshot(adb)
        if args.status:
            print(json.dumps(before, indent=2, sort_keys=True))
            return 0
        target = restore_target(before, backup) if backup else desired_settings(before)
        print(json.dumps({"mode": "apply" if args.apply else "dry-run", "changes": difference(before, target),
                          "home_role_preserved": before["home"],
                          "attentive_timeout_unchanged": before["attentive_timeout_unchanged"]},
                         indent=2, sort_keys=True))
        if not args.apply:
            print("Preview only. Add --apply to save a backup and write these settings.")
            return 0
        result = transact(adb, before, target, args.backup_dir, "restore" if backup else "configure")
        print(json.dumps(result, indent=2, sort_keys=True))
        return 0
    except ConfigurationError as error:
        print("Error: " + str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
