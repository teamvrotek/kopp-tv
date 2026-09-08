package ee.kalle.minimaltv;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;

import static ee.kalle.minimaltv.OptimizationPolicy.Key.*;
import static org.junit.Assert.*;

public class OptimizationPolicyTest {
    @Test public void missingValuesNeverCreateSettings() {
        LinkedHashMap<OptimizationPolicy.Key, String> observed = new LinkedHashMap<>();
        observed.put(SLEEP_TIMEOUT, null);
        observed.put(null, "1");
        assertTrue(OptimizationPolicy.plan(observed).isEmpty());
        assertTrue(OptimizationPolicy.plan(null).isEmpty());
    }

    @Test public void numericAnimationEqualityDoesNotWriteFormattingChanges() {
        LinkedHashMap<OptimizationPolicy.Key, String> observed = new LinkedHashMap<>();
        observed.put(WINDOW_ANIMATION_SCALE, "0.0");
        observed.put(TRANSITION_ANIMATION_SCALE, "-0.00");
        observed.put(ANIMATOR_DURATION_SCALE, "0e3");
        observed.put(SCREEN_OFF_TIMEOUT, "+0600000");
        assertTrue(OptimizationPolicy.plan(observed).isEmpty());
        assertTrue(OptimizationPolicy.equivalent(WINDOW_ANIMATION_SCALE, "1.00", "1e0"));
    }

    @Test public void planIsAnOrderedAllowlistAndDoesNotMutateObservations() {
        LinkedHashMap<OptimizationPolicy.Key, String> observed = new LinkedHashMap<>();
        observed.put(SLEEP_TIMEOUT, "-1");
        observed.put(SCREEN_OFF_TIMEOUT, "300000");
        observed.put(WINDOW_ANIMATION_SCALE, "1.00");
        LinkedHashMap<OptimizationPolicy.Key, String> plan = OptimizationPolicy.plan(observed);
        assertEquals(Arrays.asList(WINDOW_ANIMATION_SCALE, SCREEN_OFF_TIMEOUT, SLEEP_TIMEOUT),
                new ArrayList<>(plan.keySet()));
        assertEquals("0", plan.get(WINDOW_ANIMATION_SCALE));
        assertEquals("600000", plan.get(SLEEP_TIMEOUT));
        assertEquals("1.00", observed.get(WINDOW_ANIMATION_SCALE));
        plan.clear();
        assertEquals(3, observed.size());
    }

    @Test public void invalidAnimationValuesAreSkipped() {
        for (String value : new String[] { "", " ", " 1", "1 ", "NaN", "Infinity", "-1",
                "20.01", "0x1.0p0", "null", "1f", "1e999999999999999999999" }) {
            assertFalse(value, OptimizationPolicy.isSupportedValue(WINDOW_ANIMATION_SCALE, value));
        }
        assertTrue(OptimizationPolicy.isSupportedValue(WINDOW_ANIMATION_SCALE, "0.5"));
        assertTrue(OptimizationPolicy.isSupportedValue(WINDOW_ANIMATION_SCALE, "20"));
        assertFalse(OptimizationPolicy.equivalent(WINDOW_ANIMATION_SCALE, null, null));
    }

    @Test public void integerSettingsMustBeReadableByAndroidGetInt() {
        for (String value : new String[] { "1.0", "1e0", "1 ", "2147483648", "-2147483649", "NaN" }) {
            assertFalse(value, OptimizationPolicy.isSupportedValue(SCREEN_OFF_TIMEOUT, value));
        }
        assertTrue(OptimizationPolicy.isSupportedValue(SCREEN_OFF_TIMEOUT, "2147483647"));
        assertTrue(OptimizationPolicy.isSupportedValue(SCREEN_OFF_TIMEOUT, "0"));
        assertFalse(OptimizationPolicy.isSupportedValue(SCREEN_OFF_TIMEOUT, "-1"));
        assertTrue(OptimizationPolicy.isSupportedValue(SLEEP_TIMEOUT, "-1"));
        assertFalse(OptimizationPolicy.isSupportedValue(SLEEP_TIMEOUT, "-2"));
    }

