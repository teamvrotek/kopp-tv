package ee.kalle.minimaltv;

import android.content.Context;
import android.content.SharedPreferences;

/** App preferences, including a one-time migration of the original personal setup. */
public final class UserSettings {
    private final SharedPreferences preferences;

    public UserSettings(Context context) {
        this(context.getApplicationContext().getSharedPreferences("user_settings", Context.MODE_PRIVATE),
                context.getApplicationContext().getSharedPreferences("home", Context.MODE_PRIVATE));
    }

    UserSettings(SharedPreferences preferences, SharedPreferences previous) {
        this.preferences = preferences;
        synchronized (UserSettings.class) {
            if (!preferences.getBoolean("initialized", false)) {
                boolean legacy = previous.contains("selected");
                SharedPreferences.Editor editor = preferences.edit()
                        .putBoolean("initialized", true)
                        .putString("language", legacy ? "et" : "")
                        .putBoolean("show_clock", true).putBoolean("show_weather", true);
                if (legacy) {
                    editor.putString("city_name", "Pärnu")
                            .putLong("latitude", Double.doubleToLongBits(58.38588))
                            .putLong("longitude", Double.doubleToLongBits(24.49711))
                            .putString("city_timezone", "Europe/Tallinn");
                }
                editor.apply();
            }
        }
    }

    public String languageTag() {
        String tag = preferences.getString("language", "");
        return "en".equals(tag) || "et".equals(tag) ? tag : "";
    }

    public boolean showClock() { return preferences.getBoolean("show_clock", true); }
    public boolean showWeather() { return preferences.getBoolean("show_weather", true); }
    public String cityName() { return preferences.getString("city_name", ""); }
    public double latitude() { return Double.longBitsToDouble(preferences.getLong("latitude", Double.doubleToLongBits(Double.NaN))); }
    public double longitude() { return Double.longBitsToDouble(preferences.getLong("longitude", Double.doubleToLongBits(Double.NaN))); }
    public boolean hasCity() { return !cityName().isEmpty() && validCoordinates(latitude(), longitude()); }

    public String signature() {
        return languageTag() + "\0" + showClock() + "\0" + showWeather() + "\0" + cityName()
                + "\0" + Double.toHexString(latitude()) + "\0" + Double.toHexString(longitude());
    }

    public void setLanguageTag(String tag) {
        if (!("".equals(tag) || "en".equals(tag) || "et".equals(tag))) {
            throw new IllegalArgumentException("Unsupported language");
        }
        preferences.edit().putString("language", tag).apply();
    }

    public void setShowClock(boolean show) { preferences.edit().putBoolean("show_clock", show).apply(); }
    public void setShowWeather(boolean show) { preferences.edit().putBoolean("show_weather", show).apply(); }

    public void setCity(String name, double latitude, double longitude, String timezone) {
        if (name == null || name.trim().isEmpty() || !validCoordinates(latitude, longitude)) {
            throw new IllegalArgumentException("Invalid weather location");
        }
        preferences.edit().putString("city_name", name.trim())
                .putLong("latitude", Double.doubleToLongBits(latitude))
                .putLong("longitude", Double.doubleToLongBits(longitude))
                .putString("city_timezone", timezone == null ? "" : timezone).apply();
    }

    static boolean validCoordinates(double latitude, double longitude) {
        return !Double.isNaN(latitude) && !Double.isInfinite(latitude)
                && !Double.isNaN(longitude) && !Double.isInfinite(longitude)
                && latitude >= -90 && latitude <= 90 && longitude >= -180 && longitude <= 180;
    }
}
