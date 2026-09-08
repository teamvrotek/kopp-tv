package ee.kalle.minimaltv;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ee.kalle.minimaltv.OptimizationPolicy.Key;
import ee.kalle.minimaltv.OptimizationTransaction.Failure;
import ee.kalle.minimaltv.OptimizationTransaction.Record;
import ee.kalle.minimaltv.OptimizationTransaction.Result;

import static ee.kalle.minimaltv.OptimizationPolicy.Key.*;
import static org.junit.Assert.*;

public class OptimizationTransactionTest {
    @Test public void assistantComponentsAreClearedAndRestoredExactly() {
        Fixture f = new Fixture();
        f.backend.values.put(ASSISTANT, "com.example/.Assistant");
        f.backend.values.put(VOICE_INTERACTION_SERVICE, "com.example/com.example.Voice$Service");
        assertSuccess(f.apply(ASSISTANT, VOICE_INTERACTION_SERVICE), true);
        assertEquals("", f.backend.values.get(ASSISTANT));
        assertEquals("", f.backend.values.get(VOICE_INTERACTION_SERVICE));
        assertSuccess(OptimizationTransaction.restore(f.backend, f.journal), true);
        assertEquals("com.example/.Assistant", f.backend.values.get(ASSISTANT));
        assertEquals("com.example/com.example.Voice$Service", f.backend.values.get(VOICE_INTERACTION_SERVICE));
    }

    @Test public void changedAssistantComponentConflictsBeforeRestoringAnyPrivacyControl() {
        Fixture f = new Fixture();
        f.backend.values.put(LOCATION_MODE, "3");
        f.backend.values.put(ASSISTANT, "com.example/.Assistant");
        assertSuccess(f.apply(LOCATION_MODE, ASSISTANT), true);
        f.backend.values.put(ASSISTANT, "com.other/.Assistant");
        int writes = f.backend.writes;
        assertEquals(Failure.CONFLICT, OptimizationTransaction.restore(f.backend, f.journal).failure);
        assertEquals(writes, f.backend.writes);
        assertEquals("0", f.backend.values.get(LOCATION_MODE));
    }

    @Test public void firstNoOpHasNoJournalAndDoesNotCanonicalizeZero() {
        Fixture f = new Fixture();
        f.backend.values.put(WINDOW_ANIMATION_SCALE, "0.0");
        Result result = f.apply(WINDOW_ANIMATION_SCALE);
        assertSuccess(result, false);
        assertEquals("0.0", f.backend.values.get(WINDOW_ANIMATION_SCALE));
        assertNull(f.journal.record);
        assertEquals(0, f.journal.saves);
        assertEquals(0, f.backend.writes);
    }

    @Test public void unsupportedAndAbsentSettingsAreNotWritten() {
        Fixture f = new Fixture();
        f.backend.values.put(WINDOW_ANIMATION_SCALE, "NaN");
        f.backend.values.put(SCREEN_OFF_TIMEOUT, "-1");
        assertSuccess(f.apply(WINDOW_ANIMATION_SCALE, SCREEN_OFF_TIMEOUT, SLEEP_TIMEOUT), false);
        assertEquals(0, f.backend.writes);
        assertNull(f.journal.record);
    }

    @Test public void pendingJournalIsDurableBeforeFirstWriteAndBaselineIsExact() {
        Fixture f = new Fixture();
        f.backend.values.put(WINDOW_ANIMATION_SCALE, "1.00");
        f.backend.onWrite = () -> {
            assertNotNull(f.journal.record);
            assertTrue(f.journal.record.pending);
            assertEquals("1.00", f.journal.record.before.get(WINDOW_ANIMATION_SCALE));
        };
        assertSuccess(f.apply(WINDOW_ANIMATION_SCALE), true);
        assertFalse(f.journal.record.pending);
        assertEquals("1.00", f.journal.record.before.get(WINDOW_ANIMATION_SCALE));
        assertEquals("0", f.journal.record.after.get(WINDOW_ANIMATION_SCALE));
        assertTrue(f.events.indexOf("save:pending") < f.events.indexOf("write:window_animation_scale:0"));
    }

