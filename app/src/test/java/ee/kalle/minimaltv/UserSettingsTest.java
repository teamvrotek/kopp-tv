package ee.kalle.minimaltv;

import android.content.Context;
import android.content.res.Configuration;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.Locale;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class UserSettingsTest {
    private Context context;

    @Before public void clearSettings() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("user_settings", 0).edit().clear().commit();
        context.getSharedPreferences("home", 0).edit().clear().commit();
    }

    @Test public void freshInstallationUsesSystemLanguageAndNoAssumedCity() {
        UserSettings settings = new UserSettings(context);
        assertEquals("", settings.languageTag());
        assertTrue(settings.showClock());
        assertTrue(settings.showWeather());
        assertFalse(settings.hasCity());
    }

    @Test public void freshHomeServiceBootRecordDoesNotTriggerLegacyMigration() {
        context.getSharedPreferences("home", 0).edit().putInt("last_boot", 10).commit();
        UserSettings settings = new UserSettings(context);
        assertEquals("", settings.languageTag());
        assertFalse(settings.hasCity());
    }

    @Test public void originalSelectedIndexPreservesEstonianAndParnuOnce() {
        context.getSharedPreferences("home", 0).edit().putInt("selected", 0).commit();
        UserSettings settings = new UserSettings(context);
        assertEquals("et", settings.languageTag());
        assertEquals("Pärnu", settings.cityName());
        assertEquals(58.38588, settings.latitude(), 0.000001);
        assertEquals(24.49711, settings.longitude(), 0.000001);
        settings.setLanguageTag("en");
        settings.setCity("London", 51.50853, -0.12574, "Europe/London");
        settings.setShowClock(false);
        UserSettings reopened = new UserSettings(context);
        assertEquals("en", reopened.languageTag());
        assertEquals("London", reopened.cityName());
        assertFalse(reopened.showClock());
    }

    @Test public void migrationDecisionIsNotRepeatedAfterFirstInitialization() {
        new UserSettings(context);
        context.getSharedPreferences("home", 0).edit().putInt("selected", 2).commit();
        UserSettings reopened = new UserSettings(context);
        assertEquals("", reopened.languageTag());
        assertFalse(reopened.hasCity());
    }

    @Test public void displayTogglesAndCityChangesHaveIndependentSignatures() {
        UserSettings settings = new UserSettings(context);
        String original = settings.signature();
        settings.setShowClock(false);
        assertNotEquals(original, settings.signature());
        assertTrue(settings.showWeather());
        String noClock = settings.signature();
        settings.setShowWeather(false);
        assertNotEquals(noClock, settings.signature());
        String noWeather = settings.signature();
        settings.setCity("Tallinn", 59.437, 24.7536, "Europe/Tallinn");
        assertNotEquals(noWeather, settings.signature());
        assertTrue(settings.hasCity());
    }

    @Test public void rejectsUnsupportedLanguagesAndInvalidLocations() {
        UserSettings settings = new UserSettings(context);
        assertThrows(IllegalArgumentException.class, () -> settings.setLanguageTag("fr"));
        assertThrows(IllegalArgumentException.class, () -> settings.setCity("", 0, 0, ""));
        assertThrows(IllegalArgumentException.class, () -> settings.setCity("City", 91, 0, ""));
        assertThrows(IllegalArgumentException.class, () -> settings.setCity("City", 0, Double.NaN, ""));
        assertFalse(settings.hasCity());
    }

    @Test public void localeOverrideUsesResourcesAndSystemRestoresContextLocale() {
        UserSettings settings = new UserSettings(context);
        settings.setLanguageTag("et");
        assertEquals("Seaded", LauncherLocale.wrap(context).getString(R.string.settings_title));
        settings.setLanguageTag("en");
        assertEquals("Settings", LauncherLocale.wrap(context).getString(R.string.settings_title));
        settings.setLanguageTag("");
        Configuration configuration = new Configuration(context.getResources().getConfiguration());
        configuration.setLocale(Locale.FRENCH);
        Context systemContext = context.createConfigurationContext(configuration);
        assertEquals("fr", LauncherLocale.locale(LauncherLocale.wrap(systemContext)).getLanguage());
    }
}
