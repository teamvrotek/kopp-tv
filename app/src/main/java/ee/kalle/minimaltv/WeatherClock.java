package ee.kalle.minimaltv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.format.DateFormat;
import android.view.View;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.text.NumberFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Foreground-only local clock and temperature for the user's selected city. */
public final class WeatherClock extends LinearLayout {
    private static final long MINUTE = 60_000L;
    private static final long REFRESH_INTERVAL = 30 * MINUTE;
    private static final long RETRY_INTERVAL = 5 * MINUTE;
    private static final long MAX_CACHE_AGE = 2 * 60 * MINUTE;
    private static final long FUTURE_TOLERANCE = 15 * MINUTE;
    private static final int MAX_RESPONSE_BYTES = 65_536;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Object connectionLock = new Object();
    private final SharedPreferences cache;
    private final TextView timeText;
    private final TextView dateText;
    private final TextView temperatureText;
    private final Locale locale;
    private final boolean clockEnabled;
    private final boolean weatherEnabled;
    private final boolean hasCity;
    private final String cityName;
    private final String weatherUrl;

    private volatile boolean active;
    private volatile boolean destroyed;
    private volatile int requestGeneration;
    private HttpURLConnection activeConnection;
    private Future<?> request;
    private boolean fetching;
    private float temperature = Float.NaN;
    private long observedAt;
    private long fetchedAt;
    private long retryAfterElapsed;

    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            if (!active || destroyed || !clockEnabled) return;
            updateClock();
            showTemperature();
            long now = System.currentTimeMillis();
            handler.postDelayed(this, MINUTE - now % MINUTE + 25);
        }
    };

    private final Runnable weatherTick = new Runnable() {
        @Override public void run() {
            refreshWeather();
        }
    };

    public WeatherClock(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setGravity(Gravity.END);
        setFocusable(false);
        UserSettings settings = new UserSettings(context);
        locale = LauncherLocale.locale(context);
        clockEnabled = settings.showClock();
        weatherEnabled = settings.showWeather();
        hasCity = settings.hasCity();
        cityName = settings.cityName();
        weatherUrl = hasCity ? weatherUrl(settings.latitude(), settings.longitude()) : "";
        timeText = addLine(28, Color.argb(242, 255, 255, 255));
        dateText = addLine(13, Color.argb(210, 255, 255, 255));
        temperatureText = addLine(14, Color.argb(224, 255, 255, 255));
        timeText.setVisibility(clockEnabled ? View.VISIBLE : View.GONE);
        dateText.setVisibility(clockEnabled ? View.VISIBLE : View.GONE);
        temperatureText.setVisibility(weatherEnabled ? View.VISIBLE : View.GONE);
        cache = context.getApplicationContext().getSharedPreferences(hasCity
                ? cacheName(settings.latitude(), settings.longitude()) : "weather_clock_unconfigured", Context.MODE_PRIVATE);
        temperature = cache.getFloat("temperature_c", Float.NaN);
        observedAt = cache.getLong("observed_at", 0);
        fetchedAt = cache.getLong("fetched_at", 0);
        updateClock();
        showTemperature();
    }

    private TextView addLine(int sp, int color) {
        TextView view = new TextView(getContext());
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        view.setGravity(Gravity.END);
        view.setIncludeFontPadding(false);
        view.setSingleLine(true);
        view.setFocusable(false);
        float density = getResources().getDisplayMetrics().density;
        view.setShadowLayer(2 * density, 0, density, Color.argb(180, 0, 0, 0));
        LayoutParams params = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.END;
        if (getChildCount() > 0) params.topMargin = Math.round(3 * density);
        addView(view, params);
        return view;
    }

    /** Call on the UI thread from the host Activity's onResume. */
    public void resume() {
        if (destroyed || active) return;
        active = true;
        handler.removeCallbacks(clockTick);
        handler.removeCallbacks(weatherTick);
        if (clockEnabled) clockTick.run();
        else showTemperature();
        refreshWeather();
    }

    /** Call on the UI thread from the host Activity's onPause. */
    public void pause() {
        active = false;
        requestGeneration++;
        handler.removeCallbacks(clockTick);
        handler.removeCallbacks(weatherTick);
        fetching = false;
        if (request != null) {
            request.cancel(true);
            request = null;
        }
        final HttpURLConnection abandoned;
        synchronized (connectionLock) {
            abandoned = activeConnection;
            activeConnection = null;
        }
        if (abandoned != null) {
            Thread cleanup = new Thread(() -> {
                try { abandoned.disconnect(); }
                catch (RuntimeException ignored) { }
            }, "kopp-tv-weather-cancel");
            cleanup.setDaemon(true);
            cleanup.start();
        }
    }

    /** Call on the UI thread from the host Activity's onDestroy. */
    public void destroy() {
        if (destroyed) return;
        destroyed = true;
        pause();
        handler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
    }

    public String getTemperatureText() {
        return temperatureText.getText().toString();
    }

    private void updateClock() {
        if (!clockEnabled) return;
        Date now = new Date();
        // Read the device preference, not the override language's default 12/24-hour convention.
        boolean hour24 = DateFormat.is24HourFormat(getContext().getApplicationContext());
        SimpleDateFormat timeFormat = new SimpleDateFormat(
                DateFormat.getBestDateTimePattern(locale, hour24 ? "Hm" : "hm"), locale);
        SimpleDateFormat dateFormat = new SimpleDateFormat(
                DateFormat.getBestDateTimePattern(locale, "EEEEMMMMd"), locale);
        TimeZone zone = TimeZone.getDefault();
        timeFormat.setTimeZone(zone);
        dateFormat.setTimeZone(zone);
        setTextIfChanged(timeText, timeFormat.format(now));
        setTextIfChanged(dateText, dateFormat.format(now));
    }

    private static void setTextIfChanged(TextView view, String text) {
        if (!text.contentEquals(view.getText())) view.setText(text);
    }

    private boolean cacheUsable(long now) {
        return validReading(temperature, observedAt, fetchedAt, now);
    }

    private void showTemperature() {
        if (!weatherEnabled) return;
        String value;
        if (!hasCity) value = getContext().getString(R.string.weather_choose_location);
        else if (cacheUsable(System.currentTimeMillis())) {
            value = getContext().getString(R.string.weather_city_temperature, cityName,
                    NumberFormat.getIntegerInstance(locale).format(Math.round(temperature)));
        } else value = getContext().getString(R.string.weather_city_unavailable, cityName);
        setTextIfChanged(temperatureText, value);
    }

    private void refreshWeather() {
        if (!active || destroyed || !weatherEnabled || !hasCity) return;
        handler.removeCallbacks(weatherTick);
        showTemperature();
        if (fetching) return;
        long now = System.currentTimeMillis();
        long retryDelay = retryAfterElapsed - SystemClock.elapsedRealtime();
        long cacheDelay = cacheUsable(now) ? REFRESH_INTERVAL - (now - fetchedAt) : 0;
        long delay = Math.max(retryDelay, cacheDelay);
        if (delay > 0) {
            scheduleWeather(delay);
            return;
        }
        fetching = true;
        final int generation = ++requestGeneration;
        request = executor.submit(new Runnable() {
            @Override public void run() {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                WeatherResult result = null;
                try {
                    result = fetchTemperature(generation);
                } catch (Exception ignored) {
                    // The last valid temperature remains usable only until its expiry.
                }
                final WeatherResult completed = result;
                if (!isCurrentRequest(generation)) return;
                handler.post(new Runnable() {
                    @Override public void run() {
                        if (!isCurrentRequest(generation)) return;
                        fetching = false;
                        request = null;
                        if (completed != null) {
                            temperature = completed.temperature;
                            observedAt = completed.observedAt;
                            fetchedAt = completed.fetchedAt;
                            cache.edit().putFloat("temperature_c", temperature)
                                    .putLong("observed_at", observedAt)
                                    .putLong("fetched_at", fetchedAt).apply();
                        }
                        long interval = completed == null ? RETRY_INTERVAL : REFRESH_INTERVAL;
                        retryAfterElapsed = SystemClock.elapsedRealtime() + interval;
                        showTemperature();
                        scheduleWeather(interval);
                    }
                });
            }
        });
    }

    private void scheduleWeather(long delay) {
        if (!active || destroyed || !weatherEnabled || !hasCity) return;
        long now = System.currentTimeMillis();
        if (cacheUsable(now)) {
            long expiresAt = Math.min(fetchedAt, observedAt) + MAX_CACHE_AGE;
            delay = Math.min(delay, Math.max(1, expiresAt - now));
        }
        handler.removeCallbacks(weatherTick);
        handler.postDelayed(weatherTick, Math.max(1, delay));
    }

    private boolean isCurrentRequest(int generation) {
        return active && !destroyed && generation == requestGeneration
                && !Thread.currentThread().isInterrupted();
    }

    private WeatherResult fetchTemperature(int generation) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(weatherUrl).openConnection();
        try {
            synchronized (connectionLock) {
                if (!isCurrentRequest(generation)) return null;
                activeConnection = connection;
            }
            connection.setConnectTimeout(6_000);
            connection.setReadTimeout(8_000);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "KoppTV");
            if (!isCurrentRequest(generation)) return null;
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("Weather request failed");
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            long deadline = SystemClock.elapsedRealtime() + 15_000;
            try (InputStream stream = connection.getInputStream()) {
                byte[] buffer = new byte[2048];
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    if (!isCurrentRequest(generation)) return null;
                    if (SystemClock.elapsedRealtime() > deadline
                            || bytes.size() + count > MAX_RESPONSE_BYTES) {
                        throw new IOException("Weather response limit exceeded");
                    }
                    bytes.write(buffer, 0, count);
                }
            }
            JSONObject json = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            return parseWeather(json, System.currentTimeMillis());
        } finally {
            synchronized (connectionLock) {
                if (activeConnection == connection) activeConnection = null;
            }
            connection.disconnect();
        }
    }

    static String cacheName(double latitude, double longitude) {
        return "weather_clock_v2_" + Long.toHexString(Double.doubleToLongBits(latitude))
                + "_" + Long.toHexString(Double.doubleToLongBits(longitude));
    }

    static String weatherUrl(double latitude, double longitude) {
        if (!UserSettings.validCoordinates(latitude, longitude)) throw new IllegalArgumentException("Invalid location");
        return "https://api.open-meteo.com/v1/forecast?latitude=" + Double.toString(latitude)
                + "&longitude=" + Double.toString(longitude)
                + "&current=temperature_2m&temperature_unit=celsius&timeformat=unixtime"
                + "&timezone=GMT&forecast_days=1";
    }

    static boolean validReading(float value, long observed, long fetched, long now) {
        return !Float.isNaN(value) && !Float.isInfinite(value) && value >= -100 && value <= 70
                && observed > 0 && fetched > 0 && now >= fetched && now - fetched < MAX_CACHE_AGE
                && observed <= now + FUTURE_TOLERANCE && now - observed < MAX_CACHE_AGE;
    }

    static WeatherResult parseWeather(JSONObject json, long fetched) throws Exception {
        if (!"°C".equals(json.getJSONObject("current_units").getString("temperature_2m"))) {
            throw new IOException("Unexpected temperature unit");
        }
        JSONObject current = json.getJSONObject("current");
        double value = current.getDouble("temperature_2m");
        if (Double.isNaN(value) || Double.isInfinite(value) || value < -100 || value > 70) {
            throw new IOException("Invalid temperature");
        }
        long seconds = current.getLong("time");
        if (seconds <= 0 || seconds > Long.MAX_VALUE / 1000L) throw new IOException("Invalid observation time");
        long observed = seconds * 1000L;
        if (!validReading((float) value, observed, fetched, fetched)) throw new IOException("Invalid or stale temperature");
        return new WeatherResult((float) value, observed, fetched);
    }

    static final class WeatherResult {
        final float temperature;
        final long observedAt;
        final long fetchedAt;

        WeatherResult(float temperature, long observedAt, long fetchedAt) {
            this.temperature = temperature;
            this.observedAt = observedAt;
            this.fetchedAt = fetchedAt;
        }
    }
}
