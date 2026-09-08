import contextlib
import copy
import importlib.util
import io
import json
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
SPEC = importlib.util.spec_from_file_location("enable_optimizer", SCRIPTS / "enable_optimizer.py")
optimizer = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(optimizer)
WRITE, DUMP = optimizer.PERMISSIONS


class FakeDevice:
    """In-memory ADB endpoint. Tests never invoke an executable or connect to a TV."""

    def __init__(self, directory):
        self.directory = directory
        self.devices = "List of devices attached\nTV_A device product:fake\n"
        self.user = 0
        self.identity = "fake-device-identity"
        self.installed = True
        self.declared = set(optimizer.PERMISSIONS)
        self.grants = {0: {WRITE: False, DUMP: False}}
        self.calls = []
        self.mutations = []
        self.failure = None
        self.disconnect_after_failure = False
        self.disconnected = False
        self.output_override = None

    def package_dump(self):
        lines = ["Packages:", "  Package [" + optimizer.APP + "] (123abc):", "    userId=10123",
                 "    requested permissions:"]
        lines += ["      " + permission for permission in sorted(self.declared)]
        lines.append("    install permissions:")
        for permission in optimizer.PERMISSIONS:
            granted = str(self.grants[0][permission]).lower()
            lines.append("      " + permission + ": granted=" + granted + ", flags=[ GRANTED_BY_DEFAULT ]")
            for user, grants in sorted(self.grants.items()):
                if user and grants[permission] != self.grants[0][permission]:
                    lines.append("      " + permission + ": granted=" + str(grants[permission]).lower()
                                 + ", flags=[ USER_SET ], userId=" + str(user))
        for user in sorted(self.grants):
            lines.append("    User " + str(user) + ": ceDataInode=123 installed=true hidden=false")
            lines.append("      runtime permissions:")
        lines += ["", "Dexopt state:", "  [" + optimizer.APP + "]", "    path: /data/app/fake/base.apk"]
        return "\n".join(lines)

    def runner(self, command, **kwargs):
        self.calls.append(command)
        if self.disconnected:
            raise subprocess.TimeoutExpired(command, 1)
        if "shell" not in command:
            if command[-2:] != ["devices", "-l"]:
                raise AssertionError("Unexpected command: " + repr(command))
            return subprocess.CompletedProcess(command, 0, self.devices, "")
        args = shlex.split(command[-1])
        output = ""
        if args == ["am", "get-current-user"]:
            output = str(self.user)
        elif args == ["pm", "path", "--user", str(self.user), optimizer.APP]:
            output = "package:/data/app/fake/base.apk" if self.installed else ""
        elif args == ["getprop", "ro.serialno"]:
            output = self.identity
        elif args == ["dumpsys", "package", optimizer.APP]:
            output = self.output_override if self.output_override is not None else self.package_dump()
        elif args[:2] in (["pm", "grant"], ["pm", "revoke"]):
            if args[2:4] != ["--user", str(self.user)] or args[4] != optimizer.APP:
                raise AssertionError("Wrong grant target")
            backups = list(self.directory.glob("optimizer-*.json"))
            if not backups or any(json.loads(path.read_text())["kind"] != optimizer.BACKUP_KIND
                                  for path in backups):
                raise AssertionError("Mutation preceded a complete permission backup")
            permission = args[5]
            if permission not in optimizer.PERMISSIONS:
                raise AssertionError("Unexpected permission")
            self.mutations.append(args)
            self.grants[self.user][permission] = args[1] == "grant"
            if self.failure == (args[1], permission):
                self.failure = None
                self.disconnected = self.disconnect_after_failure
                raise subprocess.TimeoutExpired(command, 1)
        else:
            raise AssertionError("Unexpected fake command: " + repr(args))
        return subprocess.CompletedProcess(command, 0, output, "")

    def adb(self):
        return optimizer.Adb(serial="TV_A", runner=self.runner)


class EnableOptimizerTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.directory = Path(temp.name)
        self.fake = FakeDevice(self.directory)
        self.adb = self.fake.adb()

    def main(self, *arguments):
        with patch.object(optimizer, "Adb", return_value=self.adb), \
                contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            return optimizer.main(["--backup-dir", str(self.directory)] + list(arguments))

    def apply(self):
        before = optimizer.snapshot(self.adb)
        with contextlib.redirect_stdout(io.StringIO()):
            result = optimizer.apply_grants(self.adb, before, self.directory)
        return before, result

    def restore(self, path):
        backup = optimizer.read_backup(path)
        return optimizer.restore_grants(self.adb, optimizer.snapshot(self.adb), backup, path)

    def test_default_preview_reads_without_backup_mutation_or_launch(self):
        self.assertEqual(self.main("--serial", "TV_A"), 0)
        self.assertEqual(self.fake.mutations, [])
        self.assertEqual(list(self.directory.iterdir()), [])

    def test_status_only_reads_current_permissions(self):
        self.fake.grants[0][DUMP] = True
        self.assertEqual(self.main("--status"), 0)
        self.assertEqual(self.fake.mutations, [])
        self.assertEqual(list(self.directory.iterdir()), [])

    def test_multiple_or_unauthorized_devices_cannot_be_changed_implicitly(self):
        self.fake.devices += "TV_B device product:fake\n"
        self.assertEqual(self.main("--apply"), 1)
        self.assertEqual(self.fake.mutations, [])
        self.assertEqual(self.main("--serial", "TV_A"), 0)
        self.fake.devices = "List of devices attached\nTV_A unauthorized\n"
        self.assertEqual(self.main("--serial", "TV_A", "--apply"), 1)
        self.assertEqual(self.fake.mutations, [])

    def test_missing_app_or_missing_declared_permission_stops_before_writes(self):
        self.fake.installed = False
        self.assertEqual(self.main("--apply"), 1)
        self.fake.installed = True
        self.fake.declared.remove(DUMP)
        self.assertEqual(self.main("--apply"), 1)
        self.assertEqual(self.fake.mutations, [])
        self.assertEqual(list(self.directory.iterdir()), [])

    def test_parser_handles_user_overrides_and_shared_default_states(self):
        self.fake.user = 10
        self.fake.grants = {0: {WRITE: True, DUMP: False}, 10: {WRITE: False, DUMP: False}}
        self.assertEqual(optimizer.snapshot(self.adb)["permissions"], {WRITE: False, DUMP: False})
        self.fake.grants[10] = {WRITE: True, DUMP: True}
        self.assertEqual(optimizer.snapshot(self.adb)["permissions"], {WRITE: True, DUMP: True})

    def test_declared_permissions_without_stored_states_are_ungranted(self):
        output = self.fake.package_dump()
        output = "\n".join(line for line in output.splitlines() if ": granted=" not in line)
        self.assertEqual(optimizer.parse_permissions(output, 0), {WRITE: False, DUMP: False})

    def test_parser_refuses_malformed_duplicate_or_wrong_user_sections(self):
        original = self.fake.package_dump()
        invalid = [original.replace("granted=false", "granted=maybe", 1),
                   original + "\n  Package [" + optimizer.APP + "] (other):",
                   original.replace("User 0:", "User 10:"),
                   original.replace("    install permissions:", "    requested permissions:")]
        for output in invalid:
            with self.subTest(output=output):
                self.fake.output_override = output
                self.assertEqual(self.main("--apply"), 1)
                self.assertEqual(self.fake.mutations, [])

    def test_enable_preserves_existing_grant_and_saves_private_backup_first(self):
        self.fake.grants[0][DUMP] = True
        before, result = self.apply()
        self.assertEqual(self.fake.grants[0], {WRITE: True, DUMP: True})
        self.assertEqual([(args[1], args[-1]) for args in self.fake.mutations], [("grant", WRITE)])
        path = Path(result["backup"])
        data = optimizer.read_backup(path)
        self.assertEqual(data["before"], before)
        self.assertEqual(data["granted_permissions"], [WRITE])
        self.assertEqual(path.stat().st_mode & 0o777, 0o600)
        self.assertNotIn(self.fake.identity, path.read_text())
        self.assertNotIn("TV_A", path.read_text())

    def test_second_enable_is_idempotent_without_new_backup(self):
        self.apply()
        mutations = list(self.fake.mutations)
        files = list(self.directory.iterdir())
        _, result = self.apply()
        self.assertFalse(result["changed"])
        self.assertEqual(self.fake.mutations, mutations)
        self.assertEqual(list(self.directory.iterdir()), files)

    def test_restore_revokes_only_new_grants_and_is_idempotent(self):
        self.fake.grants[0][DUMP] = True
        _, result = self.apply()
        raw_backup = Path(result["backup"]).read_bytes()
        self.restore(result["backup"])
        self.assertEqual(self.fake.grants[0], {WRITE: False, DUMP: True})
        self.assertEqual([(args[1], args[-1]) for args in self.fake.mutations],
                         [("grant", WRITE), ("revoke", WRITE)])
        self.assertFalse(self.restore(result["backup"])["changed"])
        self.assertEqual(Path(result["backup"]).read_bytes(), raw_backup)

    def test_restore_preview_does_not_revoke_permissions(self):
        _, result = self.apply()
        mutations = list(self.fake.mutations)
        self.assertEqual(self.main("--restore", result["backup"]), 0)
        self.assertEqual(self.fake.mutations, mutations)
        self.assertEqual(self.fake.grants[0], {WRITE: True, DUMP: True})

    def test_restore_rejects_wrong_device_or_user_without_writes(self):
        _, result = self.apply()
        mutations = list(self.fake.mutations)
        self.fake.identity = "different-device"
        self.assertEqual(self.main("--restore", result["backup"], "--apply"), 1)
        self.fake.identity = "fake-device-identity"
        self.fake.user = 10
        self.fake.grants[10] = dict(self.fake.grants[0])
        self.assertEqual(self.main("--restore", result["backup"], "--apply"), 1)
        self.assertEqual(self.fake.mutations, mutations)

    def test_restore_refuses_independent_changes_to_preexisting_grants(self):
        self.fake.grants[0][DUMP] = True
        _, result = self.apply()
        self.fake.grants[0][DUMP] = False
        mutations = list(self.fake.mutations)
        with self.assertRaisesRegex(optimizer.ConfigurationError, "pre-existing permission"):
            self.restore(result["backup"])
        self.assertEqual(self.fake.mutations, mutations)

    def test_uncertain_grant_timeout_rolls_back_all_attempted_writes(self):
        original = copy.deepcopy(self.fake.grants)
        self.fake.failure = ("grant", DUMP)
        with self.assertRaisesRegex(optimizer.ConfigurationError, "restored and verified"):
            self.apply()
        self.assertEqual(self.fake.grants, original)
        self.assertEqual(len(list(self.directory.glob("optimizer-*.json"))), 1)

    def test_rollback_never_revokes_a_preexisting_grant(self):
        self.fake.grants[0][DUMP] = True
        self.fake.failure = ("grant", WRITE)
        with self.assertRaisesRegex(optimizer.ConfigurationError, "restored and verified"):
            self.apply()
        self.assertTrue(self.fake.grants[0][DUMP])
        self.assertTrue(all(args[-1] == WRITE for args in self.fake.mutations))

    def test_failed_rollback_reports_incomplete_and_retains_backup(self):
        self.fake.failure = ("grant", DUMP)
        self.fake.disconnect_after_failure = True
        with self.assertRaisesRegex(optimizer.ConfigurationError, "Rollback incomplete"):
            self.apply()
        self.assertEqual(len(list(self.directory.glob("optimizer-*.json"))), 1)

    def test_failed_restore_rolls_back_partial_revokes_and_can_be_retried(self):
        _, result = self.apply()
        self.fake.failure = ("revoke", DUMP)
        with self.assertRaisesRegex(optimizer.ConfigurationError, "restored and verified"):
            self.restore(result["backup"])
        self.assertEqual(self.fake.grants[0], {WRITE: True, DUMP: True})
        self.restore(result["backup"])
        self.assertEqual(self.fake.grants[0], {WRITE: False, DUMP: False})

    def test_backup_write_failure_prevents_any_mutation(self):
        before = optimizer.snapshot(self.adb)
        with patch.object(optimizer, "save_backup", side_effect=PermissionError("read only")):
            with self.assertRaisesRegex(optimizer.ConfigurationError, "nothing was changed"):
                optimizer.apply_grants(self.adb, before, self.directory)
        self.assertEqual(self.fake.mutations, [])

    def test_invalid_backup_schema_stops_before_adb_even_with_apply(self):
        invalid = [{"schema": 1, "kind": optimizer.BACKUP_KIND, "app": optimizer.APP, "before": []},
                   {"schema": True, "kind": optimizer.BACKUP_KIND, "app": optimizer.APP},
                   {"schema": 1, "kind": "settings", "app": optimizer.APP}]
        path = self.directory / "invalid.json"
        for data in invalid:
            with self.subTest(data=data):
                path.write_text(json.dumps(data))
                with patch.object(optimizer, "Adb") as constructor, contextlib.redirect_stderr(io.StringIO()):
                    self.assertEqual(optimizer.main(["--restore", str(path), "--apply"]), 1)
                constructor.assert_not_called()

    def test_backup_cannot_expand_permission_scope_or_relabel_preexisting_grants(self):
        self.fake.grants[0][DUMP] = True
        _, result = self.apply()
        path = Path(result["backup"])
        original = json.loads(path.read_text())
        variants = []
        data = copy.deepcopy(original)
        data["before"]["permissions"]["android.permission.PACKAGE_USAGE_STATS"] = False
        variants.append(data)
        data = copy.deepcopy(original)
        data["granted_permissions"].append(DUMP)
        variants.append(data)
        data = copy.deepcopy(original)
        data["before"]["permissions"][WRITE] = 0
        variants.append(data)
        for data in variants:
            with self.subTest(data=data):
                path.write_text(json.dumps(data))
                with self.assertRaisesRegex(optimizer.ConfigurationError, "permission format"):
                    optimizer.read_backup(path)


if __name__ == "__main__":
    unittest.main()
