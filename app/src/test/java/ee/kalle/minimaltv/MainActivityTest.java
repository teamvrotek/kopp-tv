package ee.kalle.minimaltv;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "en-rUS-land-xhdpi")
public class MainActivityTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        new UserSettings(context).setShowWeather(false);
    }

    private List<String> installApps(int count) {
        ArrayList<String> packages = new ArrayList<>();
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (int i = 0; i < count; i++) {
            String name = "test.tv.app" + i;
            packages.add(name);
            ResolveInfo info = new ResolveInfo();
            info.nonLocalizedLabel = "App " + i;
            info.activityInfo = new ActivityInfo();
            info.activityInfo.packageName = name;
            info.activityInfo.name = name + ".MainActivity";
            info.activityInfo.enabled = true;
            info.activityInfo.exported = true;
            info.activityInfo.applicationInfo = new ApplicationInfo();
            info.activityInfo.applicationInfo.packageName = name;
            info.activityInfo.applicationInfo.enabled = true;
            Shadows.shadowOf(context.getPackageManager()).addResolveInfoForIntent(query, info);
        }
        return packages;
    }

    private ActivityController<MainActivity> openHome() {
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        ViewGroup content = controller.get().findViewById(android.R.id.content);
        View home = content.getChildAt(0);
        for (int i = 0; i < 2; i++) {
            home.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY));
            home.layout(0, 0, 1920, 1080);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
        }
        return controller;
    }

    private static HorizontalScrollView scroll(View view) {
        if (view instanceof HorizontalScrollView) return (HorizontalScrollView) view;
        if (view instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
                HorizontalScrollView found = scroll(((ViewGroup) view).getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private void close(ActivityController<MainActivity> controller) { controller.pause().stop().destroy(); }

    @Test public void newInstallOpensPickerAndConfiguredEmptyHomeDoesNot() {
        ActivityController<MainActivity> first = openHome();
        assertEquals(ManageAppsActivity.class.getName(), Shadows.shadowOf(first.get()).getNextStartedActivity().getComponent().getClassName());
        close(first);
        new AppPreferences(context).save(new ArrayList<>());
        ActivityController<MainActivity> empty = openHome();
        assertNull(Shadows.shadowOf(empty.get()).getNextStartedActivity());
        assertEquals(View.GONE, scroll(empty.get().getWindow().getDecorView()).getVisibility());
        close(empty);
    }

    @Test public void chosenOrderIsRenderedAndNewInstallationsAreNotAdded() {
        List<String> packages = installApps(7);
        new AppPreferences(context).save(Arrays.asList(packages.get(4), packages.get(1), "removed.tv.app"));
        ActivityController<MainActivity> controller = openHome();
        LinearLayout row = (LinearLayout) scroll(controller.get().getWindow().getDecorView()).getChildAt(0);
        assertEquals(2, row.getChildCount());
        assertEquals("App 4", row.getChildAt(0).getContentDescription());
        assertEquals("App 1", row.getChildAt(1).getContentDescription());
        assertEquals(3, new AppPreferences(context).selectedPackages().size());
        close(controller);
    }

    @Test public void twelveAppsKeepSameTileWidthAndRestoreVisibleSelection() {
        List<String> packages = installApps(12);
        new AppPreferences(context).save(packages);
        context.getSharedPreferences("home", 0).edit().putString("selected_package", packages.get(11)).commit();
        ActivityController<MainActivity> controller = openHome();
        HorizontalScrollView viewport = scroll(controller.get().getWindow().getDecorView());
        LinearLayout row = (LinearLayout) viewport.getChildAt(0);
        assertEquals(12, row.getChildCount());
        assertEquals(row.getChildAt(0).getWidth(), row.getChildAt(6).getWidth());
        assertTrue(row.getChildAt(11).hasFocus());
        assertTrue(viewport.getScrollX() > 0);
        assertEquals(row.getWidth() - viewport.getWidth(), viewport.getScrollX());
        close(controller);
    }

    @Test public void returningAfterAppReorderKeepsFocusOnSamePackage() {
        List<String> packages = installApps(7);
        new AppPreferences(context).save(packages);
        ActivityController<MainActivity> controller = openHome();
        HorizontalScrollView viewport = scroll(controller.get().getWindow().getDecorView());
        ((ViewGroup) viewport.getChildAt(0)).getChildAt(4).requestFocus();
        controller.pause();
        new AppPreferences(context).save(Arrays.asList(packages.get(4), packages.get(0)));
        controller.resume();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        ViewGroup row = (ViewGroup) viewport.getChildAt(0);
        assertEquals(2, row.getChildCount());
        assertEquals("App 4", row.getChildAt(0).getContentDescription());
        assertTrue(row.getChildAt(0).hasFocus());
        close(controller);
    }

    @Test public void unchangedHomeReturnReusesCardsAndOwnsFocusScrolling() {
        new AppPreferences(context).save(installApps(7));
        ActivityController<MainActivity> controller = openHome();
        ViewGroup row = (ViewGroup) scroll(controller.get().getWindow().getDecorView()).getChildAt(0);
        View original = row.getChildAt(0);
        assertFalse(original.getRevealOnFocusHint());
        controller.pause().resume();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertSame(original, row.getChildAt(0));
        close(controller);
    }

    @Test public void menuCanOpenWithoutCardFocusAndUsesSelectedLocale() {
        new UserSettings(context).setLanguageTag("et");
        new AppPreferences(context).save(new ArrayList<>());
        ActivityController<MainActivity> controller = openHome();
        controller.get().dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MENU));
        AlertDialog menu = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue(menu.isShowing());
        List<String> expected = Arrays.asList("Järgmine taustapilt", "Halda rakendusi",
            "Google TV avakuva", "Androidi seaded", "Rakendusest", "Seaded");
        assertEquals(expected.size(), menu.getListView().getAdapter().getCount());
        for (int index = 0; index < expected.size(); index++) {
            assertEquals(expected.get(index), menu.getListView().getAdapter().getItem(index).toString());
        }
        menu.dismiss();
        close(controller);
    }
}
