package ee.kalle.minimaltv;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.media.ExifInterface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Local photo rotation. All public methods are called on the activity's main thread. */
public final class WallpaperController {
    private static final String TAG = "MINIMAL_TV";
    private static final long INTERVAL_MS = 600_000L;
    private static final long FADE_MS = 1_200L;
    private final Activity activity;
    private final FrameLayout container;
    private final SharedPreferences preferences;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService decoder = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "minimal-tv-wallpaper");
        thread.setPriority(Thread.NORM_PRIORITY);
        return thread;
    });
    private ImageView currentView;
    private ImageView incomingView;
    private Bitmap currentBitmap;
    // This is either the ready cache or the image currently fading in, never both.
    private Bitmap incomingBitmap;
    private Photo currentPhoto;
    private Photo incomingPhoto;
    private String currentFilename;
    private long lastSwitch;
    private long incomingSwitch;
    private long fadeStarted;
    private long nextDueUptime;
    private long requestStarted;
    private long lastDecodeMs;
    private boolean active;
    private boolean destroyed;
    private boolean loading;
    private boolean fading;
    private boolean pendingAdvance;
    private volatile long generation;
    private Future<?> decodeTask;
    private String directoryPath = "";
    private List<Photo> inventory = Collections.emptyList();
    private String lastError = "";

    // Accessed only by the single decoder worker. Changed files get another attempt.
    private String workerListSignature = "";
    private final Set<String> workerBadFiles = new HashSet<>();

    private final Runnable rotation = () -> {
        nextDueUptime = 0L;
        if (!active || destroyed) return;
        pendingAdvance = true;
        // Refresh the folder before each timed rotation, including a growing folder.
        refreshImages();
    };
    private final Runnable fadeFrame = new Runnable() {
        @Override public void run() {
            if (!active || destroyed || !fading) return;
            float progress = Math.min(1f,
                    (SystemClock.uptimeMillis() - fadeStarted) / (float) FADE_MS);
            // Manual timing deliberately remains independent of Android animator scale.
            incomingView.setAlpha(progress * progress * (3f - 2f * progress));
            if (progress >= 1f) finishFade();
            else main.postDelayed(this, 16L);
        }
    };

    public WallpaperController(Activity activity, FrameLayout backgroundContainer) {
        this.activity = activity;
        this.container = backgroundContainer;
        preferences = activity.getSharedPreferences("minimal_tv_wallpaper", 0);
        currentFilename = preferences.getString("current_filename", "");
        lastSwitch = preferences.getLong("last_switch_epoch_ms", 0L);
        currentView = newImageView();
        incomingView = newImageView();
        incomingView.setAlpha(0f);
        container.addView(currentView);
        container.addView(incomingView);
    }

    private ImageView newImageView() {
        ImageView view = new ImageView(activity);
        view.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        view.setScaleType(ImageView.ScaleType.FIT_XY);
        view.setImportantForAccessibility(ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO);
        view.setFocusable(false);
        view.setClickable(false);
        return view;
    }

    public void resume() {
        if (destroyed || active) return;
        active = true;
        if (remainingDelay(System.currentTimeMillis(), lastSwitch) == 0L) pendingAdvance = true;
        if (currentBitmap != null && !pendingAdvance) scheduleRemaining();
        // Scan even if a cache exists: additions and metadata changes take effect on resume.
        refreshImages();
    }

    public void pause() {
        if (destroyed) return;
        active = false;
        cancelWorker();
        main.removeCallbacks(rotation);
        main.removeCallbacks(fadeFrame);
        nextDueUptime = 0L;
        finishFade();
        // Retain a ready cache, but never decode, poll or animate while another app is open.
    }

    public void destroy() {
        if (destroyed) return;
        pause();
        destroyed = true;
        decoder.shutdownNow();
        currentView.setImageDrawable(null);
        incomingView.setImageDrawable(null);
        recycle(currentBitmap);
        recycle(incomingBitmap);
        currentBitmap = null;
        incomingBitmap = null;
        container.removeView(currentView);
        container.removeView(incomingView);
    }

    public void next() {
        Log.i(TAG, "wallpaper next requested: active=" + active + " loading=" + loading
                + " ready=" + (incomingPhoto == null ? "none" : incomingPhoto.name));
        if (!active || destroyed) return;
        if (!pendingAdvance) requestStarted = SystemClock.uptimeMillis();
        pendingAdvance = true;
        if (fading) return; // Coalesce repeated presses into one subsequent transition.
        if (readyCacheIsValid()) {
            startFade();
        } else if (!loading) {
            discardCache();
            refreshImages();
        }
        // An existing decode keeps running; another press must not restart it.
    }

    private void cancelWorker() {
        generation++;
        loading = false;
        if (decodeTask != null) decodeTask.cancel(true);
        decodeTask = null;
    }

    private void refreshImages() {
        if (!active || destroyed || loading || fading) return;
        loading = true;
        final long token = ++generation;
        final String anchor = currentFilename;
        final Photo displayedPhoto = currentPhoto;
        final boolean alreadyDisplaying = currentBitmap != null;
        final boolean advanceInitial = pendingAdvance;
        decodeTask = decoder.submit(() -> {
            ScanResult result = scan(token, anchor, displayedPhoto, alreadyDisplaying, advanceInitial);
            main.post(() -> applyScan(token, result));
        });
    }

    private void applyScan(long token, ScanResult scan) {
        if (!accepts(token)) return;
        directoryPath = scan.directory;
        inventory = scan.files;
        lastError = scan.error;
        if (scan.candidates.isEmpty()) {
            loading = false;
            decodeTask = null;
            discardCache();
            pendingAdvance = false;
            Log.i(TAG, "wallpaper retained; files=" + inventory.size() + " reason=" + lastError);
            schedule(INTERVAL_MS);
            return;
        }
        Photo candidate = scan.candidates.get(0);
        if (currentBitmap != null && incomingBitmap != null
                && candidate.sameAs(incomingPhoto) && readyCacheIsValid()) {
            loading = false;
            decodeTask = null;
            Log.i(TAG, "wallpaper ready: " + candidate.name + " decodeMs=0 reused=true");
            consumeReady();
            return;
        }
        // Release a stale cache before decoding its replacement. The scan phase creates no bitmap.
        discardCache();
        final int width = Math.max(1, container.getWidth() > 0 ? container.getWidth()
                : activity.getResources().getDisplayMetrics().widthPixels);
        final int height = Math.max(1, container.getHeight() > 0 ? container.getHeight()
                : activity.getResources().getDisplayMetrics().heightPixels);
        decodeTask = decoder.submit(() -> {
            LoadResult result = decodeCandidates(token, scan.candidates, width, height);
            main.post(() -> applyDecoded(token, result));
        });
    }

    private void applyDecoded(long token, LoadResult result) {
        if (!accepts(token)) {
            recycle(result.bitmap);
            return;
        }
        loading = false;
        decodeTask = null;
        lastDecodeMs = result.decodeMs;
        lastError = result.error;
        if (result.bitmap == null) {
            pendingAdvance = false;
            Log.i(TAG, "wallpaper retained; files=" + inventory.size() + " reason=" + lastError);
            schedule(INTERVAL_MS);
            return;
        }
        if (currentBitmap == null) {
            boolean preserveClock = !pendingAdvance && result.photo.name.equals(currentFilename)
                    && lastSwitch > 0L;
            currentBitmap = result.bitmap;
            currentPhoto = result.photo;
            currentFilename = result.photo.name;
            if (!preserveClock) lastSwitch = System.currentTimeMillis();
            pendingAdvance = false;
            currentView.setImageBitmap(currentBitmap);
            persist();
            Log.i(TAG, "wallpaper shown: " + currentFilename + " decodeMs=" + lastDecodeMs);
            scheduleRemaining();
            refreshImages(); // Decode the successor immediately, while Home is visible.
        } else {
            incomingBitmap = result.bitmap;
            incomingPhoto = result.photo;
            incomingView.setImageBitmap(incomingBitmap);
            incomingView.setAlpha(0f);
            Log.i(TAG, "wallpaper ready: " + incomingPhoto.name + " decodeMs=" + lastDecodeMs
                    + " reused=false");
            consumeReady();
        }
    }

    private boolean accepts(long token) {
        return active && !destroyed && token == generation;
    }

    private void consumeReady() {
        if (pendingAdvance) startFade();
        else if (nextDueUptime == 0L) scheduleRemaining();
    }

    private boolean readyCacheIsValid() {
        return !fading && incomingBitmap != null && incomingPhoto != null
                && incomingPhoto.fileUnchanged();
    }

    private void discardCache() {
        if (fading) return;
        incomingView.setImageDrawable(null);
        incomingView.setAlpha(0f);
        recycle(incomingBitmap);
        incomingBitmap = null;
        incomingPhoto = null;
    }

    private void startFade() {
        if (!active || destroyed || fading || currentBitmap == null || incomingBitmap == null) return;
        cancelWorker();
        main.removeCallbacks(rotation);
        nextDueUptime = 0L;
        pendingAdvance = false;
        incomingSwitch = System.currentTimeMillis();
        fadeStarted = SystemClock.uptimeMillis();
        fading = true;
        incomingView.setAlpha(0f);
        incomingView.bringToFront();
        Log.i(TAG, "wallpaper crossfade: " + currentFilename + " -> " + incomingPhoto.name
                + " requestDelayMs=" + (requestStarted == 0L ? -1L : fadeStarted - requestStarted));
        requestStarted = 0L;
        main.post(fadeFrame);
    }

    private void finishFade() {
        main.removeCallbacks(fadeFrame);
        if (!fading) return;
        Bitmap previousBitmap = currentBitmap;
        Photo previousPhoto = currentPhoto;
        ImageView previousView = currentView;
        currentView = incomingView;
        incomingView = previousView;
        currentBitmap = incomingBitmap;
        currentPhoto = incomingPhoto;
        currentFilename = currentPhoto.name;
        lastSwitch = incomingSwitch;
        incomingBitmap = null;
        incomingPhoto = null;
        fading = false;
        currentView.setAlpha(1f);
        incomingView.setAlpha(0f);
        if (canReusePrevious(previousPhoto)) {
            incomingBitmap = previousBitmap;
            incomingPhoto = previousPhoto;
            // The old current view already holds this bitmap and remains transparent.
            Log.i(TAG, "wallpaper ready: " + incomingPhoto.name + " decodeMs=0 reused=true");
        } else {
            incomingView.setImageDrawable(null);
            recycle(previousBitmap);
        }
        persist();
        Log.i(TAG, "wallpaper shown: " + currentFilename);
        if (!active || destroyed) return;
        scheduleRemaining();
        if (pendingAdvance && readyCacheIsValid()) startFade();
        else if (incomingBitmap == null || pendingAdvance) refreshImages();
    }

    private boolean canReusePrevious(Photo previous) {
        if (inventory.size() != 2 || previous == null || currentPhoto == null) return false;
        if (!previous.fileUnchanged() || !currentPhoto.fileUnchanged()) return false;
        boolean containsPrevious = false;
        boolean containsCurrent = false;
        for (Photo photo : inventory) {
            containsPrevious |= photo.sameAs(previous);
            containsCurrent |= photo.sameAs(currentPhoto);
        }
        return containsPrevious && containsCurrent && !previous.sameAs(currentPhoto);
    }

    private void persist() {
        preferences.edit().putString("current_filename", currentFilename)
                .putLong("last_switch_epoch_ms", lastSwitch).apply();
    }

    private void scheduleRemaining() {
        schedule(remainingDelay(System.currentTimeMillis(), lastSwitch));
    }

    private void schedule(long delay) {
        main.removeCallbacks(rotation);
        if (!active || destroyed) return;
        nextDueUptime = SystemClock.uptimeMillis() + Math.max(0L, delay);
        main.postDelayed(rotation, Math.max(0L, delay));
    }

    static long remainingDelay(long now, long switched) {
        if (switched <= 0L) return 0L;
        if (now < switched) return INTERVAL_MS;
        long elapsed = now - switched;
        return elapsed >= INTERVAL_MS ? 0L : INTERVAL_MS - elapsed;
    }

    static boolean isPhotoName(String name) {
        if (name.startsWith(".")) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".png") || lower.endsWith(".webp");
    }

    private boolean cancelled(long token) {
        return token != generation || Thread.currentThread().isInterrupted();
    }

    private ScanResult scan(long token, String anchor, Photo displayedPhoto,
                            boolean alreadyDisplaying, boolean advanceInitial) {
        ScanResult result = new ScanResult();
        try {
            File directory = activity.getExternalFilesDir("wallpapers");
            result.directory = directory == null ? "" : directory.getAbsolutePath();
            File[] allFiles = directory == null ? null : directory.listFiles();
            if (allFiles != null) {
                for (File file : allFiles) {
                    if (cancelled(token)) return result;
                    if (file.isFile() && isPhotoName(file.getName())) result.files.add(new Photo(file));
                }
            }
            Collections.sort(result.files, Comparator.comparing(photo -> photo.name));
            StringBuilder signature = new StringBuilder();
            int anchorIndex = -1;
            for (int i = 0; i < result.files.size(); i++) {
                Photo photo = result.files.get(i);
                signature.append(photo.fingerprint()).append('\n');
                if (photo.name.equals(anchor)) anchorIndex = i;
            }
            if (!signature.toString().equals(workerListSignature)) {
                workerListSignature = signature.toString();
                workerBadFiles.clear();
            }
            if (result.files.isEmpty()) {
                result.error = "no photos";
                return result;
            }
            boolean advance = alreadyDisplaying || advanceInitial;
            int start = anchorIndex < 0 ? 0 : (anchorIndex + (advance ? 1 : 0)) % result.files.size();
            for (int offset = 0; offset < result.files.size(); offset++) {
                Photo photo = result.files.get((start + offset) % result.files.size());
                if (workerBadFiles.contains(photo.fingerprint())) continue;
                if (alreadyDisplaying && photo.sameAs(displayedPhoto)) continue;
                result.candidates.add(photo);
            }
            if (result.candidates.isEmpty()) result.error = "no other readable photo";
        } catch (RuntimeException error) {
            result.error = error.getClass().getSimpleName();
        }
        return result;
    }

    private LoadResult decodeCandidates(long token, List<Photo> candidates, int width, int height) {
        LoadResult result = new LoadResult();
        long started = SystemClock.elapsedRealtime();
        for (Photo photo : candidates) {
            if (cancelled(token)) return result;
            try {
                Bitmap bitmap = decodePhoto(photo.file, width, height, token);
                if (cancelled(token)) {
                    recycle(bitmap);
                    return result;
                }
                if (!photo.fileUnchanged()) {
                    recycle(bitmap);
                    throw new IOException("Photo changed during decode");
                }
                result.bitmap = bitmap;
                result.photo = photo;
                result.decodeMs = SystemClock.elapsedRealtime() - started;
                return result;
            } catch (IOException | RuntimeException | OutOfMemoryError error) {
                if (cancelled(token)) return result;
                workerBadFiles.add(photo.fingerprint());
                Log.w(TAG, "wallpaper skipped: " + photo.name + " ("
                        + error.getClass().getSimpleName() + ")");
            }
        }
        result.decodeMs = SystemClock.elapsedRealtime() - started;
        result.error = "no other readable photo";
        return result;
    }

    private Bitmap decodePhoto(File file, int width, int height, long token) throws IOException {
        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try {
            orientation = new ExifInterface(file.getAbsolutePath()).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (IOException ignored) {
            // A readable image without EXIF is still a usable wallpaper.
        }
        boolean swapsAxes = orientation >= 5 && orientation <= 8;
        int rawTargetWidth = swapsAxes ? height : width;
        int rawTargetHeight = swapsAxes ? width : height;
        BitmapRegionDecoder regionDecoder = null;
        Bitmap decoded = null;
        Bitmap output = null;
        try {
            regionDecoder = BitmapRegionDecoder.newInstance(file.getAbsolutePath(), false);
            if (regionDecoder == null) throw new IOException("Unsupported photo");
            int sourceWidth = regionDecoder.getWidth();
            int sourceHeight = regionDecoder.getHeight();
            Rect crop = centerCrop(sourceWidth, sourceHeight, rawTargetWidth, rawTargetHeight);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            options.inSampleSize = sampleSize(crop.width(), crop.height(), rawTargetWidth, rawTargetHeight);
            if (cancelled(token)) throw new IOException("Cancelled");
            decoded = regionDecoder.decodeRegion(crop, options);
            if (decoded == null) throw new IOException("Unreadable photo");
            if (cancelled(token)) throw new IOException("Cancelled");
            output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(output);
            canvas.drawColor(Color.BLACK);
            Matrix matrix = orientationMatrix(orientation);
            RectF bounds = new RectF(0, 0, decoded.getWidth(), decoded.getHeight());
            matrix.mapRect(bounds);
            matrix.postTranslate(-bounds.left, -bounds.top);
            float scale = Math.max(width / bounds.width(), height / bounds.height());
            matrix.postScale(scale, scale);
            matrix.postTranslate((width - bounds.width() * scale) / 2f,
                    (height - bounds.height() * scale) / 2f);
            canvas.drawBitmap(decoded, matrix, new Paint(Paint.FILTER_BITMAP_FLAG));
            Bitmap ready = output;
            output = null;
            return ready;
        } finally {
            recycle(decoded);
            recycle(output);
            if (regionDecoder != null) regionDecoder.recycle();
        }
    }

    static Rect centerCrop(int width, int height, int targetWidth, int targetHeight) {
        int cropWidth = width;
        int cropHeight = height;
        if ((long) width * targetHeight > (long) height * targetWidth) {
            cropWidth = Math.max(1, (int) ((long) height * targetWidth / targetHeight));
        } else {
            cropHeight = Math.max(1, (int) ((long) width * targetHeight / targetWidth));
        }
        int left = (width - cropWidth) / 2;
        int top = (height - cropHeight) / 2;
        return new Rect(left, top, left + cropWidth, top + cropHeight);
    }

    static int sampleSize(int width, int height, int targetWidth, int targetHeight) {
        int sample = 1;
        while (width / (sample * 2L) >= targetWidth && height / (sample * 2L) >= targetHeight) {
            sample *= 2;
        }
        return sample;
    }

    private static Matrix orientationMatrix(int orientation) {
        Matrix matrix = new Matrix();
        switch (orientation) {
            case 2: matrix.setScale(-1f, 1f); break;
            case 3: matrix.setRotate(180f); break;
            case 4: matrix.setScale(1f, -1f); break;
            case 5: matrix.setValues(new float[]{0, 1, 0, 1, 0, 0, 0, 0, 1}); break;
            case 6: matrix.setRotate(90f); break;
            case 7: matrix.setValues(new float[]{0, -1, 0, -1, 0, 0, 0, 0, 1}); break;
            case 8: matrix.setRotate(270f); break;
            default: break;
        }
        return matrix;
    }

    public JSONObject describeState() {
        JSONObject state = new JSONObject();
        try {
            state.put("active", active);
            state.put("destroyed", destroyed);
            state.put("loading", loading);
            state.put("directory", directoryPath);
            state.put("photo_count", inventory.size());
            state.put("current_filename", currentFilename);
            state.put("last_switch_epoch_ms", lastSwitch);
            state.put("rotation_interval_ms", INTERVAL_MS);
            state.put("next_due_in_ms", nextDueUptime == 0L ? -1L
                    : Math.max(0L, nextDueUptime - SystemClock.uptimeMillis()));
            state.put("crossfading", fading);
            state.put("incoming_filename", fading && incomingPhoto != null ? incomingPhoto.name : JSONObject.NULL);
            state.put("ready_filename", !fading && incomingPhoto != null ? incomingPhoto.name : JSONObject.NULL);
            state.put("pending_advance", pendingAdvance);
            state.put("last_decode_ms", lastDecodeMs);
            state.put("bitmap_bytes", (currentBitmap == null ? 0L : currentBitmap.getAllocationByteCount())
                    + (incomingBitmap == null ? 0L : incomingBitmap.getAllocationByteCount()));
            state.put("last_error", lastError);
        } catch (org.json.JSONException impossible) {
            Log.w(TAG, "wallpaper status unavailable", impossible);
        }
        return state;
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
    }

    private static final class Photo {
        final File file;
        final String name;
        final long length;
        final long modified;

        Photo(File file) {
            this.file = file;
            name = file.getName();
            length = file.length();
            modified = file.lastModified();
        }

        boolean sameAs(Photo other) {
            return other != null && name.equals(other.name) && length == other.length
                    && modified == other.modified;
        }

        boolean fileUnchanged() {
            return file.isFile() && file.length() == length && file.lastModified() == modified;
        }

        String fingerprint() { return name + "\0" + length + ":" + modified; }
    }

    private static final class ScanResult {
        final List<Photo> files = new ArrayList<>();
        final List<Photo> candidates = new ArrayList<>();
        String directory = "";
        String error = "";
    }

    private static final class LoadResult {
        Bitmap bitmap;
        Photo photo;
        long decodeMs;
        String error = "";
    }
}
