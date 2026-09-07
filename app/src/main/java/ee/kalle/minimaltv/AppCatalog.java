package ee.kalle.minimaltv;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.text.Collator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/** Discovers TV launch activities through the manifest's scoped intent query. */
public final class AppCatalog {
    private AppCatalog() {}

    public static final class Entry {
        public final String packageName;
        public final String label;
        private final ComponentName activity;

        private Entry(String packageName, String label, ComponentName activity) {
            this.packageName = packageName;
            this.label = label;
            this.activity = activity;
        }

        public Drawable loadArtwork(Context context) {
            PackageManager pm = context.getPackageManager();
            try {
                Drawable image = pm.getActivityInfo(activity, 0).loadBanner(pm);
                if (image != null) return image;
            } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
                // A package can change between discovery and display.
            }
            try {
                Drawable image = pm.getApplicationBanner(packageName);
                if (image != null) return image;
                return pm.getApplicationIcon(packageName);
            } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
                return null;
            }
        }
    }

    public static List<Entry> discover(Context context) {
        List<Entry> result = query(context, null);
        Locale locale = context.getResources().getConfiguration().getLocales().get(0);
        Collator collator = Collator.getInstance(locale);
        collator.setStrength(Collator.PRIMARY);
        result.sort((first, second) -> {
            int labels = collator.compare(first.label, second.label);
            return labels != 0 ? labels : first.packageName.compareTo(second.packageName);
        });
        return result;
    }

    public static Entry find(Context context, String packageName) {
        if (packageName == null || packageName.isEmpty()
            || context.getPackageName().equals(packageName)) return null;
        List<Entry> matches = query(context, packageName);
        return matches.isEmpty() ? null : matches.get(0);
    }

    private static List<Entry> query(Context context, String packageName) {
        PackageManager pm = context.getPackageManager();
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        if (packageName != null) intent.setPackage(packageName);
        LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
        for (ResolveInfo resolved : pm.queryIntentActivities(intent, 0)) {
            ActivityInfo info = resolved.activityInfo;
            if (info == null || !info.enabled || !info.exported || info.applicationInfo == null
                || !info.applicationInfo.enabled || context.getPackageName().equals(info.packageName)
                || entries.containsKey(info.packageName)) continue;
            CharSequence title = resolved.loadLabel(pm);
            String label = title == null || title.toString().trim().isEmpty()
                ? info.packageName : title.toString();
            entries.put(info.packageName, new Entry(info.packageName, label,
                new ComponentName(info.packageName, info.name)));
        }
        return new ArrayList<>(entries.values());
    }
}
