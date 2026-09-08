package ee.kalle.minimaltv;

import android.Manifest;
import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.PowerManager;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.util.AtomicFile;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** On-demand local diagnostics and a fixed set of reversible Android settings. */
public final class AndroidOptimizer {
    private static final Object LOCK = new Object();
    private static final String TAG = "KOPP_OPTIMIZER";
    private final Context context;
    private final OptimizationTransaction.Backend backend;
    private final OptimizationTransaction.Journal journal;

    public static final class Row {
        public final String title, detail, packageName, settingsAction;
        public final boolean warning;
        Row(String title, String detail, boolean warning, String packageName) {
            this(title, detail, warning, packageName, null);
        }
        Row(String title, String detail, boolean warning, String packageName, String settingsAction) {
            this.title = title; this.detail = detail; this.warning = warning; this.packageName = packageName;
            this.settingsAction = settingsAction;
        }
    }

    public static final class Report {
        public final String device;
        public final boolean accessGranted, canRestore, recoveryPending;
        public final int changeCount;
        public final List<Row> rows;
        private final Map<OptimizationPolicy.Key, String> observed;
        Report(String device, boolean accessGranted, boolean canRestore, boolean recoveryPending,
                int changeCount, List<Row> rows, Map<OptimizationPolicy.Key, String> observed) {
            this.device = device; this.accessGranted = accessGranted; this.canRestore = canRestore;
            this.recoveryPending = recoveryPending; this.changeCount = changeCount;
            this.rows = Collections.unmodifiableList(new ArrayList<>(rows));
            this.observed = Collections.unmodifiableMap(new LinkedHashMap<>(observed));
        }
    }

    public static final class Outcome {
        public final boolean success;
        public final String message;
        Outcome(boolean success, String message) { this.success = success; this.message = message; }
    }

    public AndroidOptimizer(Context context) {
        this.context = context;
        backend = new SettingsBackend(context.getApplicationContext());
        journal = new LocalJournal(context.getApplicationContext());
    }

    AndroidOptimizer(Context context, OptimizationTransaction.Backend backend, OptimizationTransaction.Journal journal) {
        this.context = context; this.backend = backend; this.journal = journal;
    }

