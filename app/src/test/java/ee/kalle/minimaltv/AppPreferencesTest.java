package ee.kalle.minimaltv;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class AppPreferencesTest {
    private Context context;

    @Before public void clearSavedChoices() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("apps", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("home", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test public void aFreshInstallRemainsUnconfiguredAndEmpty() {
        AppPreferences choices = new AppPreferences(context);
        choices.migrateLegacyIfNeeded();
        assertFalse(choices.isConfigured());
        assertEquals(Collections.emptyList(), choices.selectedPackages());
    }

    @Test public void aFreshHomeServiceBootMarkerDoesNotCreateTheLegacySixApps() {
        context.getSharedPreferences("home", Context.MODE_PRIVATE).edit().putInt("last_boot", 23).commit();
        AppPreferences choices = new AppPreferences(context);
        choices.migrateLegacyIfNeeded();
        assertFalse(choices.isConfigured());
        assertEquals(Collections.emptyList(), choices.selectedPackages());
    }

    @Test public void upgradingTheOldLauncherKeepsItsExactAppOrderAndFocusIndex() {
        context.getSharedPreferences("home", Context.MODE_PRIVATE).edit().putInt("selected", 4).commit();
        AppPreferences choices = new AppPreferences(context);
        choices.migrateLegacyIfNeeded();
        assertTrue(choices.isConfigured());
        assertEquals(Arrays.asList("com.netflix.ninja", "tv.go3.android.tv",
            "com.google.android.youtube.tv", "ee.telia.teliatv", "com.disney.disneyplus",
            "com.apple.atve.androidtv.appletv"), choices.selectedPackages());
        assertEquals(4, context.getSharedPreferences("home", Context.MODE_PRIVATE).getInt("selected", -1));
    }

    @Test public void aSavedEmptyHomeIsConfiguredAndCannotBeReplacedByLegacyChoices() {
        context.getSharedPreferences("home", Context.MODE_PRIVATE).edit().putInt("selected", 0).commit();
        new AppPreferences(context).save(Collections.emptyList());
        AppPreferences reopened = new AppPreferences(context);
        reopened.migrateLegacyIfNeeded();
        assertTrue(reopened.isConfigured());
        assertEquals(Collections.emptyList(), reopened.selectedPackages());
    }

    @Test public void orderSurvivesReopeningEvenForUnavailablePackages() {
        new AppPreferences(context).save(Arrays.asList("installed.app", "missing.app", "other.app"));
        AppPreferences reopened = new AppPreferences(context);
        assertTrue(reopened.isConfigured());
        assertEquals(Arrays.asList("installed.app", "missing.app", "other.app"), reopened.selectedPackages());
    }

    @Test public void openingAndDiscardingAnEditedDraftDoesNotWritePreferences() {
        AppPreferences choices = new AppPreferences(context);
        choices.save(Arrays.asList("one.app", "two.app"));
        AppSelection draft = new AppSelection(choices.selectedPackages());
        draft.setSelected("one.app", false);
        draft.setSelected("three.app", true);
        assertEquals(Arrays.asList("one.app", "two.app"), new AppPreferences(context).selectedPackages());
    }

    @Test public void malformedStoredJsonDoesNotInventDefaultApps() {
        context.getSharedPreferences("apps", Context.MODE_PRIVATE).edit()
            .putBoolean("configured", true).putString("ordered_packages", "not json").commit();
        AppPreferences choices = new AppPreferences(context);
        choices.migrateLegacyIfNeeded();
        assertTrue(choices.isConfigured());
        assertEquals(Collections.emptyList(), choices.selectedPackages());
    }

    @Test public void savingChoicesRemovesDuplicatesWithoutLosingTheirFirstPositions() {
        new AppPreferences(context).save(Arrays.asList("second.app", "first.app", "second.app", "", null));
        assertEquals(Arrays.asList("second.app", "first.app"), new AppPreferences(context).selectedPackages());
    }
}
