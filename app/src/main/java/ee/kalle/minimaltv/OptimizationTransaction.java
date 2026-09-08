package ee.kalle.minimaltv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ee.kalle.minimaltv.OptimizationPolicy.Key;

/** Bounded settings transactions. The journal must make save and clear durable before returning. */
public final class OptimizationTransaction {
    private OptimizationTransaction() {}

    public interface Backend {
        String read(Key key) throws Exception;
        void write(Key key, String value) throws Exception;
    }

    public interface Journal {
        Record load() throws Exception;
        void save(Record record) throws Exception;
        void clear() throws Exception;
    }

    public static final class Record {
        public final LinkedHashMap<Key, String> before;
        public final LinkedHashMap<Key, String> after;
        public final boolean pending;

        public Record(Map<Key, String> before, Map<Key, String> after, boolean pending) {
            if (before == null || after == null) throw new IllegalArgumentException("Missing journal values");
            this.before = new LinkedHashMap<>(before);
            this.after = new LinkedHashMap<>(after);
            this.pending = pending;
        }
    }

    public enum Failure { NONE, CONFLICT, FAILED, WRITE_ROLLED_BACK, RECOVERY_REQUIRED }

    public static final class Result {
        /** A successful operation changed settings, or a failed operation may have left changes. */
        public final boolean changed;
        public final boolean restoredAfterFailure;
        public final String error;
        public final Failure failure;

        private Result(boolean changed, boolean restoredAfterFailure, String error, Failure failure) {
            this.changed = changed;
            this.restoredAfterFailure = restoredAfterFailure;
            this.error = error;
            this.failure = failure;
        }
    }

    public static synchronized Result apply(Backend backend, Journal journal, Map<Key, String> targets) {
        Record previous;
        LinkedHashMap<Key, String> requested;
        LinkedHashMap<Key, String> observed;
        try {
            previous = snapshot(journal.load());
            validate(previous);
            if (previous != null && previous.pending) {
                return failure(false, false, Failure.CONFLICT, "A pending transaction must be restored first");
            }
            requested = checkedTargets(targets);
            observed = readRelevant(backend, previous, requested);
            checkTracked(previous, observed);
        } catch (Conflict exception) {
            return failure(false, false, Failure.CONFLICT, exception.getMessage());
        } catch (InvalidJournal exception) {
            return failure(false, false, Failure.RECOVERY_REQUIRED, exception.getMessage());
        } catch (Exception exception) {
            return failure(false, false, Failure.FAILED, message(exception));
        }

        LinkedHashMap<Key, String> writes = new LinkedHashMap<>();
        LinkedHashMap<Key, String> before = previous == null
                ? new LinkedHashMap<Key, String>() : new LinkedHashMap<>(previous.before);
        LinkedHashMap<Key, String> after = previous == null
                ? new LinkedHashMap<Key, String>() : new LinkedHashMap<>(previous.after);
        for (Key key : requested.keySet()) {
            String current = observed.get(key);
            if (!OptimizationPolicy.isSupportedValue(key, current)
                    || OptimizationPolicy.equivalent(key, current, key.target)) continue;
            writes.put(key, key.target);
            if (!before.containsKey(key)) before.put(key, current);
            after.put(key, key.target);
        }
        if (writes.isEmpty()) return success(false);
        Record pending = new Record(before, after, true);
        Result preparation = prepare(journal, previous, pending);
        if (preparation != null) return preparation;
        List<Key> attempted = new ArrayList<>();
        try {
            writeAndVerify(backend, observed, writes, attempted);
            journal.save(new Record(before, after, false));
            return success(true);
        } catch (Exception exception) {
            return recover(backend, journal, previous, pending, observed, writes, attempted, exception);
        }
    }

