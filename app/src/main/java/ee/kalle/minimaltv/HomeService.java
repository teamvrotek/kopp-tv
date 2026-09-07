package ee.kalle.minimaltv;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

public class HomeService extends AccessibilityService {
    private static volatile boolean visitingGoogleHome;
    private long lastLaunch = -1;

    static void beginGoogleHomeVisit() { visitingGoogleHome = true; }
    static void endGoogleHomeVisit() { visitingGoogleHome = false; }

    private void openHome() {
        openHome(false);
    }

    private void openHome(boolean explicitKey) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (!explicitKey && lastLaunch >= 0 && now - lastLaunch < 300) return;
        lastLaunch = now;
        try {
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
        } catch (RuntimeException error) {
            Log.e("MINIMAL_TV", "Unable to open Home", error);
        }
    }

    @Override protected void onServiceConnected() {
        int boot = Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        android.content.SharedPreferences prefs = getSharedPreferences("home", MODE_PRIVATE);
        if (boot >= 0 && boot != prefs.getInt("last_boot", -2)) {
            openHome();
            prefs.edit().putInt("last_boot", boot).apply();
        }
        Log.i("MINIMAL_TV", "Home key service connected");
    }

    @Override protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_HOME) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            endGoogleHomeVisit();
            openHome(true);
        }
        return true;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        // Cover Home launches that bypass key filtering, including injected keys.
        // Match only its actual Home activity, preserving search and settings windows.
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && "com.google.android.apps.tv.launcherx".contentEquals(event.getPackageName() == null ? "" : event.getPackageName())
                && "com.google.android.apps.tv.launcherx.home.HomeActivity".contentEquals(event.getClassName() == null ? "" : event.getClassName())) {
            if (visitingGoogleHome) return;
            Log.i("MINIMAL_TV", "Google Home redirected");
            openHome();
        }
    }

    @Override public void onInterrupt() { }
}
