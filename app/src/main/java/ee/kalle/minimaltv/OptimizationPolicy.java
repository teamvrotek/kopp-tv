package ee.kalle.minimaltv;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/** The complete allowlist of reversible settings offered by the launcher. */
public final class OptimizationPolicy {
    private OptimizationPolicy() {}

    public enum Key {
        WINDOW_ANIMATION_SCALE("global", "window_animation_scale", "0", Kind.SCALE),
        TRANSITION_ANIMATION_SCALE("global", "transition_animation_scale", "0", Kind.SCALE),
        ANIMATOR_DURATION_SCALE("global", "animator_duration_scale", "0", Kind.SCALE),
        STAY_ON_WHILE_PLUGGED_IN("global", "stay_on_while_plugged_in", "0", Kind.POWER_MASK),
        SCREENSAVER_ENABLED("secure", "screensaver_enabled", "0", Kind.BOOLEAN),
        SCREENSAVER_ACTIVATE_ON_SLEEP("secure", "screensaver_activate_on_sleep", "0", Kind.BOOLEAN),
        SCREENSAVER_ACTIVATE_ON_DOCK("secure", "screensaver_activate_on_dock", "0", Kind.BOOLEAN),
        SCREEN_OFF_TIMEOUT("system", "screen_off_timeout", "600000", Kind.TIMEOUT),
        SLEEP_TIMEOUT("secure", "sleep_timeout", "600000", Kind.SLEEP_TIMEOUT),
        LOCATION_MODE("secure", "location_mode", "0", Kind.LOCATION_MODE),
        WIFI_SCAN_ALWAYS_ENABLED("global", "wifi_scan_always_enabled", "0", Kind.BOOLEAN),
        BLE_SCAN_ALWAYS_ENABLED("global", "ble_scan_always_enabled", "0", Kind.BOOLEAN),
        ASSISTANT("secure", "assistant", "", Kind.COMPONENT),
        VOICE_INTERACTION_SERVICE("secure", "voice_interaction_service", "", Kind.COMPONENT);

        public final String namespace;
        public final String name;
        public final String target;
        private final Kind kind;

        Key(String namespace, String name, String target, Kind kind) {
            this.namespace = namespace;
            this.name = name;
            this.target = target;
            this.kind = kind;
        }

        public String qualified() {
            return namespace + "/" + name;
        }
    }

    private enum Kind { SCALE, BOOLEAN, POWER_MASK, TIMEOUT, SLEEP_TIMEOUT, LOCATION_MODE, COMPONENT }

    /** Missing and malformed observations never cause a setting to be created or repaired. */
    public static LinkedHashMap<Key, String> plan(Map<Key, String> observed) {
        LinkedHashMap<Key, String> changes = new LinkedHashMap<>();
        if (observed == null) return changes;
        for (Key key : Key.values()) {
            String value = observed.get(key);
            if (isSupportedValue(key, value) && !equivalent(key, value, key.target)) {
                changes.put(key, key.target);
            }
        }
        return changes;
    }

    public static boolean isSupportedValue(Key key, String value) {
        if (key != null && key.kind == Kind.COMPONENT) return validComponent(value);
        return number(key, value) != null;
    }

    /** Compares valid settings numerically while preserving their original strings elsewhere. */
    public static boolean equivalent(Key key, String first, String second) {
        if (key != null && key.kind == Kind.COMPONENT) {
            return validComponent(first) && validComponent(second) && first.equals(second);
        }
        BigDecimal a = number(key, first);
        BigDecimal b = number(key, second);
        return a != null && b != null && a.compareTo(b) == 0;
    }

    private static boolean validComponent(String value) {
        if (value == null || value.length() > 512) return false;
        if (value.isEmpty()) return true;
        return value.matches("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*/"
                + "\\.?[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*");
    }

    private static BigDecimal number(Key key, String value) {
        if (key == null || value == null || value.isEmpty() || value.length() > 64) return null;
        try {
            if (key.kind == Kind.SCALE) {
                // WindowManager constrains animation scales to the range 0 through 20.
                if (!value.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")) {
                    return null;
                }
                BigDecimal number = new BigDecimal(value);
                return number.signum() >= 0 && number.compareTo(BigDecimal.valueOf(20)) <= 0
                        ? number : null;
            }
            // These settings are read by Android with getInt, not getFloat.
            if (!value.matches("[+-]?[0-9]+")) return null;
            int number = Integer.parseInt(value);
            int minimum = key.kind == Kind.SLEEP_TIMEOUT ? -1 : 0;
            int maximum = key.kind == Kind.BOOLEAN ? 1 : key.kind == Kind.LOCATION_MODE ? 3
                    : key.kind == Kind.POWER_MASK ? 15 : Integer.MAX_VALUE;
            return number >= minimum && number <= maximum ? BigDecimal.valueOf(number) : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
