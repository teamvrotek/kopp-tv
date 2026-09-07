package ee.kalle.minimaltv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Small remote-friendly settings screen. Location searches happen only on explicit request. */
public final class SettingsActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Object connectionLock = new Object();
    private UserSettings settings;
    private Button cityButton;
    private AlertDialog dialog;
    private Future<?> search;
    private HttpURLConnection activeConnection;
    private volatile int searchGeneration;
    private volatile boolean active;

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        settings = new UserSettings(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(18, 20, 23));
        ScrollView scroll = new ScrollView(this);
        FrameLayout.LayoutParams bounds = new FrameLayout.LayoutParams(
                Math.min(dp(620), getResources().getDisplayMetrics().widthPixels - dp(64)), -2, Gravity.CENTER);
        root.addView(scroll, bounds);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(dp(16), dp(20), dp(16), dp(20));
        scroll.addView(rows);
        TextView title = new TextView(this);
        title.setText(R.string.settings_title);
        title.setTextSize(26);
        title.setTextColor(Color.WHITE);
        title.setPadding(dp(8), 0, 0, dp(12));
        rows.addView(title);
        String[] languageNames = languageNames();
        int selectedLanguage = "en".equals(settings.languageTag()) ? 1 : "et".equals(settings.languageTag()) ? 2 : 0;
        Button language = addButton(rows, getString(R.string.settings_language_value, languageNames[selectedLanguage]));
        language.setOnClickListener(view -> chooseLanguage());
        cityButton = addButton(rows, "");
        updateCityButton();
        cityButton.setOnClickListener(view -> askCity());
        CheckBox clock = new CheckBox(this);
        clock.setText(R.string.settings_show_clock);
        clock.setChecked(settings.showClock());
        clock.setMinHeight(dp(52));
        clock.setOnCheckedChangeListener((button, checked) -> settings.setShowClock(checked));
        rows.addView(clock, new LinearLayout.LayoutParams(-1, -2));
        CheckBox weather = new CheckBox(this);
        weather.setText(R.string.settings_show_weather);
        weather.setChecked(settings.showWeather());
        weather.setMinHeight(dp(52));
        weather.setOnCheckedChangeListener((button, checked) -> settings.setShowWeather(checked));
        rows.addView(weather, new LinearLayout.LayoutParams(-1, -2));
        addButton(rows, getString(R.string.settings_done)).setOnClickListener(view -> finish());
        setContentView(root);
        language.requestFocus();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private Button addButton(LinearLayout rows, String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        button.setPadding(dp(16), dp(8), dp(16), dp(8));
        button.setMinHeight(dp(52));
        rows.addView(button, new LinearLayout.LayoutParams(-1, -2));
        return button;
    }

    private String[] languageNames() {
        return new String[] {getString(R.string.settings_language_system),
                getString(R.string.settings_language_english), getString(R.string.settings_language_estonian)};
    }

    private void chooseLanguage() {
        int selected = "en".equals(settings.languageTag()) ? 1 : "et".equals(settings.languageTag()) ? 2 : 0;
        dialog = new AlertDialog.Builder(this).setTitle(R.string.settings_language)
                .setSingleChoiceItems(languageNames(), selected, (picker, index) -> {
                    String tag = new String[] {"", "en", "et"}[index];
                    picker.dismiss();
                    if (!tag.equals(settings.languageTag())) {
                        settings.setLanguageTag(tag);
                        recreate();
                    }
                }).setNegativeButton(R.string.settings_cancel, null).show();
    }

    private void updateCityButton() {
        cityButton.setText(getString(R.string.settings_city_value,
                settings.hasCity() ? settings.cityName() : getString(R.string.settings_city_unconfigured)));
    }

    private void askCity() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(80)});
        input.setHint(R.string.settings_city_hint);
        if (settings.hasCity()) input.setText(settings.cityName());
        input.selectAll();
        FrameLayout field = new FrameLayout(this);
        field.setPadding(dp(24), dp(8), dp(24), 0);
        field.addView(input, new FrameLayout.LayoutParams(-1, -2));
        AlertDialog prompt = new AlertDialog.Builder(this).setTitle(R.string.settings_city)
                .setView(field).setPositiveButton(R.string.settings_search, null)
                .setNegativeButton(R.string.settings_cancel, null).create();
        dialog = prompt;
        prompt.setOnShowListener(ignored -> {
            prompt.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                String query = input.getText().toString().trim();
                if (query.codePointCount(0, query.length()) < 2) {
                    input.setError(getString(R.string.settings_search_minimum));
                    return;
                }
                prompt.dismiss();
                searchCities(query);
            });
            input.setOnEditorActionListener((view, action, event) -> {
                if (action != EditorInfo.IME_ACTION_SEARCH) return false;
                prompt.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                return true;
            });
            input.requestFocus();
            if (prompt.getWindow() != null) prompt.getWindow().setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        });
        prompt.show();
    }

    private void searchCities(String query) {
        cancelSearch();
        final int generation = ++searchGeneration;
        final String language = LauncherLocale.locale(this).getLanguage().toLowerCase(Locale.ROOT);
        dialog = new AlertDialog.Builder(this).setMessage(R.string.settings_searching)
                .setNegativeButton(R.string.settings_cancel, (which, button) -> cancelSearch()).create();
        dialog.setOnCancelListener(ignored -> cancelSearch());
        dialog.show();
        search = executor.submit(() -> {
            List<City> found = null;
            try { found = fetchCities(query, language, generation); }
            catch (Exception ignored) { }
            final List<City> results = found;
            handler.post(() -> {
                if (!active || generation != searchGeneration) return;
                search = null;
                if (dialog != null) dialog.dismiss();
                if (results == null || results.isEmpty()) {
                    dialog = new AlertDialog.Builder(this)
                            .setMessage(results == null ? R.string.settings_city_error : R.string.settings_no_cities)
                            .setPositiveButton(R.string.settings_done, null).show();
                    return;
                }
                String[] labels = new String[results.size()];
                for (int i = 0; i < labels.length; i++) labels[i] = results.get(i).label;
                dialog = new AlertDialog.Builder(this).setTitle(R.string.settings_choose_city)
                        .setItems(labels, (picker, index) -> {
                            City city = results.get(index);
                            settings.setCity(city.name, city.latitude, city.longitude, city.timezone);
                            updateCityButton();
                        }).setNegativeButton(R.string.settings_cancel, null).show();
            });
        });
    }

    private List<City> fetchCities(String query, String language, int generation) throws Exception {
        String address = "https://geocoding-api.open-meteo.com/v1/search?name="
                + URLEncoder.encode(query, "UTF-8") + "&count=10&format=json&language="
                + URLEncoder.encode(language, "UTF-8");
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        try {
            synchronized (connectionLock) {
                if (!currentSearch(generation)) return null;
                activeConnection = connection;
            }
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(5_000);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            if (!currentSearch(generation)) return null;
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) throw new IOException("Location search failed");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            long deadline = SystemClock.elapsedRealtime() + 12_000;
            try (InputStream stream = connection.getInputStream()) {
                byte[] buffer = new byte[2048];
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    if (!currentSearch(generation)) return null;
                    if (SystemClock.elapsedRealtime() > deadline || bytes.size() + count > 131_072) {
                        throw new IOException("Location response limit exceeded");
                    }
                    bytes.write(buffer, 0, count);
                }
            }
            return parseCities(new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8)));
        } finally {
            synchronized (connectionLock) {
                if (activeConnection == connection) activeConnection = null;
            }
            connection.disconnect();
        }
    }

    static List<City> parseCities(JSONObject json) throws Exception {
        List<City> cities = new ArrayList<>();
        if (json.optBoolean("error", false)) throw new IOException("Location response error");
        JSONArray results = json.optJSONArray("results");
        if (results == null) return cities;
        for (int i = 0; i < Math.min(10, results.length()); i++) {
            JSONObject place = results.optJSONObject(i);
            if (place == null) continue;
            String name = place.optString("name", "").trim();
            double latitude = place.optDouble("latitude", Double.NaN);
            double longitude = place.optDouble("longitude", Double.NaN);
            if (name.isEmpty() || !UserSettings.validCoordinates(latitude, longitude)) continue;
            LinkedHashSet<String> parts = new LinkedHashSet<>();
            parts.add(name);
            for (String key : new String[] {"admin1", "admin2", "country"}) {
                String part = place.optString(key, "").trim();
                if ("country".equals(key) && part.isEmpty()) part = place.optString("country_code", "").trim();
                if (!part.isEmpty()) parts.add(part);
            }
            cities.add(new City(name, String.join(", ", parts), latitude, longitude,
                    place.optString("timezone", "")));
        }
        return cities;
    }

    private boolean currentSearch(int generation) {
        return active && generation == searchGeneration && !Thread.currentThread().isInterrupted();
    }

    private void cancelSearch() {
        searchGeneration++;
        if (search != null) search.cancel(true);
        search = null;
        final HttpURLConnection abandoned;
        synchronized (connectionLock) {
            abandoned = activeConnection;
            activeConnection = null;
        }
        if (abandoned != null) {
            Thread cleanup = new Thread(() -> {
                try { abandoned.disconnect(); } catch (RuntimeException ignored) { }
            }, "kopp-tv-city-cancel");
            cleanup.setDaemon(true);
            cleanup.start();
        }
    }

    @Override protected void onResume() { super.onResume(); active = true; }

    @Override protected void onPause() {
        active = false;
        cancelSearch();
        if (dialog != null) dialog.dismiss();
        super.onPause();
    }

    @Override protected void onDestroy() {
        cancelSearch();
        executor.shutdownNow();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    static final class City {
        final String name;
        final String label;
        final double latitude;
        final double longitude;
        final String timezone;
        City(String name, String label, double latitude, double longitude, String timezone) {
            this.name = name; this.label = label; this.latitude = latitude;
            this.longitude = longitude; this.timezone = timezone;
        }
    }
}