    @Test public void booleansAndPowerMasksHaveExplicitBounds() {
        for (OptimizationPolicy.Key key : Arrays.asList(SCREENSAVER_ENABLED,
                SCREENSAVER_ACTIVATE_ON_SLEEP, SCREENSAVER_ACTIVATE_ON_DOCK,
                WIFI_SCAN_ALWAYS_ENABLED, BLE_SCAN_ALWAYS_ENABLED)) {
            assertTrue(OptimizationPolicy.isSupportedValue(key, "0"));
            assertTrue(OptimizationPolicy.isSupportedValue(key, "1"));
            assertFalse(OptimizationPolicy.isSupportedValue(key, "2"));
            assertFalse(OptimizationPolicy.isSupportedValue(key, "-1"));
        }
        assertTrue(OptimizationPolicy.isSupportedValue(STAY_ON_WHILE_PLUGGED_IN, "15"));
        assertFalse(OptimizationPolicy.isSupportedValue(STAY_ON_WHILE_PLUGGED_IN, "16"));
    }

    @Test public void privacyControlsRequirePresentValidValues() {
        assertTrue(OptimizationPolicy.isSupportedValue(LOCATION_MODE, "0"));
        assertTrue(OptimizationPolicy.isSupportedValue(LOCATION_MODE, "3"));
        assertFalse(OptimizationPolicy.isSupportedValue(LOCATION_MODE, "4"));
        assertFalse(OptimizationPolicy.isSupportedValue(LOCATION_MODE, "-1"));
        LinkedHashMap<OptimizationPolicy.Key, String> observed = new LinkedHashMap<>();
        observed.put(LOCATION_MODE, "3");
        observed.put(WIFI_SCAN_ALWAYS_ENABLED, "1");
        observed.put(BLE_SCAN_ALWAYS_ENABLED, null);
        LinkedHashMap<OptimizationPolicy.Key, String> plan = OptimizationPolicy.plan(observed);
        assertEquals(Arrays.asList(LOCATION_MODE, WIFI_SCAN_ALWAYS_ENABLED), new ArrayList<>(plan.keySet()));
        assertEquals("0", plan.get(LOCATION_MODE));
    }

    @Test public void everyDeclaredTargetIsSupportedAndQualified() {
        assertEquals(14, OptimizationPolicy.Key.values().length);
        for (OptimizationPolicy.Key key : OptimizationPolicy.Key.values()) {
            assertTrue(key.name, OptimizationPolicy.isSupportedValue(key, key.target));
            assertEquals(key.namespace + "/" + key.name, key.qualified());
        }
        assertEquals("secure/sleep_timeout", SLEEP_TIMEOUT.qualified());
    }

    @Test public void assistantComponentsAcceptOnlyBoundedExplicitComponentsOrEmpty() {
        for (OptimizationPolicy.Key key : Arrays.asList(ASSISTANT, VOICE_INTERACTION_SERVICE)) {
            for (String component : new String[] { "", "com.example/.Assistant", "com.example/com.example.Assistant",
                    "android/com.android.server.Voice$Service" }) {
                assertTrue(component, OptimizationPolicy.isSupportedValue(key, component));
            }
            for (String invalid : new String[] { "null", "com.example", "/.Assistant", "com.example/",
                    "com.example/.Bad name", " com.example/.Assistant", "com.example//Assistant",
                    "com.example/.Assistant;other", "com.example/.Assistant\n" }) {
                assertFalse(invalid, OptimizationPolicy.isSupportedValue(key, invalid));
            }
            assertFalse(OptimizationPolicy.isSupportedValue(key, null));
            assertFalse(OptimizationPolicy.isSupportedValue(key,
                    "com.example/" + new String(new char[513]).replace('\0', 'A')));
            assertTrue(OptimizationPolicy.equivalent(key, "", ""));
            assertFalse(OptimizationPolicy.equivalent(key, "com.example/.Assistant", "com.example/com.example.Assistant"));
        }
        LinkedHashMap<OptimizationPolicy.Key, String> observed = new LinkedHashMap<>();
        observed.put(ASSISTANT, "com.example/.Assistant");
        observed.put(VOICE_INTERACTION_SERVICE, "");
        assertEquals("", OptimizationPolicy.plan(observed).get(ASSISTANT));
        assertFalse(OptimizationPolicy.plan(observed).containsKey(VOICE_INTERACTION_SERVICE));
    }
}