    @Test public void repeatedApplyPreservesFirstBaselineIncludingAddedKeys() {
        Fixture f = new Fixture();
        f.backend.values.put(WINDOW_ANIMATION_SCALE, "1.00");
        assertSuccess(f.apply(WINDOW_ANIMATION_SCALE), true);
        int saves = f.journal.saves;
        assertSuccess(f.apply(WINDOW_ANIMATION_SCALE), false);
        assertEquals(saves, f.journal.saves);
        f.backend.values.put(SLEEP_TIMEOUT, "-1");
        assertSuccess(f.apply(WINDOW_ANIMATION_SCALE, SLEEP_TIMEOUT), true);
        assertEquals("1.00", f.journal.record.before.get(WINDOW_ANIMATION_SCALE));
        assertEquals("-1", f.journal.record.before.get(SLEEP_TIMEOUT));
        assertEquals(2, f.journal.record.before.size());
        assertSuccess(OptimizationTransaction.restore(f.backend, f.journal), true);
        assertEquals("1.00", f.backend.values.get(WINDOW_ANIMATION_SCALE));
        assertEquals("-1", f.backend.values.get(SLEEP_TIMEOUT));
        assertNull(f.journal.record);
    }

    @Test public void reapplyingAfterBaselineResetDoesNotReplaceOriginalFormatting() {
        Fixture f = appliedAnimation("1.00");
        f.backend.values.put(WINDOW_ANIMATION_SCALE, "1.0");
        assertSuccess(f.apply(WINDOW_ANIMATION_SCALE), true);
        assertEquals("1.00", f.journal.record.before.get(WINDOW_ANIMATION_SCALE));
    }

    @Test public void pendingJournalBlocksApplyButCanBeRestored() {
        Fixture f = appliedAnimation("1");
        f.journal.record = new Record(f.journal.record.before, f.journal.record.after, true);
        int writes = f.backend.writes;
        assertEquals(Failure.CONFLICT, f.apply(WINDOW_ANIMATION_SCALE).failure);
        assertEquals(writes, f.backend.writes);
        assertTrue(f.journal.record.pending);
        assertSuccess(OptimizationTransaction.restore(f.backend, f.journal), true);
        assertNull(f.journal.record);
    }

    @Test public void staleActiveJournalRejectsEvenAnUnrelatedApplyBeforeAnyWrite() {
        Fixture f = appliedAnimation("1");
        f.backend.values.put(WINDOW_ANIMATION_SCALE, "0.5");
        f.backend.values.put(SLEEP_TIMEOUT, "-1");
        int writes = f.backend.writes;
        assertEquals(Failure.CONFLICT, f.apply(SLEEP_TIMEOUT).failure);
        assertEquals(writes, f.backend.writes);
        assertFalse(f.journal.record.before.containsKey(SLEEP_TIMEOUT));
    }

    @Test public void restorePreflightsAllKeysBeforeWritingAny() {
        Fixture f = new Fixture();
        f.backend.values.put(WINDOW_ANIMATION_SCALE, "1");
        f.backend.values.put(SLEEP_TIMEOUT, "-1");
        assertSuccess(f.apply(WINDOW_ANIMATION_SCALE, SLEEP_TIMEOUT), true);
        f.backend.values.put(SLEEP_TIMEOUT, "120000");
        int writes = f.backend.writes;
        assertEquals(Failure.CONFLICT, OptimizationTransaction.restore(f.backend, f.journal).failure);
        assertEquals(writes, f.backend.writes);
        assertEquals("0", f.backend.values.get(WINDOW_ANIMATION_SCALE));
    }

    @Test public void removedTrackedSettingIsAConflictAndIsNotCreated() {
        Fixture f = appliedAnimation("1");
        f.backend.values.remove(WINDOW_ANIMATION_SCALE);
        int writes = f.backend.writes;
        assertEquals(Failure.CONFLICT, OptimizationTransaction.restore(f.backend, f.journal).failure);
        assertEquals(writes, f.backend.writes);
    }

