package ee.kalle.minimaltv;

import java.util.HashMap;
import java.util.Map;

/** Strict parsing of the small power-service fields used by the optimiser. */
public final class OptimizationDiagnostics {
    private OptimizationDiagnostics() { }

    public static final class PowerState {
        public final String wakefulness;
        public final boolean screensaverEnabled;
        public final long screenTimeout;
        public final long sleepTimeout;

        private PowerState(String wakefulness, boolean screensaverEnabled, long screenTimeout, long sleepTimeout) {
            this.wakefulness = wakefulness;
            this.screensaverEnabled = screensaverEnabled;
            this.screenTimeout = screenTimeout;
            this.sleepTimeout = sleepTimeout;
        }
    }

    public static PowerState parsePower(String text) {
        if (text == null || text.length() > 131072) return null;
        Map<String, String> fields = new HashMap<>();
        for (String line : text.split("\\r?\\n")) {
            int separator = line.indexOf('=');
            if (separator < 0) continue;
            String name = line.substring(0, separator).trim();
            if (!("mWakefulness".equals(name) || "mDreamsEnabledSetting".equals(name)
                    || "mScreenOffTimeoutSetting".equals(name) || "mSleepTimeoutSetting".equals(name))) continue;
            String value = line.substring(separator + 1).trim();
            if (fields.containsKey(name) && !fields.get(name).equals(value)) return null;
            fields.put(name, value);
        }
        String wake = fields.get("mWakefulness");
        if (!("Awake".equals(wake) || "Asleep".equals(wake) || "Dreaming".equals(wake) || "Dozing".equals(wake))) return null;
        String dreams = fields.get("mDreamsEnabledSetting");
        if (!("true".equals(dreams) || "false".equals(dreams))) return null;
        try {
            long screen = Long.parseLong(fields.get("mScreenOffTimeoutSetting"));
            long sleep = Long.parseLong(fields.get("mSleepTimeoutSetting"));
            if (screen < -1 || sleep < -1) return null;
            return new PowerState(wake, Boolean.parseBoolean(dreams), screen, sleep);
        } catch (RuntimeException error) {
            return null;
        }
    }
}