    public static synchronized Result restore(Backend backend, Journal journal) {
        Record previous;
        LinkedHashMap<Key, String> observed;
        try {
            previous = snapshot(journal.load());
            validate(previous);
            if (previous == null) return success(false);
            observed = readRelevant(backend, previous, Collections.<Key, String>emptyMap());
            // Validate the entire record before changing even the first setting.
            checkTracked(previous, observed);
        } catch (Conflict exception) {
            return failure(false, false, Failure.CONFLICT, exception.getMessage());
        } catch (InvalidJournal exception) {
            return failure(false, false, Failure.RECOVERY_REQUIRED, exception.getMessage());
        } catch (Exception exception) {
            return failure(false, false, Failure.FAILED, message(exception));
        }
        LinkedHashMap<Key, String> writes = new LinkedHashMap<>();
        for (Key key : previous.before.keySet()) {
            if (!OptimizationPolicy.equivalent(key, observed.get(key), previous.before.get(key))) {
                writes.put(key, previous.before.get(key));
            }
        }
        if (writes.isEmpty()) {
            try {
                journal.clear();
                return success(false);
            } catch (Exception exception) {
                return failure(false, false, Failure.RECOVERY_REQUIRED, message(exception));
            }
        }
        Record pending = new Record(previous.before, previous.after, true);
        Result preparation = prepare(journal, previous, pending);
        if (preparation != null) return preparation;
        List<Key> attempted = new ArrayList<>();
        try {
            writeAndVerify(backend, observed, writes, attempted);
            // Include entries that were already at baseline in the final verification.
            for (Key key : previous.before.keySet()) {
                requireValue(backend, key, previous.before.get(key));
            }
        } catch (Exception exception) {
            return recover(backend, journal, previous, pending, observed, writes, attempted, exception);
        }
        try {
            journal.clear();
            return success(true);
        } catch (Exception exception) {
            // The baseline is verified. Keep it restored and retain a retryable journal.
            keepPending(journal, pending);
            return failure(true, false, Failure.RECOVERY_REQUIRED, message(exception));
        }
    }

    private static Result prepare(Journal journal, Record previous, Record pending) {
        try {
            journal.save(snapshot(pending));
            return null;
        } catch (Exception exception) {
            // save may have persisted the record before throwing. No backend writes occurred.
            if (replaceJournal(journal, previous)) {
                return failure(false, false, Failure.FAILED, message(exception));
            }
            keepPending(journal, pending);
            return failure(false, false, Failure.RECOVERY_REQUIRED, message(exception));
        }
    }

    private static void writeAndVerify(Backend backend, Map<Key, String> observed,
            Map<Key, String> writes, List<Key> attempted) throws Exception {
        for (Map.Entry<Key, String> entry : writes.entrySet()) {
            Key key = entry.getKey();
            if (!OptimizationPolicy.equivalent(key, backend.read(key), observed.get(key))) {
                throw new Conflict("Setting changed before write: " + key.qualified());
            }
            // Include writes that throw after modifying the setting in rollback.
            attempted.add(key);
            backend.write(key, entry.getValue());
            requireValue(backend, key, entry.getValue());
        }
        for (Map.Entry<Key, String> entry : writes.entrySet()) {
            requireValue(backend, entry.getKey(), entry.getValue());
        }
    }

    private static Result recover(Backend backend, Journal journal, Record previous, Record pending,
            Map<Key, String> observed, Map<Key, String> writes, List<Key> attempted,
            Exception exception) {
        boolean rolledBack = rollback(backend, observed, writes, attempted);
        if (rolledBack && replaceJournal(journal, previous)) {
            Failure failure = attempted.isEmpty()
                    ? (exception instanceof Conflict ? Failure.CONFLICT : Failure.FAILED)
                    : Failure.WRITE_ROLLED_BACK;
            return failure(false, !attempted.isEmpty(), failure, message(exception));
        }
        keepPending(journal, pending);
        return failure(!rolledBack, rolledBack && !attempted.isEmpty(),
                Failure.RECOVERY_REQUIRED, message(exception));
    }