    @Test public void alreadyRestoredValuesClearBackupWithoutWrites() {
        Fixture f = appliedAnimation("1.00");
        f.backend.values.put(WINDOW_ANIMATION_SCALE, "1.0");
        int writes = f.backend.writes;
        assertSuccess(OptimizationTransaction.restore(f.backend, f.journal), false);
        assertEquals(writes, f.backend.writes);
        assertNull(f.journal.record);
    }

    @Test public void failureBeforeWriteRollsBackEarlierWrites() {
        Fixture f = twoAnimations();
        f.backend.writeFailures.put(2, false);
        Result result = f.apply(WINDOW_ANIMATION_SCALE, TRANSITION_ANIMATION_SCALE);
        assertEquals(Failure.WRITE_ROLLED_BACK, result.failure);
        assertTrue(result.restoredAfterFailure);
        assertFalse(result.changed);
        assertEquals("1.00", f.backend.values.get(WINDOW_ANIMATION_SCALE));
        assertEquals("2", f.backend.values.get(TRANSITION_ANIMATION_SCALE));
        assertNull(f.journal.record);
    }

    @Test public void failureAfterWriteIncludesUncertainWriteInRollback() {
        Fixture f = twoAnimations();
        f.backend.writeFailures.put(2, true);
        Result result = f.apply(WINDOW_ANIMATION_SCALE, TRANSITION_ANIMATION_SCALE);
        assertEquals(Failure.WRITE_ROLLED_BACK, result.failure);
        assertTrue(result.restoredAfterFailure);
        assertEquals("1.00", f.backend.values.get(WINDOW_ANIMATION_SCALE));
        assertEquals("2", f.backend.values.get(TRANSITION_ANIMATION_SCALE));
        assertNull(f.journal.record);
    }

    @Test public void failedReadbackRollsBackAWriteThatActuallyHappened() {
        Fixture f = new Fixture();
        f.backend.values.put(WINDOW_ANIMATION_SCALE, "1");
        f.backend.readFailures.add(3); // Initial read, pre-write check, then write verification.
        Result result = f.apply(WINDOW_ANIMATION_SCALE);
        assertEquals(Failure.WRITE_ROLLED_BACK, result.failure);
        assertEquals("1", f.backend.values.get(WINDOW_ANIMATION_SCALE));
        assertNull(f.journal.record);
    }

    @Test public void ignoredWriteFailsVerificationWithoutLeavingABackup() {
        Fixture f = new Fixture();
        f.backend.values.put(WINDOW_ANIMATION_SCALE, "1");
        f.backend.ignoredWrites.add(1);
        Result result = f.apply(WINDOW_ANIMATION_SCALE);
        assertEquals(Failure.WRITE_ROLLED_BACK, result.failure);
        assertFalse(result.changed);
        assertNull(f.journal.record);
    }

    @Test public void failedRollbackKeepsPendingRecoveryAndCanRestoreLater() {
        Fixture f = twoAnimations();
        f.backend.writeFailures.put(2, true);
        f.backend.writeFailures.put(3, false);
        Result result = f.apply(WINDOW_ANIMATION_SCALE, TRANSITION_ANIMATION_SCALE);
        assertEquals(Failure.RECOVERY_REQUIRED, result.failure);
        assertTrue(result.changed);
        assertFalse(result.restoredAfterFailure);
        assertTrue(f.journal.record.pending);
        assertEquals("1.00", f.backend.values.get(WINDOW_ANIMATION_SCALE));
        assertEquals("0", f.backend.values.get(TRANSITION_ANIMATION_SCALE));
        assertSuccess(OptimizationTransaction.restore(f.backend, f.journal), true);
        assertEquals("2", f.backend.values.get(TRANSITION_ANIMATION_SCALE));
        assertNull(f.journal.record);
    }

    @Test public void rollbackWriteThatThrowsAfterSuccessStillCountsAsVerifiedRecovery() {
        Fixture f = new Fixture();
        f.backend.values.put(WINDOW_ANIMATION_SCALE, "1");
        f.backend.writeFailures.put(1, true);
        f.backend.writeFailures.put(2, true);
        Result result = f.apply(WINDOW_ANIMATION_SCALE);
        assertEquals(Failure.WRITE_ROLLED_BACK, result.failure);
        assertEquals("1", f.backend.values.get(WINDOW_ANIMATION_SCALE));
        assertNull(f.journal.record);
    }

