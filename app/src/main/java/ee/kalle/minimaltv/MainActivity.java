package ee.kalle.minimaltv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public class MainActivity extends Activity {
    private static final int GAP_DP = 5;
    private final ArrayList<AppCard> cards = new ArrayList<>();
    private final ArrayList<AppCatalog.Entry> apps = new ArrayList<>();
    private SharedPreferences prefs;
    private AppPreferences appPreferences;
    private WallpaperController wallpapers;
    private WeatherClock weatherClock;
    private FrameLayout root;
    private LinearLayout row;
    private HomeScrollView scroller;
    private LinearLayout emptyState;
    private Button manageButton;
    private AlertDialog menu;
    private AlertDialog about;
    private HomeLayout geometry;
    private int selected;
    private int firstVisible;
    private String settingsSignature;
    private boolean active;
    private boolean setupOffered;
    private boolean appsLoaded;

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override protected void attachBaseContext(Context context) {
        super.attachBaseContext(LauncherLocale.wrap(context));
    }

    private String currentSettingsSignature() {
        return new UserSettings(this).signature() + "|" + Locale.getDefault().toLanguageTag()
            + "|" + TimeZone.getDefault().getID() + "|" + android.text.format.DateFormat.is24HourFormat(this);
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        settingsSignature = currentSettingsSignature();
        setupOffered = state != null && state.getBoolean("setup_offered");
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        appPreferences = new AppPreferences(this);
        appPreferences.migrateLegacyIfNeeded();
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        FrameLayout backdrop = new FrameLayout(this);
        root.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
        wallpapers = new WallpaperController(this, backdrop);
        weatherClock = new WeatherClock(this);
        FrameLayout.LayoutParams clockParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        clockParams.setMargins(dp(32), dp(24), dp(32), 0);
        root.addView(weatherClock, clockParams);

        scroller = new HomeScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroller.setFocusable(false);
        scroller.setClipToPadding(true);
        row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setClipChildren(false);
        row.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> revealSelected(false));
        scroller.addView(row, new FrameLayout.LayoutParams(-2, -1));
        FrameLayout.LayoutParams rowParams = new FrameLayout.LayoutParams(-1, dp(82), Gravity.BOTTOM);
        rowParams.setMargins(dp(32), 0, dp(32), dp(32));
        root.addView(scroller, rowParams);

        emptyState = new LinearLayout(this);
        emptyState.setOrientation(LinearLayout.VERTICAL);
        emptyState.setGravity(Gravity.CENTER);
        TextView emptyText = new TextView(this);
        emptyText.setText(R.string.home_no_apps);
        emptyText.setTextColor(Color.WHITE);
        emptyText.setTextSize(18);
        emptyText.setShadowLayer(3, 0, 1, Color.BLACK);
        emptyState.addView(emptyText);
        manageButton = new Button(this);
        manageButton.setText(R.string.home_manage_apps);
        manageButton.setOnClickListener(view -> openManageApps());
        emptyState.addView(manageButton);
        FrameLayout.LayoutParams emptyParams = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        emptyParams.setMargins(dp(32), 0, dp(32), dp(32));
        root.addView(emptyState, emptyParams);
        root.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft || geometry == null) updateGeometry();
        });
        setContentView(root);
    }

    private void reloadApps() {
        List<String> selectedPackages = appPreferences.selectedPackages();
        String desired = prefs.getString("selected_package", "");
        if (desired.isEmpty() && prefs.contains("selected") && !selectedPackages.isEmpty()) {
            desired = selectedPackages.get(Math.max(0, Math.min(selectedPackages.size() - 1, prefs.getInt("selected", 0))));
        }
        List<AppCatalog.Entry> installed = AppCatalog.discover(this);
        ArrayList<AppCatalog.Entry> available = new ArrayList<>();
        for (String packageName : selectedPackages) {
            for (AppCatalog.Entry entry : installed) {
                if (entry.packageName.equals(packageName)) { available.add(entry); break; }
            }
        }
        boolean same = appsLoaded && available.size() == apps.size();
        for (int index = 0; same && index < apps.size(); index++) {
            same = apps.get(index).packageName.equals(available.get(index).packageName)
                && apps.get(index).label.equals(available.get(index).label);
        }
        if (same) { focusSelected(); revealSelected(false); return; }
        appsLoaded = true;
        apps.clear();
        apps.addAll(available);
        row.removeAllViews();
        cards.clear();
        selected = 0;
        firstVisible = 0;
        for (int index = 0; index < apps.size(); index++) {
            final int position = index;
            AppCard card = new AppCard(this, index);
            card.setId(100 + index);
            card.setContentDescription(apps.get(index).label);
            card.setFocusable(true);
            card.setFocusableInTouchMode(false);
            card.setRevealOnFocusHint(false);
            card.setClickable(true);
            card.setOnClickListener(view -> launch(position));
            card.setOnFocusChangeListener((view, focused) -> {
                if (focused) {
                    selected = position;
                    prefs.edit().putString("selected_package", apps.get(position).packageName).apply();
                    revealSelected(true);
                }
                view.invalidate();
            });
            card.setOnKeyListener((view, key, event) -> {
                if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN) {
                        int next = Math.max(0, Math.min(cards.size() - 1,
                            position + (key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1)));
                        cards.get(next).requestFocus();
                    }
                    return true;
                }
                if (key == KeyEvent.KEYCODE_DPAD_DOWN) return true;
                if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
                    if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) card.performClick();
                    return true;
                }
                return false;
            });
            cards.add(card);
            row.addView(card, new LinearLayout.LayoutParams(dp(145), -1));
            if (apps.get(index).packageName.equals(desired)) selected = index;
        }
        emptyState.setVisibility(cards.isEmpty() ? View.VISIBLE : View.GONE);
        scroller.setVisibility(cards.isEmpty() ? View.GONE : View.VISIBLE);
        updateGeometry();
        final int restoreIndex = selected;
        row.post(() -> {
            if (!active || isFinishing()) return;
            if (cards.isEmpty()) manageButton.requestFocus();
            else {
                selected = Math.min(restoreIndex, cards.size() - 1);
                cards.get(selected).requestFocus();
                revealSelected(false);
            }
        });
    }

    private void updateGeometry() {
        int viewport = root.getWidth() - dp(64);
        if (viewport <= 0) return;
        geometry = new HomeLayout(viewport, dp(GAP_DP), dp(6), cards.size());
        scroller.getLayoutParams().height = geometry.frameHeight;
        row.getLayoutParams().width = Math.max(viewport, geometry.contentWidth());
        for (int index = 0; index < cards.size(); index++) {
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) cards.get(index).getLayoutParams();
            params.width = geometry.frameWidth(index);
            params.rightMargin = index + 1 < cards.size() ? geometry.gap : 0;
            cards.get(index).setLayoutParams(params);
        }
        scroller.requestLayout();
        revealSelected(false);
    }

    private void revealSelected(boolean smooth) {
        if (geometry == null || cards.isEmpty()) return;
        firstVisible = geometry.firstVisible(selected, firstVisible);
        int offset = cards.size() > HomeLayout.VISIBLE_APPS ? geometry.offset(firstVisible) : 0;
        if (smooth) scroller.smoothScrollTo(offset, 0);
        else scroller.scrollTo(offset, 0);
        for (int index = firstVisible; index < Math.min(cards.size(), firstVisible + HomeLayout.VISIBLE_APPS); index++) {
            cards.get(index).refreshImage();
        }
    }

    private void focusSelected() {
        if (cards.isEmpty()) manageButton.requestFocus();
        else cards.get(Math.max(0, Math.min(cards.size() - 1, selected))).requestFocus();
    }

    private void launch(int index) {
        if (!active || index < 0 || index >= apps.size()) return;
        AppCatalog.Entry app = apps.get(index);
        try {
            Intent intent = getPackageManager().getLeanbackLaunchIntentForPackage(app.packageName);
            if (intent == null) throw new IllegalStateException("No TV launch activity");
            startActivity(intent);
        } catch (RuntimeException error) {
            Log.e("MINIMAL_TV", "App unavailable " + app.packageName, error);
            Toast.makeText(this, getString(R.string.home_app_unavailable, app.label), Toast.LENGTH_LONG).show();
        }
    }

    private void openManageApps() { startActivity(new Intent(this, ManageAppsActivity.class)); }

    private void showMenu() {
        if (menu != null && menu.isShowing()) return;
        menu = new AlertDialog.Builder(this).setItems(new String[] {
            getString(R.string.home_next_background), getString(R.string.home_manage_apps),
            getString(R.string.home_google_home), getString(R.string.home_android_settings),
            getString(R.string.home_about), getString(R.string.home_settings)
        }, (dialog, which) -> {
            if (which == 0) wallpapers.next();
            else if (which == 1) openManageApps();
            else if (which == 2) openGoogleHome();
            else if (which == 3) startActivity(new Intent(Settings.ACTION_SETTINGS));
            else if (which == 4) {
                String attribution = android.text.TextUtils.htmlEncode(getString(R.string.home_weather_credit));
                about = new AlertDialog.Builder(this).setTitle(R.string.app_name)
                    .setMessage(android.text.Html.fromHtml(getString(R.string.home_copyright)
                        + "<br><br>" + attribution + " <a href=\"https://open-meteo.com/\">Open-Meteo</a>.", android.text.Html.FROM_HTML_MODE_LEGACY))
                    .setPositiveButton(R.string.home_close, null).show();
                TextView message = about.findViewById(android.R.id.message);
                if (message != null) message.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
            } else if (which == 5) startActivity(new Intent(this, SettingsActivity.class));
        }).create();
        menu.setOnDismissListener(dialog -> focusSelected());
        menu.show();
    }

    private void openGoogleHome() {
        HomeService.beginGoogleHomeVisit();
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_HOME);
            intent.setComponent(new ComponentName("com.google.android.apps.tv.launcherx",
                "com.google.android.apps.tv.launcherx.home.HomeActivity"));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(intent);
        } catch (RuntimeException error) {
            HomeService.endGoogleHomeVisit();
            Log.e("MINIMAL_TV", "Unable to open Google TV Home", error);
            Toast.makeText(this, R.string.home_google_unavailable, Toast.LENGTH_LONG).show();
        }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if (key == KeyEvent.KEYCODE_MENU || key == KeyEvent.KEYCODE_DPAD_UP) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) showMenu();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        HomeService.endGoogleHomeVisit();
        setIntent(intent);
        if (menu != null) menu.dismiss();
        if (about != null) about.dismiss();
        focusSelected();
    }

    @Override protected void onResume() {
        super.onResume();
        HomeService.endGoogleHomeVisit();
        if (!settingsSignature.equals(currentSettingsSignature())) { recreate(); return; }
        active = true;
        reloadApps();
        wallpapers.resume();
        weatherClock.resume();
        if (!appPreferences.isConfigured() && !setupOffered) {
            setupOffered = true;
            root.post(() -> { if (active && !isFinishing()) openManageApps(); });
        }
    }

    @Override protected void onPause() {
        active = false;
        wallpapers.pause();
        weatherClock.pause();
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("setup_offered", setupOffered);
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        wallpapers.destroy();
        weatherClock.destroy();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (menu != null && menu.isShowing()) menu.dismiss();
        else focusSelected();
    }

    private static final class HomeScrollView extends HorizontalScrollView {
        HomeScrollView(Context context) { super(context); }
        @Override public boolean requestChildRectangleOnScreen(View child, Rect rectangle, boolean immediate) {
            return false;
        }
    }

    private final class AppCard extends View {
        private final int index;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF thumbnail = new RectF();
        private final RectF ring = new RectF();
        private final Path clip = new Path();
        private float thumbnailRadius;
        private float ringRadius;
        private float ringStroke;
        private float ringContrastStroke;
        private Drawable artwork;
        private boolean isBanner;

        AppCard(Context context, int position) { super(context); index = position; }

        void refreshImage() {
            if (artwork != null) return;
            PackageManager pm = getPackageManager();
            try {
                Intent intent = pm.getLeanbackLaunchIntentForPackage(apps.get(index).packageName);
                if (intent != null && intent.getComponent() != null) {
                    ActivityInfo activity = pm.getActivityInfo(intent.getComponent(), 0);
                    artwork = activity.loadBanner(pm);
                    Log.i("MINIMAL_TV", apps.get(index).packageName + " TV banner=" + activity.banner + " appBanner=" + activity.applicationInfo.banner);
                }
                if (artwork == null) artwork = pm.getApplicationBanner(apps.get(index).packageName);
                isBanner = artwork != null;
                if (artwork == null) artwork = pm.getApplicationIcon(apps.get(index).packageName);
            } catch (Exception error) {
                Log.w("MINIMAL_TV", "Artwork unavailable: " + apps.get(index).packageName, error);
            }
            invalidate();
        }

        @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
            float density = getResources().getDisplayMetrics().density;
            float thumbnailInset = 6f * density;
            float clearGap = 3f * density;
            thumbnailRadius = 8f * density;
            ringStroke = 1.5f * density;
            ringContrastStroke = 3f * density;
            thumbnail.set(thumbnailInset, thumbnailInset, w - thumbnailInset, h - thumbnailInset);
            // Offset the stroke center and corner radius together to keep concentric arcs.
            float outwardOffset = clearGap + ringStroke / 2f;
            ring.set(thumbnail);
            ring.inset(-outwardOffset, -outwardOffset);
            ringRadius = thumbnailRadius + outwardOffset;
            clip.reset();
            clip.addRoundRect(thumbnail, thumbnailRadius, thumbnailRadius, Path.Direction.CW);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.save();
            canvas.clipPath(clip);
            canvas.drawColor(Color.rgb(12, 12, 14));
            if (artwork != null) {
                if (isBanner) artwork.setBounds(Math.round(thumbnail.left), Math.round(thumbnail.top), Math.round(thumbnail.right), Math.round(thumbnail.bottom));
                else {
                    float intrinsicWidth = Math.max(1, artwork.getIntrinsicWidth());
                    float intrinsicHeight = Math.max(1, artwork.getIntrinsicHeight());
                    float scale = Math.min(thumbnail.width() / intrinsicWidth, thumbnail.height() / intrinsicHeight) * .86f;
                    int halfW = Math.round(intrinsicWidth * scale / 2);
                    int halfH = Math.round(intrinsicHeight * scale / 2);
                    int cx = Math.round(thumbnail.centerX());
                    int cy = Math.round(thumbnail.centerY());
                    artwork.setBounds(cx - halfW, cy - halfH, cx + halfW, cy + halfH);
                }
                artwork.draw(canvas);
            }
            canvas.restore();
            if (hasFocus()) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(0x88000000);
                paint.setStrokeWidth(ringContrastStroke);
                canvas.drawRoundRect(ring, ringRadius, ringRadius, paint);
                paint.setColor(0xFFF0F3F5);
                paint.setStrokeWidth(ringStroke);
                canvas.drawRoundRect(ring, ringRadius, ringRadius, paint);
            }
        }

        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName("android.widget.Button");
            info.setContentDescription(apps.get(index).label);
            info.setSelected(hasFocus());
        }
    }
}
