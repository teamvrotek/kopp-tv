package ee.kalle.minimaltv;

import android.content.Context;
import android.view.View;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.List;
import java.util.Locale;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class WeatherSettingsTest {
    private Context context;

    @Before public void clearSettings() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("user_settings", 0).edit().clear().commit();
        context.getSharedPreferences("home", 0).edit().clear().commit();
    }

    private JSONObject reading(String unit, double temperature, long observedSeconds) throws Exception {
        return new JSONObject().put("current_units", new JSONObject().put("temperature_2m", unit))
                .put("current", new JSONObject().put("temperature_2m", temperature).put("time", observedSeconds));
    }

    @Test public void validatesUnitsObservationAgeAndTimestampOverflow() throws Exception {
        long now = 1_800_000_000_000L;
        WeatherClock.WeatherResult valid = WeatherClock.parseWeather(reading("°C", -3.5, now / 1000), now);
        assertEquals(-3.5f, valid.temperature, 0.0001f);
        assertThrows(Exception.class, () -> WeatherClock.parseWeather(reading("°F", 20, now / 1000), now));
        assertThrows(Exception.class, () -> WeatherClock.parseWeather(reading("°C", 71, now / 1000), now));
        assertThrows(Exception.class, () -> WeatherClock.parseWeather(reading("°C", 20, now / 1000 - 7200), now));
        assertThrows(Exception.class, () -> WeatherClock.parseWeather(reading("°C", 20, now / 1000 + 901), now));
        assertThrows(Exception.class, () -> WeatherClock.parseWeather(reading("°C", 20, Long.MAX_VALUE), now));
    }

    @Test public void coordinateCacheKeysAreDistinctAndUrlsIgnoreDecimalLocale() {
        assertNotEquals(WeatherClock.cacheName(58.38588, 24.49711), WeatherClock.cacheName(59.437, 24.7536));
        assertNotEquals(WeatherClock.cacheName(58.38588, 24.49711), WeatherClock.cacheName(58.38588, 25));
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMAN);
            assertTrue(WeatherClock.weatherUrl(58.38588, 24.49711).contains("latitude=58.38588&longitude=24.49711"));
        } finally { Locale.setDefault(original); }
    }

    @Test public void differentCityDoesNotDisplayOldCityCache() {
        long now = System.currentTimeMillis();
        context.getSharedPreferences(WeatherClock.cacheName(58.38588, 24.49711), 0).edit()
                .putFloat("temperature_c", 22).putLong("observed_at", now).putLong("fetched_at", now).commit();
        UserSettings settings = new UserSettings(context);
        settings.setLanguageTag("en");
        settings.setCity("Different city", 1, 2, "");
        WeatherClock widget = new WeatherClock(LauncherLocale.wrap(context));
        assertEquals("Different city · Weather unavailable", widget.getTemperatureText());
        widget.destroy();
    }

    @Test public void unconfiguredLocationAndIndependentClockToggleAreLocalized() {
        UserSettings settings = new UserSettings(context);
        settings.setLanguageTag("et");
        settings.setShowClock(false);
        WeatherClock widget = new WeatherClock(LauncherLocale.wrap(context));
        assertEquals("Vali ilma asukoht", widget.getTemperatureText());
        assertEquals(View.GONE, widget.getChildAt(0).getVisibility());
        assertEquals(View.GONE, widget.getChildAt(1).getVisibility());
        assertEquals(View.VISIBLE, widget.getChildAt(2).getVisibility());
        widget.destroy();
    }

    @Test public void geocodingDisambiguatesResultsAndSkipsInvalidCoordinates() throws Exception {
        JSONObject json = new JSONObject("{\"results\":["
                + "{\"name\":\"Paris\",\"latitude\":48.85,\"longitude\":2.35,\"admin1\":\"Île-de-France\",\"country\":\"France\",\"timezone\":\"Europe/Paris\"},"
                + "{\"name\":\"Paris\",\"latitude\":33.66,\"longitude\":-95.55,\"admin1\":\"Texas\",\"country\":\"United States\"},"
                + "{\"name\":\"Broken\",\"latitude\":999,\"longitude\":0}]}");
        List<SettingsActivity.City> cities = SettingsActivity.parseCities(json);
        assertEquals(2, cities.size());
        assertEquals("Paris, Île-de-France, France", cities.get(0).label);
        assertEquals("Paris, Texas, United States", cities.get(1).label);
        assertEquals("Europe/Paris", cities.get(0).timezone);
        assertTrue(SettingsActivity.parseCities(new JSONObject()).isEmpty());
        assertThrows(Exception.class, () -> SettingsActivity.parseCities(new JSONObject().put("error", true)));
    }
}