    @Test public void independentChangeDuringFailedWriteIsNotOverwrittenByRollback() {
        Fixture f = new Fixture();
        f.backend.values.put(WINDOW_ANIMATION_SCALE, "1");
        f.backend.onWrite = () -> {
            f.backend.values.put(WINDOW_ANIMATION_SCALE, "0.5");
            throw new IllegalStateException("External writer changed value");
        };
        Result result = f.apply(WINDOW_ANIMATION_SCALE);
        assertEquals(Failure.RECOVERY_REQUIRED, result.failure);
        assertEquals("0.5", f.backend.values.get(WINDOW_ANIMATION_SCALE));
        assertEquals(1, f.backend.writes);
        assertTrue(f.journal.record.pending);
    }

    @Test public void rollbackOfRepeatedApplyKeepsPreviouslyOptimizedStateAndFirstBackup() {
        Fixture f = appliedAnimation("1.00");
        f.backend.values.put(SLEEP_TIMEOUT, "-1");
        f.backend.writeFailures.put(2, true);
        Result result = f.apply(SLEEP_TIMEOUT);
        assertEquals(Failure.WRITE_ROLLED_BACK, result.failure);
        assertEquals("0", f.backend.values.get(WINDOW_ANIMATION_SCALE));
        assertEquals("-1", f.backend.values.get(SLEEP_TIMEOUT));
        assertEquals(1, f.journal.record.before.size());
        assertEquals("1.00", f.journal.record.before.get(WINDOW_ANIMATION_SCALE));
        assertFalse(f.journal.record.pending);
    }

    @Test public void failedRestoreRollsBackToStateBeforeRestoreAndKeepsBackup() {
        Fixture f = twoAnimations();
        assertSuccess(f.apply(WINDOW_ANIMATION_SCALE, TRANSITION_ANIMATION_SCALE), true);
        f.backend.writeFailures.put(4, true);
        Result result = OptimizationTransaction.restore(f.backend, f.journal);
        assertEquals(Failure.WRITE_ROLLED_BACK, result.failure);
        assertEquals("0", f.backend.values.get(WINDOW_ANIMATION_SCALE));
        assertEquals("0", f.backend.values.get(TRANSITION_ANIMATION_SCALE));
        assertFalse(f.journal.record.pending);
        assertEquals("1.00", f.journal.record.before.get(WINDOW_ANIMATION_SCALE));
    }

    @Test public void journalSaveFailureNeverStartsBackendWrites() {
        Fixture f = new Fixture();
        f.backend.values.put(WINDOW_ANIMATION_SCALE, "1");
        f.journal.failBeforeSave.add(1);
        Result result = f.apply(WINDOW_ANIMATION_SCALE);
        assertEquals(Failure.FAILED, result.failure);
        assertFalse(result.restoredAfterFailure);
        assertEquals(0, f.backend.writes);
        assertNull(f.journal.record);
    }

    @Test public void uncertainJournalSaveBeforeAnyWriteIsCleanedUp() {
        Fixture f = new Fixture();
        f.backend.values.put(WINDOW_ANIMATION_SCALE, "1");
        f.journal.failAfterSave.add(1);
        assertEquals(Failure.FAILED, f.apply(WINDOW_ANIMATION_SCALE).failure);
        assertEquals(0, f.backend.writes);
        assertNull(f.journal.record);
    }

    @Test public void failedFinalJournalCommitRollsBackSettings() {
        Fixture f = new Fixture();
        f.backend.values.put(WINDOW_ANIMATION_SCALE, "1.00");
        f.journal.failAfterSave.add(2);
        Result result = f.apply(WINDOW_ANIMATION_SCALE);
        assertEquals(Failure.WRITE_ROLLED_BACK, result.failure);
        assertEquals("1.00", f.backend.values.get(WINDOW_ANIMATION_SCALE));
        assertNull(f.journal.record);
    }