    private boolean hasAccess() {
        return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    public Report scan() {
        synchronized (LOCK) {
            Map<OptimizationPolicy.Key, String> observed = readSettings();
            Map<OptimizationPolicy.Key, String> changes = OptimizationPolicy.plan(observed);
            ArrayList<Row> rows = new ArrayList<>();
            OptimizationTransaction.Record saved = null;
            boolean brokenJournal = false;
            try { saved = journal.load(); }
            catch (Exception error) {
                brokenJournal = true;
                Log.e(TAG, "Unable to read optimisation journal", error);
                add(rows, R.string.opt_backup_title, R.string.opt_backup_unreadable, true);
            }
            if (saved != null && saved.pending) add(rows, R.string.opt_backup_title, R.string.opt_recovery_pending, true);
            else if (saved != null) add(rows, R.string.opt_backup_title, R.string.opt_backup_ready, false);
            appendSettings(rows, observed, changes, true);
            appendDiagnostics(rows);
            appendSettings(rows, observed, changes, false);
            rows.add(new Row(context.getString(R.string.opt_home_title), context.getString(homeEnabled()
                    ? R.string.opt_home_enabled : R.string.opt_home_manual), !homeEnabled(), null,
                    homeEnabled() ? null : Settings.ACTION_ACCESSIBILITY_SETTINGS));
            rows.add(new Row(context.getString(R.string.opt_privacy_manual_title),
                    context.getString(R.string.opt_privacy_manual_detail), false, null, Settings.ACTION_SETTINGS));
            if (Build.VERSION.SDK_INT >= 31) rows.add(new Row(context.getString(R.string.opt_microphone_title),
                    context.getString(R.string.opt_microphone_detail), false, null, "android.settings.MANAGE_MICROPHONE_PRIVACY"));
            appendOptionalApps(rows);
            return new Report(Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE,
                    hasAccess(), saved != null, brokenJournal || (saved != null && saved.pending),
                    changes.size(), rows, observed);
        }
    }

    public Outcome optimize(Report report) {
        synchronized (LOCK) {
            if (!hasAccess()) return new Outcome(false, context.getString(R.string.opt_access_required));
            if (report == null) return new Outcome(false, context.getString(R.string.opt_rescan_required));
            Map<OptimizationPolicy.Key, String> targets = OptimizationPolicy.plan(report.observed);
            try {
                for (OptimizationPolicy.Key key : targets.keySet()) {
                    if (!Objects.equals(report.observed.get(key), backend.read(key))) {
                        return new Outcome(false, context.getString(R.string.opt_rescan_required));
                    }
                }
                OptimizationTransaction.Result result = OptimizationTransaction.apply(backend, journal, targets);
                return outcome(result, false, targets.size());
            } catch (Exception error) {
                Log.e(TAG, "Optimisation failed", error);
                return new Outcome(false, context.getString(R.string.opt_operation_failed));
            }
        }
    }

    public Outcome restore() {
        synchronized (LOCK) {
            if (!hasAccess()) return new Outcome(false, context.getString(R.string.opt_access_required));
            try { return outcome(OptimizationTransaction.restore(backend, journal), true, 0); }
            catch (Exception error) {
                Log.e(TAG, "Restore failed", error);
                return new Outcome(false, context.getString(R.string.opt_operation_failed));
            }
        }
    }

    private Outcome outcome(OptimizationTransaction.Result result, boolean restore, int count) {
        if (result.error != null) {
            Log.e(TAG, result.error);
            int message = R.string.opt_operation_failed;
            if (result.failure == OptimizationTransaction.Failure.CONFLICT) message = R.string.opt_restore_conflict;
            else if (result.failure == OptimizationTransaction.Failure.WRITE_ROLLED_BACK) message = R.string.opt_write_rolled_back;
            else if (result.failure == OptimizationTransaction.Failure.RECOVERY_REQUIRED) message = R.string.opt_recovery_pending;
            return new Outcome(false, context.getString(message));
        }
        return new Outcome(true, context.getString(restore ? R.string.opt_restored
                : result.changed ? R.string.opt_applied : R.string.opt_already_configured, count));
    }

    private Map<OptimizationPolicy.Key, String> readSettings() {
        LinkedHashMap<OptimizationPolicy.Key, String> values = new LinkedHashMap<>();
        for (OptimizationPolicy.Key key : OptimizationPolicy.Key.values()) {
            try { values.put(key, backend.read(key)); }
            catch (Exception error) { values.put(key, null); Log.w(TAG, "Setting unavailable: " + key.qualified(), error); }
        }
        return values;
    }

    private void appendSettings(List<Row> rows, Map<OptimizationPolicy.Key, String> observed,
            Map<OptimizationPolicy.Key, String> changes, boolean proposedChanges) {
        for (OptimizationPolicy.Key key : OptimizationPolicy.Key.values()) {
            if (changes.containsKey(key) != proposedChanges) continue;
            String raw = observed.get(key);
            String detail;
            if (!OptimizationPolicy.isSupportedValue(key, raw)) detail = context.getString(R.string.opt_setting_unavailable);
            else if (proposedChanges) detail = context.getString(R.string.opt_setting_change,
                    displayValue(key, raw), displayValue(key, key.target));
            else detail = context.getString(R.string.opt_setting_ready, displayValue(key, raw));
            rows.add(new Row(context.getString(titleFor(key)), detail, proposedChanges, null));
        }
    }

    private void appendDiagnostics(List<Row> rows) {
        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
            manager.getMemoryInfo(memory);
            String detail = context.getString(R.string.opt_capacity, size(memory.availMem), size(memory.totalMem))
                    + "\n" + context.getString(memory.lowMemory ? R.string.opt_memory_low : R.string.opt_memory_normal);
            rows.add(new Row(context.getString(R.string.opt_memory_title), detail, memory.lowMemory, null));
        } catch (RuntimeException error) { add(rows, R.string.opt_memory_title, R.string.opt_diagnostic_unavailable, false); }
        try {
            StatFs storage = new StatFs(context.getFilesDir().getAbsolutePath());
            boolean low = storage.getAvailableBytes() < 500L * 1024 * 1024;
            String detail = context.getString(R.string.opt_capacity, size(storage.getAvailableBytes()), size(storage.getTotalBytes()));
            if (low) detail += "\n" + context.getString(R.string.opt_storage_low);
            rows.add(new Row(context.getString(R.string.opt_storage_title), detail, low, null));
        } catch (RuntimeException error) { add(rows, R.string.opt_storage_title, R.string.opt_diagnostic_unavailable, false); }
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (Build.VERSION.SDK_INT >= 29 && power != null) {
            try {
                int status = power.getCurrentThermalStatus();
                int[] labels = {R.string.opt_thermal_none, R.string.opt_thermal_light, R.string.opt_thermal_moderate,
                        R.string.opt_thermal_severe, R.string.opt_thermal_critical, R.string.opt_thermal_emergency,
                        R.string.opt_thermal_shutdown};
                int label = status >= 0 && status < labels.length ? labels[status] : R.string.opt_diagnostic_unavailable;
                add(rows, R.string.opt_thermal_title, label, status >= PowerManager.THERMAL_STATUS_MODERATE);
            } catch (RuntimeException error) { add(rows, R.string.opt_thermal_title, R.string.opt_diagnostic_unavailable, false); }
        }
        rows.add(new Row(context.getString(R.string.opt_uptime_title),
                DateUtils.formatElapsedTime(SystemClock.elapsedRealtime() / 1000), false, null));
        if (context.checkSelfPermission(Manifest.permission.DUMP) != PackageManager.PERMISSION_GRANTED) {
            add(rows, R.string.opt_power_title, R.string.opt_power_access, false);
            return;
        }
        OptimizationDiagnostics.PowerState state = OptimizationDiagnostics.parsePower(readPowerDump());
        if (state == null) add(rows, R.string.opt_power_title, R.string.opt_diagnostic_unavailable, false);
        else rows.add(new Row(context.getString(R.string.opt_power_title), context.getString(R.string.opt_power_detail,
                duration(state.screenTimeout), duration(state.sleepTimeout),
                context.getString(state.screensaverEnabled ? R.string.opt_on : R.string.opt_off)), false, null));
    }

