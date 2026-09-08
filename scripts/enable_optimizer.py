#!/usr/bin/env python3
"""Preview or grant Kopp TV's two optional optimisation permissions through ADB."""

import argparse
import datetime
import hashlib
import json
import math
import os
from pathlib import Path
import re
import sys
import uuid

from configure_tv import APP, Adb, ConfigurationError, select_device

PERMISSIONS = (
    "android.permission.WRITE_SECURE_SETTINGS",
    "android.permission.DUMP",
)
BACKUP_KIND = "kopp-tv-optimizer-permissions"


def _children(lines, index):
    indent = len(lines[index]) - len(lines[index].lstrip())
    result = []
    for line in lines[index + 1:]:
        if line.strip() and len(line) - len(line.lstrip()) <= indent:
            break
        result.append(line)
    return result


def parse_permissions(output, user):
    """Read AOSP package dumps, including install-permission overrides for other users."""
    lines = output.splitlines()
    headers = [index for index, line in enumerate(lines)
               if re.fullmatch(r"\s*Package \[" + re.escape(APP) + r"\] \([^)]*\):", line)]
    if len(headers) != 1:
        raise ConfigurationError("Could not identify Kopp TV's package permission section safely.")
    body = _children(lines, headers[0])
    requested = [index for index, line in enumerate(body) if line.strip() == "requested permissions:"]
    if len(requested) != 1:
        raise ConfigurationError("Could not read Kopp TV's declared permissions.")
    declarations = {line.strip() for line in _children(body, requested[0]) if line.strip()}
    missing = set(PERMISSIONS) - declarations
    if missing:
        raise ConfigurationError("Installed Kopp TV does not declare: " + ", ".join(sorted(missing))
                                 + ". Install a version with Android optimisation first.")
    user_headers = [line for line in body if re.match(r"\s*User " + str(user) + r":", line)]
    if len(user_headers) != 1 or not re.search(r"\binstalled=true\b", user_headers[0]):
        raise ConfigurationError("Could not verify the installed package for the selected Android user.")
    install_sections = [index for index, line in enumerate(body)
                        if line.strip() == "install permissions:"]
    if len(install_sections) > 1:
        raise ConfigurationError("Ambiguous install-permission sections; nothing was changed.")
    states = {}
    if install_sections:
        for line in _children(body, install_sections[0]):
            permission = line.strip().split(":", 1)[0]
            if permission not in PERMISSIONS:
                continue
            match = re.fullmatch(re.escape(permission)
                                 + r": granted=(true|false)((?:, (?:flags=\[[^\]]*\]|userId=\d+))*)",
                                 line.strip())
            if not match:
                raise ConfigurationError("Unrecognized grant state for " + permission + ".")
            user_ids = re.findall(r", userId=(\d+)", match.group(2))
            if len(user_ids) > 1:
                raise ConfigurationError("Ambiguous permission user; nothing was changed.")
            state_user = int(user_ids[0]) if user_ids else 0
            key = (permission, state_user)
            if key in states:
                raise ConfigurationError("Duplicate permission state; nothing was changed.")
            states[key] = match.group(1) == "true"
    # AOSP prints a nonzero user's state only when it differs from user 0.
    # A declared permission with no stored state is ungranted.
    return {permission: states.get((permission, user), states.get((permission, 0), False))
            for permission in PERMISSIONS}


def read_permissions(adb, user):
    return parse_permissions(adb.shell("dumpsys", "package", APP), user)


def snapshot(adb):
    user = adb.shell("am", "get-current-user")
    if not user.isdigit():
        raise ConfigurationError("Could not determine the active Android user.")
    installed = adb.shell("pm", "path", "--user", user, APP)
    if not any(line.startswith("package:") for line in installed.splitlines()):
        raise ConfigurationError("Kopp TV is not installed for the active user. Nothing was changed.")
    identity = adb.shell("getprop", "ro.serialno").strip()
    if not identity or identity.lower() in {"unknown", "null"}:
        raise ConfigurationError("Could not identify this device for a safe backup and restore.")
    return {"user": int(user), "device_identity_sha256": hashlib.sha256(identity.encode()).hexdigest(),
            "permissions": read_permissions(adb, int(user))}


def difference(before, target):
    return [{"permission": permission, "before": before["permissions"][permission],
             "after": target[permission]} for permission in PERMISSIONS
            if before["permissions"][permission] != target[permission]]


def save_backup(directory, before):
    directory = Path(directory)
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.chmod(directory, 0o700)
    stamp = datetime.datetime.now(datetime.timezone.utc)
    name = "optimizer-" + stamp.strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8] + ".json"
    path = directory / name
    data = {"schema": 1, "kind": BACKUP_KIND, "app": APP, "created_at": stamp.isoformat(),
            "before": before, "granted_permissions": [permission for permission in PERMISSIONS
                                                       if not before["permissions"][permission]]}
    with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as stream:
        json.dump(data, stream, indent=2, sort_keys=True)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())
    return path