    @Test public void journalCleanupFailureAfterRollbackLeavesPendingRecovery() {
        Fixture f = new Fixture();
        f.backend.values.put(WINDOW_ANIMATION_SCALE, "1");
        f.backend.writeFailures.put(1, true);
        f.journal.failClear = true;
        Result result = f.apply(WINDOW_ANIMATION_SCALE);
        assertEquals(Failure.RECOVERY_REQUIRED, result.failure);
        assertTrue(result.restoredAfterFailure);
        assertFalse(result.changed);
        assertEquals("1", f.backend.values.get(WINDOW_ANIMATION_SCALE));
        assertTrue(f.journal.record.pending);
        f.journal.failClear = false;
        assertSuccess(OptimizationTransaction.restore(f.backend, f.journal), false);
    }

    @Test public void failedJournalClearAfterRestoreLeavesVerifiedBaselineAndRetryableJournal() {
        Fixture f = appliedAnimation("1.00");
        f.journal.failClear = true;
        Result result = OptimizationTransaction.restore(f.backend, f.journal);
        assertEquals(Failure.RECOVERY_REQUIRED, result.failure);
        assertEquals("1.00", f.backend.values.get(WINDOW_ANIMATION_SCALE));
        assertTrue(f.journal.record.pending);
        f.journal.failClear = false;
        assertSuccess(OptimizationTransaction.restore(f.backend, f.journal), false);
        assertNull(f.journal.record);
    }

    @Test public void readFailureDoesNotCreateJournalOrWrite() {
        Fixture f = new Fixture();
        f.backend.readFailures.add(1);
        assertEquals(Failure.FAILED, f.apply(WINDOW_ANIMATION_SCALE).failure);
        assertEquals(0, f.backend.writes);
        assertEquals(0, f.journal.saves);
    }

    @Test public void settingChangedBetweenPreflightAndWriteIsRejected() {
        Fixture f = new Fixture();
        f.backend.values.put(WINDOW_ANIMATION_SCALE, "1");
        f.backend.beforeRead = count -> {
            if (count == 2) f.backend.values.put(WINDOW_ANIMATION_SCALE, "0.5");
        };
        assertEquals(Failure.CONFLICT, f.apply(WINDOW_ANIMATION_SCALE).failure);
        assertEquals(0, f.backend.writes);
        assertEquals("0.5", f.backend.values.get(WINDOW_ANIMATION_SCALE));
        assertNull(f.journal.record);
    }

    @Test public void corruptRecordIsNeverUsedForWrites() {
        Fixture f = new Fixture();
        f.journal.record = new Record(values(WINDOW_ANIMATION_SCALE, "NaN"),
                values(WINDOW_ANIMATION_SCALE, "0"), true);
        assertEquals(Failure.RECOVERY_REQUIRED, OptimizationTransaction.restore(f.backend, f.journal).failure);
        assertEquals(0, f.backend.writes);
        assertNotNull(f.journal.record);
    }