    private static boolean rollback(Backend backend, Map<Key, String> observed,
            Map<Key, String> writes, List<Key> attempted) {
        boolean complete = true;
        for (int index = attempted.size() - 1; index >= 0; index--) {
            Key key = attempted.get(index);
            String baseline = observed.get(key);
            try {
                String current = backend.read(key);
                if (OptimizationPolicy.equivalent(key, current, baseline)) continue;
                // Do not overwrite a concurrent independent change while recovering.
                if (!OptimizationPolicy.equivalent(key, current, writes.get(key))) {
                    complete = false;
                    continue;
                }
                try {
                    backend.write(key, baseline);
                } catch (Exception ignored) {
                    // A failed write can still have taken effect. Read it back below.
                }
                requireValue(backend, key, baseline);
            } catch (Exception ignored) {
                complete = false;
            }
        }
        // Also recheck earlier restored entries after later rollback writes.
        for (Key key : attempted) {
            try {
                requireValue(backend, key, observed.get(key));
            } catch (Exception ignored) {
                complete = false;
            }
        }
        return complete;
    }

    private static LinkedHashMap<Key, String> checkedTargets(Map<Key, String> targets) {
        if (targets == null) throw new IllegalArgumentException("Missing optimisation targets");
        targets = new LinkedHashMap<>(targets);
        if (targets.containsKey(null)) throw new IllegalArgumentException("Unknown optimisation key");
        LinkedHashMap<Key, String> result = new LinkedHashMap<>();
        for (Key key : Key.values()) {
            if (!targets.containsKey(key)) continue;
            if (!OptimizationPolicy.equivalent(key, targets.get(key), key.target)) {
                throw new IllegalArgumentException("Unsupported target: " + key.qualified());
            }
            result.put(key, key.target);
        }
        // Defend against callers passing raw maps containing values outside the enum.
        if (result.size() != targets.size()) throw new IllegalArgumentException("Unknown optimisation key");
        return result;
    }

    private static LinkedHashMap<Key, String> readRelevant(Backend backend, Record record,
            Map<Key, String> requested) throws Exception {
        LinkedHashMap<Key, String> result = new LinkedHashMap<>();
        for (Key key : Key.values()) {
            if (requested.containsKey(key) || (record != null && record.before.containsKey(key))) {
                result.put(key, backend.read(key));
            }
        }
        return result;
    }

    private static void checkTracked(Record record, Map<Key, String> observed) throws Conflict {
        if (record == null) return;
        for (Key key : record.before.keySet()) {
            String current = observed.get(key);
            if (!OptimizationPolicy.equivalent(key, current, record.before.get(key))
                    && !OptimizationPolicy.equivalent(key, current, record.after.get(key))) {
                throw new Conflict("Independently changed setting: " + key.qualified());
            }
        }
    }

    private static void validate(Record record) throws InvalidJournal {
        if (record == null) return;
        if (record.before.isEmpty() || !record.before.keySet().equals(record.after.keySet())) {
            throw new InvalidJournal("Journal key sets are invalid");
        }
        for (Key key : record.before.keySet()) {
            if (!OptimizationPolicy.isSupportedValue(key, record.before.get(key))
                    || !OptimizationPolicy.equivalent(key, record.after.get(key), key.target)) {
                throw new InvalidJournal("Journal contains unsupported values");
            }
        }
    }

    private static void requireValue(Backend backend, Key key, String expected) throws Exception {
        if (!OptimizationPolicy.equivalent(key, backend.read(key), expected)) {
            throw new IllegalStateException("Setting verification failed: " + key.qualified());
        }
    }

    private static Record snapshot(Record record) {
        return record == null ? null : new Record(record.before, record.after, record.pending);
    }

    private static boolean replaceJournal(Journal journal, Record record) {
        try {
            if (record == null) journal.clear();
            else journal.save(snapshot(record));
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void keepPending(Journal journal, Record record) {
        try {
            journal.save(new Record(record.before, record.after, true));
        } catch (Exception ignored) {
            // The first durable pending record is retained by an atomic journal implementation.
        }
    }

    private static Result success(boolean changed) {
        return new Result(changed, false, null, Failure.NONE);
    }

    private static Result failure(boolean changed, boolean restored, Failure failure, String error) {
        return new Result(changed, restored, error, failure);
    }

    private static String message(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isEmpty() ? exception.getClass().getSimpleName() : message;
    }

    private static final class Conflict extends Exception {
        Conflict(String message) { super(message); }
    }

    private static final class InvalidJournal extends Exception {
        InvalidJournal(String message) { super(message); }
    }
}
