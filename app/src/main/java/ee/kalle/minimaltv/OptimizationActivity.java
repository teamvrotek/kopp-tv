package ee.kalle.minimaltv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewParent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Shows a scan before explicit changes and lets active transactions finish off-screen. */
public final class OptimizationActivity extends Activity {
    private static final String TAG = "KOPP_TV_OPTIMIZER";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private AndroidOptimizer optimizer;
    private AndroidOptimizer.Report report;
    private Button scanButton;
    private Button optimizeButton;
    private Button restoreButton;
    private Button backButton;
    private Button lastAction;
    private TextView status;
    private TextView permissionHint;
    private TextView device;
    private ScrollView reportScroll;
    private LinearLayout reportRows;
    private boolean busy;
    private boolean resumed;
    private boolean rescanOnResume = true;
    private volatile boolean destroyed;

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        optimizer = new AndroidOptimizer(this);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(32), dp(20), dp(32), dp(20));
        root.setBackgroundColor(Color.rgb(18, 20, 23));
        root.addView(text(getString(R.string.optimization_title), 25));
        TextView explanation = text(getString(R.string.optimization_description), 15);
        explanation.setPadding(0, dp(6), 0, dp(10));
        root.addView(explanation);

        LinearLayout actions = new LinearLayout(this);
        scanButton = button(R.string.optimization_scan);
        optimizeButton = button(R.string.optimization_optimize);
        restoreButton = button(R.string.optimization_restore);
        backButton = button(R.string.optimization_back);
        for (Button action : new Button[] {scanButton, optimizeButton, restoreButton, backButton}) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(60), 1);
            if (actions.getChildCount() > 0) params.leftMargin = dp(10);
            actions.addView(action, params);
            action.setOnFocusChangeListener((view, focused) -> {
                if (focused) lastAction = (Button) view;
            });
            action.setOnKeyListener((view, key, event) -> {
                if (key != KeyEvent.KEYCODE_DPAD_DOWN) return false;
                if (event.getAction() == KeyEvent.ACTION_DOWN) reportScroll.requestFocus();
                return true;
            });
        }
        root.addView(actions);
        scanButton.setOnClickListener(view -> scan());
        optimizeButton.setOnClickListener(view -> change(false));
        restoreButton.setOnClickListener(view -> change(true));
        backButton.setOnClickListener(view -> finish());

        status = text("", 15);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setPadding(0, dp(10), 0, dp(6));
        root.addView(status);
        permissionHint = text(getString(R.string.optimization_access_hint), 14);
        permissionHint.setTextColor(Color.rgb(188, 196, 205));
        permissionHint.setPadding(0, 0, 0, dp(8));
        permissionHint.setVisibility(View.GONE);
        root.addView(permissionHint);
        device = text("", 15);
        device.setPadding(0, 0, 0, dp(4));
        root.addView(device);
        TextView navigation = text(getString(R.string.optimization_navigation), 13);
        navigation.setTextColor(Color.rgb(171, 181, 191));
        navigation.setPadding(0, 0, 0, dp(6));
        root.addView(navigation);

        reportScroll = new ScrollView(this);
        reportScroll.setId(View.generateViewId());
        reportScroll.setFocusable(true);
        reportScroll.setDescendantFocusability(ScrollView.FOCUS_BEFORE_DESCENDANTS);
        reportScroll.setFillViewport(true);
        reportRows = new LinearLayout(this);
        reportRows.setOrientation(LinearLayout.VERTICAL);
        reportRows.setPadding(0, dp(4), dp(8), dp(8));
        reportScroll.addView(reportRows, new ScrollView.LayoutParams(-1, -2));
        root.addView(reportScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        updateButtons();
        scanButton.requestFocus();
    }

    private void scan() {
        if (busy || destroyed) return;
        busy = true;
        rescanOnResume = false;
        setStatus(getString(R.string.optimization_scanning), false);
        updateButtons();
        executor.execute(() -> {
            AndroidOptimizer.Report scanned = null;
            String message = getString(R.string.optimization_scan_complete);
            boolean failed = false;
            try {
                scanned = optimizer.scan();
            } catch (Exception error) {
                Log.w(TAG, "Optimisation scan failed", error);
                message = getString(R.string.optimization_scan_failed);
                failed = true;
            }
            deliver(scanned, message, failed);
        });
    }

    private void change(boolean restore) {
        if (busy || destroyed || report == null || !report.accessGranted) return;
        if (restore ? !report.canRestore : report.recoveryPending || report.changeCount <= 0) return;
        final AndroidOptimizer.Report preview = report;
        busy = true;
        rescanOnResume = false;
        setStatus(getString(restore ? R.string.optimization_restoring : R.string.optimization_optimizing), false);
        updateButtons();
        executor.execute(() -> {
            String message;
            boolean failed;
            try {
                AndroidOptimizer.Outcome outcome = restore ? optimizer.restore() : optimizer.optimize(preview);
                failed = !outcome.success;
                message = outcome.message;
                if (message == null || message.trim().isEmpty()) {
                    message = getString(failed ? R.string.optimization_change_failed
                            : restore ? R.string.optimization_restored : R.string.optimization_optimized);
                }
            } catch (Exception error) {
                Log.w(TAG, restore ? "Optimisation restore failed" : "Optimisation change failed", error);
                message = getString(R.string.optimization_change_failed);
                failed = true;
            }
            AndroidOptimizer.Report refreshed = null;
            try {
                refreshed = optimizer.scan();
            } catch (Exception error) {
                Log.w(TAG, "Unable to refresh optimisation report", error);
                message += "\n" + getString(R.string.optimization_scan_failed);
                failed = true;
            }
            deliver(refreshed, message, failed);
        });
    }

    private void deliver(AndroidOptimizer.Report scanned, String message, boolean failed) {
        if (destroyed) return;
        handler.post(() -> {
            if (destroyed) return;
            busy = false;
            report = scanned;
            renderReport();
            setStatus(message, failed);
            updateButtons();
            if (resumed && rescanOnResume) scan();
        });
    }

    private void renderReport() {
        reportRows.removeAllViews();
        permissionHint.setVisibility(report != null && !report.accessGranted ? View.VISIBLE : View.GONE);
        device.setText(report == null ? "" : getString(R.string.optimization_device, report.device));
        if (report == null) {
            reportRows.addView(text(getString(R.string.optimization_no_report), 16));
            return;
        }
        for (AndroidOptimizer.Row row : report.rows) {
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(dp(14), dp(12), dp(14), dp(12));
            item.setBackgroundColor(Color.rgb(27, 31, 36));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.bottomMargin = dp(8);
            reportRows.addView(item, params);
            TextView title = text(row.title, 18);
            title.setTypeface(null, Typeface.BOLD);
            if (row.warning) title.setTextColor(Color.rgb(255, 206, 130));
            item.addView(title);
            TextView detail = text(row.detail, 15);
            detail.setPadding(0, dp(5), 0, 0);
            item.addView(detail);
            if (row.packageName != null) {
                Button details = button(R.string.optimization_app_details);
                LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(-2, dp(48));
                detailParams.topMargin = dp(8);
                item.addView(details, detailParams);
                details.setOnClickListener(view -> openAppDetails(row.packageName));
            }
            if (row.settingsAction != null) {
                Button settings = button(R.string.optimization_open_settings);
                LinearLayout.LayoutParams settingsParams = new LinearLayout.LayoutParams(-2, dp(48));
                settingsParams.topMargin = dp(8);
                item.addView(settings, settingsParams);
                settings.setOnClickListener(view -> openSettings(row.settingsAction));
            }
        }
    }

    private void openAppDetails(String packageName) {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)));
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to open Android app settings", error);
            setStatus(getString(R.string.optimization_app_details_failed), true);
        }
    }

    private void openSettings(String action) {
        try {
            startActivity(new Intent(action));
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to open Android settings action", error);
            setStatus(getString(R.string.optimization_settings_unavailable), true);
        }
    }

    private void updateButtons() {
        scanButton.setEnabled(!busy);
        boolean access = report != null && report.accessGranted;
        optimizeButton.setEnabled(!busy && access && !report.recoveryPending && report.changeCount > 0);
        restoreButton.setEnabled(!busy && access && report.canRestore);
        optimizeButton.setText(report == null ? getString(R.string.optimization_optimize)
                : getResources().getQuantityString(R.plurals.optimization_optimize_changes,
                        Math.max(0, report.changeCount), Math.max(0, report.changeCount)));
        View focused = getCurrentFocus();
        if (focused instanceof Button && !focused.isEnabled()) backButton.requestFocus();
    }

    private void setStatus(String message, boolean warning) {
        status.setText(message);
        status.setTextColor(warning ? Color.rgb(255, 206, 130) : Color.rgb(242, 246, 249));
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT && insideReport(getCurrentFocus())) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                Button target = lastAction != null && lastAction.isEnabled() ? lastAction
                        : scanButton.isEnabled() ? scanButton : backButton;
                target.requestFocus();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private boolean insideReport(View view) {
        while (view != null) {
            if (view == reportScroll) return true;
            ViewParent parent = view.getParent();
            view = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    private TextView text(String value, int size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(Color.rgb(242, 246, 249));
        return view;
    }

    private Button button(int label) {
        Button view = new Button(this);
        view.setId(View.generateViewId());
        view.setText(label);
        view.setAllCaps(false);
        view.setTextSize(14);
        view.setPadding(dp(8), 0, dp(8), 0);
        view.setFocusable(true);
        view.setFocusableInTouchMode(false);
        view.setStateListAnimator(null);
        view.setTextColor(new ColorStateList(new int[][] {
                new int[] {-android.R.attr.state_enabled}, new int[] {}
        }, new int[] {Color.rgb(115, 126, 135), Color.rgb(242, 246, 249)}));
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[] {android.R.attr.state_focused}, buttonBackground(true));
        background.addState(new int[] {android.R.attr.state_pressed}, buttonBackground(true));
        background.addState(new int[] {}, buttonBackground(false));
        view.setBackground(background);
        return view;
    }

    private GradientDrawable buttonBackground(boolean focused) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(focused ? Color.rgb(43, 61, 75) : Color.rgb(31, 37, 44));
        shape.setCornerRadius(dp(5));
        if (focused) shape.setStroke(dp(2), Color.rgb(242, 246, 249));
        return shape;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (!busy && rescanOnResume) scan();
    }

    @Override protected void onPause() {
        resumed = false;
        rescanOnResume = true;
        super.onPause();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        // A submitted transaction must finish; shutdown does not interrupt its worker.
        executor.shutdown();
        super.onDestroy();
    }
}
