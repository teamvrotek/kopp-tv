package ee.kalle.minimaltv;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ListView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ManageAppsActivityTest {
    private Context context;

    @Before public void resetPreferences() {
        context = RuntimeEnvironment.getApplication();
        for (String name : new String[] {"apps", "home", "user_settings"}) {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit();
        }
    }

    @Test public void backCancelsCheckboxEdits() {
        AppPreferences saved = new AppPreferences(context);
        saved.save(Arrays.asList("missing.one", "missing.two"));
        ManageAppsActivity activity = open();
        ListView list = find(activity.getWindow().getDecorView(), ListView.class);
        list.performItemClick(null, 0, list.getAdapter().getItemId(0));
        activity.onBackPressed();
        assertTrue(activity.isFinishing());
        assertEquals(Arrays.asList("missing.one", "missing.two"), saved.selectedPackages());
    }

    @Test public void doneCanSaveAnExplicitlyEmptyHome() {
        AppPreferences saved = new AppPreferences(context);
        saved.save(Arrays.asList("missing.one", "missing.two"));
        ManageAppsActivity activity = open();
        ListView list = find(activity.getWindow().getDecorView(), ListView.class);
        list.performItemClick(null, 0, list.getAdapter().getItemId(0));
        list.performItemClick(null, 1, list.getAdapter().getItemId(1));
        button(activity.getWindow().getDecorView(), activity.getString(R.string.apps_done)).performClick();
        assertTrue(activity.isFinishing());
        assertTrue(saved.isConfigured());
        assertEquals(Collections.emptyList(), saved.selectedPackages());
    }

    @Test public void arrangedOrderIsPersistedOnlyAfterDone() {
        AppPreferences saved = new AppPreferences(context);
        saved.save(Arrays.asList("missing.one", "missing.two", "missing.three"));
        ManageAppsActivity activity = open();
        button(activity.getWindow().getDecorView(), activity.getString(R.string.apps_arrange)).performClick();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        ListView list = find(dialog.getWindow().getDecorView(), ListView.class);
        list.performItemClick(null, 2, list.getAdapter().getItemId(2));
        Button earlier = button(dialog.getWindow().getDecorView(), activity.getString(R.string.apps_move_earlier));
        earlier.performClick();
        earlier.performClick();
        assertEquals(activity.getString(R.string.apps_unavailable, "missing.three"), list.getAdapter().getItem(0));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(Arrays.asList("missing.one", "missing.two", "missing.three"), saved.selectedPackages());
        button(activity.getWindow().getDecorView(), activity.getString(R.string.apps_done)).performClick();
        assertEquals(Arrays.asList("missing.three", "missing.one", "missing.two"), saved.selectedPackages());
    }

    @Test public void cancellingTheOrderDialogKeepsThePreviousOrder() {
        AppPreferences saved = new AppPreferences(context);
        saved.save(Arrays.asList("missing.one", "missing.two"));
        ManageAppsActivity activity = open();
        button(activity.getWindow().getDecorView(), activity.getString(R.string.apps_arrange)).performClick();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        ListView list = find(dialog.getWindow().getDecorView(), ListView.class);
        list.performItemClick(null, 1, list.getAdapter().getItemId(1));
        button(dialog.getWindow().getDecorView(), activity.getString(R.string.apps_move_earlier)).performClick();
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        button(activity.getWindow().getDecorView(), activity.getString(R.string.apps_done)).performClick();
        assertEquals(Arrays.asList("missing.one", "missing.two"), saved.selectedPackages());
    }

    private ManageAppsActivity open() {
        return Robolectric.buildActivity(ManageAppsActivity.class).setup().visible().get();
    }

    private static <T extends View> T find(View node, Class<T> type) {
        if (type.isInstance(node)) return type.cast(node);
        if (node instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) node;
            for (int index = 0; index < group.getChildCount(); index++) {
                T match = find(group.getChildAt(index), type);
                if (match != null) return match;
            }
        }
        return null;
    }

    private static Button button(View node, String label) {
        if (node instanceof Button && label.contentEquals(((Button) node).getText())) return (Button) node;
        if (node instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) node;
            for (int index = 0; index < group.getChildCount(); index++) {
                Button match = button(group.getChildAt(index), label);
                if (match != null) return match;
            }
        }
        return null;
    }
}
