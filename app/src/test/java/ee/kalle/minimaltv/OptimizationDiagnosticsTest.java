package ee.kalle.minimaltv;

import org.junit.Test;
import static org.junit.Assert.*;

public class OptimizationDiagnosticsTest {
    private static final String SAMPLE = "POWER MANAGER\n  mWakefulness=Awake\n  mDreamsEnabledSetting=false\n"
            + "  mScreenOffTimeoutSetting=600000\n  mSleepTimeoutSetting=600000\n";
    @Test public void readsVerifiedRuntimeFields() {
        OptimizationDiagnostics.PowerState state = OptimizationDiagnostics.parsePower(SAMPLE);
        assertNotNull(state);
        assertFalse(state.screensaverEnabled);
        assertEquals(600000, state.screenTimeout);
        assertEquals(600000, state.sleepTimeout);
    }
    @Test public void acceptsDisabledTimeoutButRejectsMalformedOrMissingFields() {
        assertEquals(-1, OptimizationDiagnostics.parsePower(SAMPLE.replace("mSleepTimeoutSetting=600000", "mSleepTimeoutSetting=-1")).sleepTimeout);
        assertNull(OptimizationDiagnostics.parsePower(SAMPLE.replace("mSleepTimeoutSetting=600000", "mSleepTimeoutSetting=unknown")));
        assertNull(OptimizationDiagnostics.parsePower(SAMPLE.replace("mDreamsEnabledSetting=false\n", "")));
        assertNull(OptimizationDiagnostics.parsePower(SAMPLE.replace("mSleepTimeoutSetting=600000", "mSleepTimeoutSetting=-2")));
    }
    @Test public void deniedAmbiguousAndOversizedOutputNeverBecomesASuccess() {
        assertNull(OptimizationDiagnostics.parsePower("Permission Denial: cannot dump PowerManager"));
        assertNull(OptimizationDiagnostics.parsePower(SAMPLE + "mDreamsEnabledSetting=true\n"));
        assertNull(OptimizationDiagnostics.parsePower(new String(new char[131073])));
        assertNull(OptimizationDiagnostics.parsePower(null));
    }
}