    @Test public void arbitraryTargetIsRejectedWithoutReadsOrWrites() {
        Fixture f = new Fixture();
        assertEquals(Failure.FAILED, OptimizationTransaction.apply(f.backend, f.journal,
                values(WINDOW_ANIMATION_SCALE, "2")).failure);
        assertEquals(0, f.backend.reads);
        assertEquals(0, f.backend.writes);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Test public void rawMapCannotIntroduceUnknownKeys() {
        Fixture f = new Fixture();
        Map raw = new LinkedHashMap();
        raw.put("global/other_setting", "0");
        assertEquals(Failure.FAILED, OptimizationTransaction.apply(f.backend, f.journal, raw).failure);
        assertEquals(0, f.backend.reads);
        assertEquals(0, f.backend.writes);
    }

    @Test public void recordsCopyInputsAndApplyDoesNotMutateTargets() {
        Map<Key, String> before = values(WINDOW_ANIMATION_SCALE, "1");
        Map<Key, String> after = values(WINDOW_ANIMATION_SCALE, "0");
        Record record = new Record(before, after, false);
        before.clear();
        after.clear();
        assertEquals("1", record.before.get(WINDOW_ANIMATION_SCALE));
        assertEquals("0", record.after.get(WINDOW_ANIMATION_SCALE));
        Fixture f = new Fixture();
        f.backend.values.put(WINDOW_ANIMATION_SCALE, "1");
        Map<Key, String> targets = values(WINDOW_ANIMATION_SCALE, "0.0");
        assertSuccess(OptimizationTransaction.apply(f.backend, f.journal, targets), true);
        assertEquals("0.0", targets.get(WINDOW_ANIMATION_SCALE));
    }

    private static Fixture appliedAnimation(String baseline) {
        Fixture f = new Fixture();
        f.backend.values.put(WINDOW_ANIMATION_SCALE, baseline);
        assertSuccess(f.apply(WINDOW_ANIMATION_SCALE), true);
        return f;
    }

    private static Fixture twoAnimations() {
        Fixture f = new Fixture();
        f.backend.values.put(WINDOW_ANIMATION_SCALE, "1.00");
        f.backend.values.put(TRANSITION_ANIMATION_SCALE, "2");
        return f;
    }

    private static LinkedHashMap<Key, String> values(Key key, String value) {
        LinkedHashMap<Key, String> result = new LinkedHashMap<>();
        result.put(key, value);
        return result;
    }

    private static void assertSuccess(Result result, boolean changed) {
        assertNull(result.error);
        assertEquals(Failure.NONE, result.failure);
        assertEquals(changed, result.changed);
        assertFalse(result.restoredAfterFailure);
    }

    private static class Fixture {
        final List<String> events = new ArrayList<>();
        final FakeBackend backend = new FakeBackend(events);
        final FakeJournal journal = new FakeJournal(events);

        Result apply(Key... keys) {
            LinkedHashMap<Key, String> targets = new LinkedHashMap<>();
            for (Key key : keys) targets.put(key, key.target);
            return OptimizationTransaction.apply(backend, journal, targets);
        }
    }

    private static class FakeBackend implements OptimizationTransaction.Backend {
        final LinkedHashMap<Key, String> values = new LinkedHashMap<>();
        final Map<Integer, Boolean> writeFailures = new HashMap<>();
        final Set<Integer> readFailures = new HashSet<>();
        final Set<Integer> ignoredWrites = new HashSet<>();
        final List<String> events;
        int reads;
        int writes;
        Runnable onWrite;
        java.util.function.IntConsumer beforeRead;

        FakeBackend(List<String> events) { this.events = events; }

        @Override public String read(Key key) throws Exception {
            reads++;
            if (beforeRead != null) beforeRead.accept(reads);
            if (readFailures.contains(reads)) throw new Exception("Read failed");
            return values.get(key);
        }

        @Override public void write(Key key, String value) throws Exception {
            writes++;
            events.add("write:" + key.name + ":" + value);
            if (onWrite != null) onWrite.run();
            Boolean failureAfterMutation = writeFailures.get(writes);
            if (Boolean.FALSE.equals(failureAfterMutation)) throw new Exception("Write failed before mutation");
            if (!ignoredWrites.contains(writes)) values.put(key, value);
            if (Boolean.TRUE.equals(failureAfterMutation)) throw new Exception("Write failed after mutation");
        }
    }

    private static class FakeJournal implements OptimizationTransaction.Journal {
        Record record;
        final List<String> events;
        final Set<Integer> failBeforeSave = new HashSet<>();
        final Set<Integer> failAfterSave = new HashSet<>();
        int saves;
        boolean failClear;

        FakeJournal(List<String> events) { this.events = events; }

        @Override public Record load() {
            return record == null ? null : new Record(record.before, record.after, record.pending);
        }

        @Override public void save(Record record) throws Exception {
            saves++;
            events.add(record.pending ? "save:pending" : "save:active");
            if (failBeforeSave.contains(saves)) throw new Exception("Journal save failed before persistence");
            this.record = new Record(record.before, record.after, record.pending);
            if (failAfterSave.contains(saves)) throw new Exception("Journal save failed after persistence");
        }

        @Override public void clear() throws Exception {
            events.add("clear");
            if (failClear) throw new Exception("Journal clear failed");
            record = null;
        }
    }
}