    private String size(long bytes) { return Formatter.formatShortFileSize(context, bytes); }
    private String duration(long millis) {
        if (millis < 0) return context.getString(R.string.opt_never);
        if (millis % 60000 == 0) return context.getString(R.string.opt_minutes, millis / 60000);
        return context.getString(R.string.opt_seconds, millis / 1000);
    }
    private String displayValue(OptimizationPolicy.Key key, String value) {
        if (key == OptimizationPolicy.Key.ASSISTANT || key == OptimizationPolicy.Key.VOICE_INTERACTION_SERVICE)
            return context.getString(value.isEmpty() ? R.string.opt_off : R.string.opt_on);
        if (key == OptimizationPolicy.Key.SCREEN_OFF_TIMEOUT || key == OptimizationPolicy.Key.SLEEP_TIMEOUT)
            return duration(Long.parseLong(value));
        if (Double.parseDouble(value) == 0) return context.getString(R.string.opt_off);
        if (key == OptimizationPolicy.Key.WINDOW_ANIMATION_SCALE || key == OptimizationPolicy.Key.TRANSITION_ANIMATION_SCALE
                || key == OptimizationPolicy.Key.ANIMATOR_DURATION_SCALE) return value + "×";
        return context.getString(R.string.opt_on);
    }
    private void add(List<Row> rows, int title, int detail, boolean warning) {
        rows.add(new Row(context.getString(title), context.getString(detail), warning, null));
    }

    private boolean homeEnabled() {
        try {
            String services = Settings.Secure.getString(context.getContentResolver(), "enabled_accessibility_services");
            if (services == null || Settings.Secure.getInt(context.getContentResolver(), "accessibility_enabled", 0) != 1) return false;
            for (String service : services.split(":")) {
                if ((context.getPackageName() + "/.HomeService").equals(service)
                        || (context.getPackageName() + "/" + context.getPackageName() + ".HomeService").equals(service)) return true;
            }
        } catch (RuntimeException ignored) { }
        return false;
    }

