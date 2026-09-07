import contextlib
import copy
import importlib.util
import io
import json
from pathlib import Path
import shlex
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("configure_tv", Path(__file__).parents[1] / "configure_tv.py")
tv = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(tv)


class FakeDevice:
    """A stateful fake ADB endpoint. No command is sent to a real executable or device."""

    def __init__(self):
        self.devices = "List of devices attached\nTV_A device product:fake\n"
        self.installed = True
        self.identity = "fake-hardware-identity"
        self.user = "0"
        self.home = "com.google.android.apps.tv.launcherx"
        self.activity = self.home + "/.home.HomeActivity"
        self.values = {
            "global/window_animation_scale": "1.0",
            "global/transition_animation_scale": "1.0",
            "global/stay_on_while_plugged_in": "3",
            "secure/screensaver_enabled": "1",
            "secure/screensaver_activate_on_sleep": "1",
            "secure/screensaver_activate_on_dock": "0",
            "system/screen_off_timeout": "300000",
            "secure/sleep_timeout": "1800000",
            "secure/attentive_timeout": "14400000",
            tv.SERVICES_KEY: "example.reader/.ReaderService",
            tv.ACCESSIBILITY_KEY: "1",
        }
        self.mutations = []
        self.calls = []
        self.fail_key = None
        self.disconnect_after_failure = False
        self.disconnected = False
        self.backup_directory = None

    def runner(self, command, **kwargs):
        self.calls.append(command)
        if "shell" not in command:
            self.assert_command(command[-2:] == ["devices", "-l"])
            return subprocess.CompletedProcess(command, 0, self.devices, "")
        args = shlex.split(command[-1])
        if self.disconnected:
            raise subprocess.TimeoutExpired(command, 1)
        output = ""
        if args == ["am", "get-current-user"]:
            output = self.user
        elif args[:2] == ["pm", "path"]:
            output = "package:/data/app/fake/base.apk" if self.installed else ""
        elif args == ["getprop", "ro.serialno"]:
            output = self.identity
        elif args[:3] == ["cmd", "role", "get-role-holders"]:
            output = self.home
        elif args[:3] == ["cmd", "package", "resolve-activity"]:
            output = self.activity
        elif args[:2] == ["am", "start"]:
            self.record_mutation(args)
            output = "Status: ok"
        elif args[0] == "settings":
            self.assert_command(args[1:3] == ["--user", self.user])
            action, namespace = args[3:5]
            if action == "list":
                output = "\n".join(key.split("/", 1)[1] + "=" + value
                                   for key, value in self.values.items()
                                   if key.startswith(namespace + "/"))
            else:
                self.record_mutation(args)
                key = namespace + "/" + args[5]
                if action == "put":
                    self.values[key] = args[6]
                elif action == "delete":
                    self.values.pop(key, None)
                else:
                    raise AssertionError("Unexpected settings action")
                if key == self.fail_key:
                    self.fail_key = None
                    self.disconnected = self.disconnect_after_failure
                    raise subprocess.TimeoutExpired(command, 1)
        else:
            raise AssertionError("Unexpected fake command: " + repr(args))
        return subprocess.CompletedProcess(command, 0, output, "")

    def assert_command(self, condition):
        if not condition:
            raise AssertionError("Unexpected command")

    def record_mutation(self, args):
        if self.backup_directory is not None:
            files = list(self.backup_directory.glob("*.json"))
            if not files or any(json.loads(path.read_text())["schema"] != 1 for path in files):
                raise AssertionError("Mutation preceded a complete backup")
        self.mutations.append(args)

    def adb(self):
        return tv.Adb(serial="TV_A", runner=self.runner)


class ConfigureTvTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.fake = FakeDevice()
        self.fake.backup_directory = self.directory
        self.adb = self.fake.adb()

    def apply(self):
        before = tv.snapshot(self.adb)
        with contextlib.redirect_stdout(io.StringIO()):
            result = tv.transact(self.adb, before, tv.desired_settings(before), self.directory, "configure")
        return before, result

    def test_cli_defaults_to_preview_without_backup_or_mutation(self):
        with patch.object(tv, "Adb", return_value=self.adb), contextlib.redirect_stdout(io.StringIO()):
            result = tv.main(["--serial", "TV_A", "--backup-dir", str(self.directory)])
        self.assertEqual(result, 0)
        self.assertEqual(self.fake.mutations, [])
        self.assertEqual(list(self.directory.iterdir()), [])

    def test_ambiguous_or_unauthorized_device_needs_explicit_authorization(self):
        self.fake.devices += "TV_B device product:fake\n"
        with self.assertRaisesRegex(tv.ConfigurationError, "--serial"):
            tv.select_device(self.adb, None)
        self.assertEqual(tv.select_device(self.adb, "TV_A"), "TV_A")
        self.fake.devices = "List of devices attached\nTV_A unauthorized\n"
        with self.assertRaisesRegex(tv.ConfigurationError, "authorized"):
            tv.select_device(self.adb, "TV_A")
        self.assertEqual(self.fake.mutations, [])

    def test_absent_app_stops_before_any_changes(self):
        self.fake.installed = False
        with self.assertRaisesRegex(tv.ConfigurationError, "not installed"):
            tv.snapshot(self.adb)
        self.assertEqual(self.fake.mutations, [])
        self.assertEqual(list(self.directory.iterdir()), [])

    def test_apply_preserves_other_services_home_and_video_timeout_policy(self):
        original = copy.deepcopy(self.fake.values)
        before, result = self.apply()
        self.assertTrue(result["settings_verified"])
        self.assertEqual(self.fake.values[tv.SERVICES_KEY], original[tv.SERVICES_KEY] + ":" + tv.SERVICE)
        self.assertEqual(self.fake.values["secure/attentive_timeout"], "14400000")
        self.assertEqual(self.fake.values["system/screen_off_timeout"], "600000")
        self.assertEqual(self.fake.values["secure/sleep_timeout"], "600000")
        self.assertEqual(tv.read_home(self.adb, 0), before["home"])
        self.assertEqual(self.fake.mutations[0][:2], ["am", "start"])
        backup = tv.read_backup(result["backup"])
        self.assertIsNone(backup["before"]["settings"]["global/animator_duration_scale"])
        self.assertEqual(Path(result["backup"]).stat().st_mode & 0o777, 0o600)
        self.assertFalse(any(args[:2] == ["cmd", "role"] for args in self.fake.mutations))

    def test_existing_full_service_name_is_not_duplicated_and_second_apply_is_idle(self):
        full = tv.APP + "/" + tv.APP + ".HomeService"
        self.fake.values[tv.SERVICES_KEY] += ":" + full
        self.apply()
        mutations = len(self.fake.mutations)
        backups = len(list(self.directory.glob("*.json")))
        _, result = self.apply()
        self.assertFalse(result["changed"])
        self.assertEqual(len(self.fake.mutations), mutations)
        self.assertEqual(len(list(self.directory.glob("*.json"))), backups)
        self.assertEqual(self.fake.values[tv.SERVICES_KEY].count(full), 1)

    def test_restore_removes_originally_absent_values_and_restores_exact_settings(self):
        original = copy.deepcopy(self.fake.values)
        _, result = self.apply()
        backup = tv.read_backup(result["backup"])
        current = tv.snapshot(self.adb)
        target = tv.restore_target(current, backup)
        with contextlib.redirect_stdout(io.StringIO()):
            tv.transact(self.adb, current, target, self.directory, "restore")
        self.assertEqual(self.fake.values, original)
        self.assertNotIn("global/animator_duration_scale", self.fake.values)

    def test_timeout_after_remote_write_rolls_back_attempted_changes(self):
        original = copy.deepcopy(self.fake.values)
        self.fake.fail_key = "secure/screensaver_enabled"
        with self.assertRaisesRegex(tv.ConfigurationError, "restored and verified"):
            self.apply()
        self.assertEqual(self.fake.values, original)
        self.assertEqual(len(list(self.directory.glob("*.json"))), 1)

    def test_failed_rollback_reports_incomplete_recovery_and_keeps_backup(self):
        self.fake.fail_key = "secure/screensaver_enabled"
        self.fake.disconnect_after_failure = True
        with self.assertRaisesRegex(tv.ConfigurationError, "Rollback incomplete"):
            self.apply()
        self.assertEqual(len(list(self.directory.glob("*.json"))), 1)

    def test_restore_rejects_another_device_and_new_accessibility_services(self):
        _, result = self.apply()
        backup = tv.read_backup(result["backup"])
        current = tv.snapshot(self.adb)
        mutations = len(self.fake.mutations)
        current["device_identity_sha256"] = "0" * 64
        with self.assertRaisesRegex(tv.ConfigurationError, "different device"):
            tv.restore_target(current, backup)
        current = tv.snapshot(self.adb)
        current["settings"][tv.SERVICES_KEY] += ":new.reader/.Service"
        with self.assertRaisesRegex(tv.ConfigurationError, "preserve those services"):
            tv.restore_target(current, backup)
        self.assertEqual(len(self.fake.mutations), mutations)

    def test_tampered_backup_cannot_add_arbitrary_setting(self):
        _, result = self.apply()
        path = Path(result["backup"])
        data = json.loads(path.read_text())
        data["before"]["settings"]["secure/unrelated_setting"] = "1"
        path.write_text(json.dumps(data))
        with self.assertRaisesRegex(tv.ConfigurationError, "backup format"):
            tv.read_backup(path)

    def test_malformed_backup_stops_cli_before_adb_or_mutation(self):
        path = self.directory / "malformed.json"
        path.write_text('{"schema": 1, "app": "ee.kalle.minimaltv", "before": []}')
        with patch.object(tv, "Adb") as constructor, contextlib.redirect_stderr(io.StringIO()):
            result = tv.main(["--restore", str(path), "--apply"])
        self.assertEqual(result, 1)
        constructor.assert_not_called()
        self.assertEqual(self.fake.mutations, [])

    def test_backup_write_error_prevents_even_opening_the_app(self):
        before = tv.snapshot(self.adb)
        with patch.object(tv, "save_backup", side_effect=PermissionError("read only")):
            with self.assertRaisesRegex(tv.ConfigurationError, "nothing was changed"):
                tv.transact(self.adb, before, tv.desired_settings(before), self.directory, "configure")
        self.assertEqual(self.fake.mutations, [])

    def test_error_text_with_success_exit_code_is_failure(self):
        def runner(command, **kwargs):
            return subprocess.CompletedProcess(command, 0, "Error: permission denied", "")
        with self.assertRaisesRegex(tv.ConfigurationError, "permission denied"):
            tv.Adb(runner=runner).shell("settings", "get", "secure", "example")

    def test_remote_arguments_are_quoted_without_host_shell_execution(self):
        recorded = []
        def runner(command, **kwargs):
            recorded.append((command, kwargs))
            return subprocess.CompletedProcess(command, 0, "", "")
        value = "example/.Service'; echo UNEXPECTED; '"
        tv.Adb(runner=runner).shell("settings", "put", "secure", "example", value)
        self.assertEqual(shlex.split(recorded[0][0][-1])[-1], value)
        self.assertNotIn("shell", recorded[0][1])


if __name__ == "__main__":
    unittest.main()
