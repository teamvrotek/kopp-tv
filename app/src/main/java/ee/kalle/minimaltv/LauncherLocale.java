package ee.kalle.minimaltv;

import android.content.Context;
import android.content.res.Configuration;
import java.util.Locale;

/** Locale overrides are confined to this app's contexts. */
public final class LauncherLocale {
    private LauncherLocale() { }

    public static Context wrap(Context context) {
        String tag = new UserSettings(context).languageTag();
        if (tag.isEmpty()) return context;
        Configuration configuration = new Configuration(context.getResources().getConfiguration());
        Locale locale = Locale.forLanguageTag(tag);
        configuration.setLocale(locale);
        configuration.setLayoutDirection(locale);
        return context.createConfigurationContext(configuration);
    }

    public static Locale locale(Context context) {
        String tag = new UserSettings(context).languageTag();
        if (!tag.isEmpty()) return Locale.forLanguageTag(tag);
        return context.getResources().getConfiguration().getLocales().get(0);
    }
}