    private void appendOptionalApps(List<Row> rows) {
        String[] packages = {"com.google.android.tvrecommendations", "com.google.android.apps.education.cast2class", "com.google.android.katniss"};
        int[] labels = {R.string.opt_recommendations_title, R.string.opt_education_title, R.string.opt_voice_title};
        int[] details = {R.string.opt_recommendations_detail, R.string.opt_education_detail, R.string.opt_voice_detail};
        for (int index = 0; index < packages.length; index++) {
            try {
                ApplicationInfo info = context.getPackageManager().getApplicationInfo(packages[index], PackageManager.MATCH_DISABLED_COMPONENTS);
                String detail = context.getString(info.enabled ? R.string.opt_optional_enabled : R.string.opt_optional_disabled)
                        + "\n" + context.getString(details[index]);
                rows.add(new Row(context.getString(labels[index]), detail, false, packages[index]));
            } catch (PackageManager.NameNotFoundException ignored) { }
        }
    }

    private static int titleFor(OptimizationPolicy.Key key) {
        switch (key) {
            case WINDOW_ANIMATION_SCALE: return R.string.opt_window_animation;
            case TRANSITION_ANIMATION_SCALE: return R.string.opt_transition_animation;
            case ANIMATOR_DURATION_SCALE: return R.string.opt_animator_duration;
            case STAY_ON_WHILE_PLUGGED_IN: return R.string.opt_stay_awake;
            case SCREENSAVER_ENABLED: return R.string.opt_screensaver;
            case SCREENSAVER_ACTIVATE_ON_SLEEP: return R.string.opt_screensaver_sleep;
            case SCREENSAVER_ACTIVATE_ON_DOCK: return R.string.opt_screensaver_dock;
            case SCREEN_OFF_TIMEOUT: return R.string.opt_screen_timeout;
            case SLEEP_TIMEOUT: return R.string.opt_sleep_timeout;
            case LOCATION_MODE: return R.string.opt_location;
            case WIFI_SCAN_ALWAYS_ENABLED: return R.string.opt_wifi_scanning;
            case BLE_SCAN_ALWAYS_ENABLED: return R.string.opt_bluetooth_scanning;
            case ASSISTANT: return R.string.opt_default_assistant;
            case VOICE_INTERACTION_SERVICE: return R.string.opt_voice_interaction;
            default: throw new IllegalArgumentException("Unknown setting");
        }
    }

    private static String readPowerDump() {
        Process process = null;
        ExecutorService reader = Executors.newSingleThreadExecutor();
        try {
            process = new ProcessBuilder("/system/bin/dumpsys", "-t", "2", "power").redirectErrorStream(true).start();
            final Process running = process;
            Future<String> output = reader.submit(() -> {
                try (InputStream stream = running.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[4096]; int count;
                    while ((count = stream.read(buffer)) != -1) {
                        if (bytes.size() + count > 131072) throw new IOException("Power diagnostic output limit");
                        bytes.write(buffer, 0, count);
                    }
                    return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
                }
            });
            if (!process.waitFor(4, TimeUnit.SECONDS) || process.exitValue() != 0) return null;
            return output.get(1, TimeUnit.SECONDS);
        } catch (Exception error) {
            Log.w(TAG, "Power diagnostics unavailable", error);
            return null;
        } finally {
            if (process != null) {
                process.destroyForcibly();
                try { process.getInputStream().close(); } catch (IOException ignored) { }
            }
            reader.shutdownNow();
        }
    }

    static final class SettingsBackend implements OptimizationTransaction.Backend {
        private final Context context;
        SettingsBackend(Context context) { this.context = context; }
        @Override public String read(OptimizationPolicy.Key key) {
            if ("global".equals(key.namespace)) return Settings.Global.getString(context.getContentResolver(), key.name);
            if ("secure".equals(key.namespace)) return Settings.Secure.getString(context.getContentResolver(), key.name);
            return Settings.System.getString(context.getContentResolver(), key.name);
        }
        @Override public void write(OptimizationPolicy.Key key, String value) throws IOException {
            boolean written;
            if ("global".equals(key.namespace)) written = Settings.Global.putString(context.getContentResolver(), key.name, value);
            else if ("secure".equals(key.namespace)) written = Settings.Secure.putString(context.getContentResolver(), key.name, value);
            else written = Settings.System.putString(context.getContentResolver(), key.name, value);
            if (!written) throw new IOException("Android rejected " + key.qualified());
        }
    }

