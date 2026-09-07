package ee.kalle.minimaltv;

import android.content.Intent;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import java.time.Duration;
import java.util.ArrayList;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class HomeServiceTest {
    public static class RecordingService extends HomeService {
        final ArrayList<Intent> opened = new ArrayList<>();
        @Override public void startActivity(Intent intent) { opened.add(intent); }
    }
    private RecordingService service;
    @Before public void setUp() {
        HomeService.endGoogleHomeVisit();
        service = Robolectric.buildService(RecordingService.class).create().get();
    }
    @After public void clearVisit() { HomeService.endGoogleHomeVisit(); }

    private AccessibilityEvent window(String packageName, String activity) {
        AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        event.setPackageName(packageName);
        event.setClassName(activity);
        return event;
    }

    private void googleHome() {
        service.onAccessibilityEvent(window("com.google.android.apps.tv.launcherx",
                "com.google.android.apps.tv.launcherx.home.HomeActivity"));
    }

    @Test public void ordinaryGoogleHomeReturnsToLauncherAndOtherWindowsDoNot() {
        service.onAccessibilityEvent(window("com.google.android.apps.tv.launcherx", "SearchActivity"));
        service.onAccessibilityEvent(window("streaming.app", "PlaybackActivity"));
        assertTrue(service.opened.isEmpty());
        googleHome();
        assertEquals(1, service.opened.size());
        assertEquals(MainActivity.class.getName(), service.opened.get(0).getComponent().getClassName());
        assertTrue((service.opened.get(0).getFlags() & Intent.FLAG_ACTIVITY_CLEAR_TOP) != 0);
        googleHome();
        assertEquals(1, service.opened.size());
    }

    @Test public void physicalHomeEndsIntentionalGoogleVisitAndBypassesAutomaticDebounce() {
        googleHome();
        HomeService.beginGoogleHomeVisit();
        googleHome();
        assertEquals(1, service.opened.size());
        assertTrue(service.onKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HOME)));
        assertEquals(2, service.opened.size());
        assertTrue(service.onKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HOME)));
        assertEquals(2, service.opened.size());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(301));
        googleHome();
        assertEquals(3, service.opened.size());
    }

    @Test public void otherRemoteKeysAreNotIntercepted() {
        assertFalse(service.onKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)));
        assertTrue(service.opened.isEmpty());
    }
}
