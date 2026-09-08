package ee.kalle.minimaltv;

import android.Manifest;
import android.app.Application;
import android.content.Context;
import android.provider.Settings;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "en-rUS-land-xhdpi")
public class AndroidOptimizerTest {
    private Context context;
    private Backend backend;
    private Journal journal;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Settings.Secure.putString(context.getContentResolver(), Settings.Secure.ANDROID_ID, "optimizer-test-device");
        backend = new Backend();
        journal = new Journal();
    }

    private void grant() {
        Shadows.shadowOf((Application) context).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS);
    }

    @Test public void scanIsReadOnlyAndChangesRequireAccess() {
        Shadows.shadowOf((Application) context).denyPermissions(Manifest.permission.WRITE_SECURE_SETTINGS);
        AndroidOptimizer optimizer = new AndroidOptimizer(context, backend, journal);
        AndroidOptimizer.Report report = optimizer.scan();
        assertFalse(report.accessGranted);
        assertEquals(3, report.changeCount);
        assertFalse(optimizer.optimize(report).success);
        assertEquals(0, backend.writes);
        assertNull(journal.record);
    }

    @Test public void appliesPrivacyAndRestoresExactOriginals() {
        grant();
        Map<OptimizationPolicy.Key, String> original = new LinkedHashMap<>(backend.values);
        AndroidOptimizer optimizer = new AndroidOptimizer(context, backend, journal);
        assertTrue(optimizer.optimize(optimizer.scan()).success);
        assertEquals("0", backend.read(OptimizationPolicy.Key.LOCATION_MODE));
        assertEquals("", backend.read(OptimizationPolicy.Key.ASSISTANT));
        assertEquals("", backend.read(OptimizationPolicy.Key.VOICE_INTERACTION_SERVICE));
        assertTrue(optimizer.scan().canRestore);
        assertEquals(0, optimizer.scan().changeCount);
        assertTrue(optimizer.restore().success);
        assertEquals(original, backend.values);
        assertNull(journal.record);
    }

    @Test public void refusesStalePreviewWithoutWriting() {
        grant();
        AndroidOptimizer optimizer = new AndroidOptimizer(context, backend, journal);
        AndroidOptimizer.Report preview = optimizer.scan();
        backend.values.put(OptimizationPolicy.Key.LOCATION_MODE, "1");
        assertFalse(optimizer.optimize(preview).success);
        assertEquals(0, backend.writes);
        assertNull(journal.record);
    }

    @Test public void privateJournalSurvivesNewInstanceAndClearsAllCopies() throws Exception {
        AndroidOptimizer.LocalJournal local = new AndroidOptimizer.LocalJournal(context);
        Map<OptimizationPolicy.Key, String> before = new LinkedHashMap<>(), after = new LinkedHashMap<>();
        before.put(OptimizationPolicy.Key.ASSISTANT, "example.voice/.Assistant");
        after.put(OptimizationPolicy.Key.ASSISTANT, "");
        local.save(new OptimizationTransaction.Record(before, after, true));
        OptimizationTransaction.Record loaded = new AndroidOptimizer.LocalJournal(context).load();
        assertEquals(before, loaded.before);
        assertEquals(after, loaded.after);
        assertTrue(loaded.pending);
        File base = new File(context.getFilesDir(), "android-optimisation.json");
        Files.write(new File(base + ".new").toPath(), "partial".getBytes(StandardCharsets.UTF_8));
        Files.copy(base.toPath(), new File(base + ".bak").toPath());
        local.clear();
        assertFalse(base.exists());
        assertFalse(new File(base + ".new").exists());
        assertFalse(new File(base + ".bak").exists());
        assertNull(local.load());
    }

    @Test public void corruptedOrForeignJournalCannotAuthorizeNewChanges() throws Exception {
        grant();
        File base = new File(context.getFilesDir(), "android-optimisation.json");
        Files.write(base.toPath(), "{broken".getBytes(StandardCharsets.UTF_8));
        AndroidOptimizer optimizer = new AndroidOptimizer(context, backend, new AndroidOptimizer.LocalJournal(context));
        assertTrue(optimizer.scan().recoveryPending);
        assertFalse(optimizer.optimize(optimizer.scan()).success);
        assertEquals(0, backend.writes);
        Files.delete(base.toPath());
        Map<OptimizationPolicy.Key, String> before = new LinkedHashMap<>(), after = new LinkedHashMap<>();
        before.put(OptimizationPolicy.Key.LOCATION_MODE, "3"); after.put(OptimizationPolicy.Key.LOCATION_MODE, "0");
        AndroidOptimizer.LocalJournal local = new AndroidOptimizer.LocalJournal(context);
        local.save(new OptimizationTransaction.Record(before, after, false));
        Settings.Secure.putString(context.getContentResolver(), Settings.Secure.ANDROID_ID, "another-device");
        assertThrows(Exception.class, local::load);
    }

    @Test public void systemBackendUsesTheCorrectNamespaces() throws Exception {
        AndroidOptimizer.SettingsBackend settings = new AndroidOptimizer.SettingsBackend(context);
        settings.write(OptimizationPolicy.Key.SCREEN_OFF_TIMEOUT, "600000");
        settings.write(OptimizationPolicy.Key.LOCATION_MODE, "0");
        settings.write(OptimizationPolicy.Key.ASSISTANT, "example.voice/.Assistant");
        assertEquals("600000", Settings.System.getString(context.getContentResolver(), "screen_off_timeout"));
        assertEquals("0", Settings.Secure.getString(context.getContentResolver(), "location_mode"));
        assertEquals("example.voice/.Assistant", settings.read(OptimizationPolicy.Key.ASSISTANT));
    }

    private static final class Backend implements OptimizationTransaction.Backend {
        final LinkedHashMap<OptimizationPolicy.Key, String> values = new LinkedHashMap<>();
        int writes;
        Backend() {
            for (OptimizationPolicy.Key key : OptimizationPolicy.Key.values()) values.put(key, key.target);
            values.put(OptimizationPolicy.Key.LOCATION_MODE, "3");
            values.put(OptimizationPolicy.Key.ASSISTANT, "example.voice/.Assistant");
            values.put(OptimizationPolicy.Key.VOICE_INTERACTION_SERVICE, "example.voice/.Interaction");
        }
        @Override public String read(OptimizationPolicy.Key key) { return values.get(key); }
        @Override public void write(OptimizationPolicy.Key key, String value) { writes++; values.put(key, value); }
    }
    private static final class Journal implements OptimizationTransaction.Journal {
        OptimizationTransaction.Record record;
        @Override public OptimizationTransaction.Record load() { return record; }
        @Override public void save(OptimizationTransaction.Record record) { this.record = record; }
        @Override public void clear() { record = null; }
    }
}