    static final class LocalJournal implements OptimizationTransaction.Journal {
        private final Context context;
        private final AtomicFile file;
        LocalJournal(Context context) {
            this.context = context;
            file = new AtomicFile(new File(context.getFilesDir(), "android-optimisation.json"));
        }
        private String identity() throws Exception {
            String id = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
            if (id == null || id.isEmpty()) throw new IOException("Device identity unavailable");
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder();
            for (byte item : hash) value.append(String.format(java.util.Locale.ROOT, "%02x", item & 255));
            return value.toString();
        }
        private long userSerial() throws IOException {
            android.os.UserManager users = (android.os.UserManager) context.getSystemService(Context.USER_SERVICE);
            long serial = users == null ? -1 : users.getSerialNumberForUser(android.os.Process.myUserHandle());
            if (serial < 0) throw new IOException("Android user identity unavailable");
            return serial;
        }
        @Override public OptimizationTransaction.Record load() throws Exception {
            if (!file.getBaseFile().exists() && !new File(file.getBaseFile() + ".bak").exists()) return null;
            byte[] data = file.readFully();
            if (data.length > 32768) throw new IOException("Oversized optimisation journal");
            JSONObject json = new JSONObject(new String(data, StandardCharsets.UTF_8));
            if (json.getInt("schema") != 1 || !identity().equals(json.getString("device"))
                    || json.getLong("user_serial") != userSerial())
                throw new IOException("Optimisation journal belongs to another device or user");
            LinkedHashMap<OptimizationPolicy.Key, String> before = readMap(json.getJSONObject("before"));
            LinkedHashMap<OptimizationPolicy.Key, String> after = readMap(json.getJSONObject("after"));
            if (!before.keySet().equals(after.keySet()) || before.isEmpty()) throw new IOException("Invalid journal keys");
            return new OptimizationTransaction.Record(before, after, json.getBoolean("pending"));
        }
        private LinkedHashMap<OptimizationPolicy.Key, String> readMap(JSONObject data) throws Exception {
            LinkedHashMap<OptimizationPolicy.Key, String> result = new LinkedHashMap<>();
            JSONArray names = data.names();
            if (names == null) return result;
            for (int index = 0; index < names.length(); index++) {
                String name = names.getString(index);
                OptimizationPolicy.Key key = null;
                for (OptimizationPolicy.Key candidate : OptimizationPolicy.Key.values()) if (candidate.qualified().equals(name)) key = candidate;
                if (key == null) throw new IOException("Unknown journal setting");
                Object raw = data.get(name);
                if (!(raw instanceof String) || !OptimizationPolicy.isSupportedValue(key, (String) raw)) throw new IOException("Invalid journal value");
                result.put(key, (String) raw);
            }
            return result;
        }
        @Override public void save(OptimizationTransaction.Record record) throws Exception {
            JSONObject before = new JSONObject(), after = new JSONObject();
            for (OptimizationPolicy.Key key : record.before.keySet()) before.put(key.qualified(), record.before.get(key));
            for (OptimizationPolicy.Key key : record.after.keySet()) after.put(key.qualified(), record.after.get(key));
            JSONObject data = new JSONObject().put("schema", 1).put("device", identity())
                    .put("user_serial", userSerial()).put("pending", record.pending)
                    .put("before", before).put("after", after);
            FileOutputStream stream = null;
            try {
                stream = file.startWrite();
                byte[] bytes = data.toString().getBytes(StandardCharsets.UTF_8);
                stream.write(bytes);
                stream.getFD().sync();
                file.finishWrite(stream);
                stream = null;
                if (!java.util.Arrays.equals(bytes, file.readFully())) throw new IOException("Journal persistence verification failed");
            } catch (Exception error) {
                if (stream != null) file.failWrite(stream);
                throw error;
            }
        }
        @Override public void clear() throws IOException {
            file.delete();
            if (file.getBaseFile().exists() || new File(file.getBaseFile() + ".bak").exists()
                    || new File(file.getBaseFile() + ".new").exists()) throw new IOException("Could not remove restored journal");
        }
    }
}
