package ee.kalle.minimaltv;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Stores the chosen order without removing temporarily unavailable packages. */
public final class AppPreferences {
    private static final String KEY_PACKAGES = "ordered_packages";
    private static final String KEY_CONFIGURED = "configured";
    private static final String KEY_LEGACY_CHECKED = "legacy_checked";
    private final SharedPreferences preferences;
    private final SharedPreferences legacy;

    public AppPreferences(Context context) {
        preferences = context.getSharedPreferences("apps", Context.MODE_PRIVATE);
        legacy = context.getSharedPreferences("home", Context.MODE_PRIVATE);
    }

    public boolean isConfigured() { return preferences.getBoolean(KEY_CONFIGURED, false); }

    public List<String> selectedPackages() {
        ArrayList<String> result = new ArrayList<>();
        try {
            JSONArray saved = new JSONArray(preferences.getString(KEY_PACKAGES, "[]"));
            for (int index = 0; index < saved.length(); index++) {
                Object value = saved.opt(index);
                if (value instanceof String) result.add((String) value);
            }
        } catch (JSONException error) {
            Log.w("KOPP_TV", "Unable to read saved app selection", error);
        }
        return AppSelection.normalize(result);
    }

    public void save(List<String> packages) {
        JSONArray saved = new JSONArray(AppSelection.normalize(packages));
        preferences.edit().putString(KEY_PACKAGES, saved.toString())
            .putBoolean(KEY_CONFIGURED, true).putBoolean(KEY_LEGACY_CHECKED, true).apply();
    }

    public void migrateLegacyIfNeeded() {
        if (isConfigured() || preferences.getBoolean(KEY_LEGACY_CHECKED, false)) return;
        // Old launchers saved an integer selection; a new Home service also writes last_boot.
        if (legacy.contains("selected")) {
            save(Arrays.asList("com.netflix.ninja", "tv.go3.android.tv",
                "com.google.android.youtube.tv", "ee.telia.teliatv",
                "com.disney.disneyplus", "com.apple.atve.androidtv.appletv"));
        } else {
            preferences.edit().putBoolean(KEY_LEGACY_CHECKED, true).apply();
        }
    }
}