def read_backup(path):
    try:
        if Path(path).stat().st_size > 65536:
            raise ValueError("Oversized backup")
        data = json.loads(Path(path).read_text())
        if (type(data["schema"]) is not int or data["schema"] != 1
                or data["kind"] != BACKUP_KIND or data["app"] != APP):
            raise ValueError("Unknown backup schema or app")
        before = data["before"]
        if type(before["user"]) is not int or before["user"] < 0:
            raise ValueError("Invalid Android user")
        if not re.fullmatch(r"[0-9a-f]{64}", before["device_identity_sha256"]):
            raise ValueError("Invalid device identity")
        permissions = before["permissions"]
        if (not isinstance(permissions, dict) or set(permissions) != set(PERMISSIONS)
                or any(type(value) is not bool for value in permissions.values())):
            raise ValueError("Unsupported permission states")
        expected = [permission for permission in PERMISSIONS if not permissions[permission]]
        if data["granted_permissions"] != expected:
            raise ValueError("Invalid grant ownership")
        return data
    except (OSError, ValueError, KeyError, TypeError) as error:
        raise ConfigurationError("Backup is unreadable or does not match the optimizer permission format.") from error


def restore_target(before, backup):
    saved = backup["before"]
    if (before["user"] != saved["user"]
            or before["device_identity_sha256"] != saved["device_identity_sha256"]):
        raise ConfigurationError("Backup belongs to a different device or Android user. Nothing was changed.")
    for permission in PERMISSIONS:
        if saved["permissions"][permission] and not before["permissions"][permission]:
            raise ConfigurationError("A pre-existing permission changed independently. Restore stopped "
                                     "without altering it: " + permission)
    return dict(saved["permissions"])


def write_permission(adb, user, permission, granted):
    if permission not in PERMISSIONS or type(granted) is not bool:
        raise ConfigurationError("Refusing to change an unsupported permission.")
    adb.shell("pm", "grant" if granted else "revoke", "--user", user, APP, permission)


def transact(adb, before, target, backup_path):
    changes = difference(before, target)
    attempted = []
    try:
        for change in changes:
            # Include an uncertain write in rollback even if ADB times out after Android applied it.
            attempted.append(change["permission"])
            write_permission(adb, before["user"], change["permission"], change["after"])
        if read_permissions(adb, before["user"]) != target:
            raise ConfigurationError("Android did not retain all requested permission states.")
    except (ConfigurationError, KeyboardInterrupt) as error:
        failures = []
        for permission in reversed(attempted):
            try:
                write_permission(adb, before["user"], permission, before["permissions"][permission])
            except (ConfigurationError, KeyboardInterrupt) as rollback_error:
                failures.append(str(rollback_error))
        try:
            actual = read_permissions(adb, before["user"])
            if any(actual[permission] != before["permissions"][permission] for permission in attempted):
                failures.append("Restored permission states could not all be verified.")
        except ConfigurationError as rollback_error:
            failures.append(str(rollback_error))
        recovery = ("Rollback incomplete: " + "; ".join(failures) if failures
                    else "Attempted permission changes were restored and verified.")
        raise ConfigurationError(str(error) + " " + recovery + " Backup: " + str(backup_path)) from error
    return {"changed": bool(changes), "permissions_verified": True, "backup": str(backup_path)}


def apply_grants(adb, before, directory):
    target = {permission: True for permission in PERMISSIONS}
    if not difference(before, target):
        return {"changed": False, "message": "Permissions already granted; nothing was changed."}
    try:
        path = save_backup(directory, before)
    except OSError as error:
        raise ConfigurationError("Could not save the backup; nothing was changed: " + str(error)) from error
    print("Permission backup saved before changes: " + str(path))
    return transact(adb, before, target, path)


def restore_grants(adb, before, backup, path):
    target = restore_target(before, backup)
    if not difference(before, target):
        return {"changed": False, "message": "Original permission states already restored."}
    # The original, unchanged backup remains the recovery record throughout restore.
    return transact(adb, before, target, path)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", help="ADB device serial or current wireless debugging address")
    parser.add_argument("--adb", default="adb", help="ADB executable, default: adb")
    parser.add_argument("--timeout", type=float, default=20, help="Timeout per ADB command in seconds")
    parser.add_argument("--backup-dir", type=Path, default=Path(__file__).resolve().parents[1] / ".kopp-tv")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--apply", action="store_true", help="Apply permission changes")
    mode.add_argument("--dry-run", action="store_true", help="Preview only, which is the default")
    mode.add_argument("--status", action="store_true", help="Read current permissions without changing anything")
    parser.add_argument("--restore", type=Path, metavar="BACKUP", help="Restore this permission backup; add --apply to write")
    args = parser.parse_args(argv)
    if not math.isfinite(args.timeout) or args.timeout <= 0 or args.timeout > 300:
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
        target = restore_target(before, backup) if backup else {permission: True for permission in PERMISSIONS}
        print(json.dumps({"mode": "apply" if args.apply else "dry-run", "user": before["user"],
                          "changes": difference(before, target)}, indent=2, sort_keys=True))
        if not args.apply:
            print("Preview only. Add --apply to change these permissions.")
            return 0
        result = (restore_grants(adb, before, backup, args.restore) if backup
                  else apply_grants(adb, before, args.backup_dir))
        print(json.dumps(result, indent=2, sort_keys=True))
        return 0
    except ConfigurationError as error:
        print("Error: " + str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
