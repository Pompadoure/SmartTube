package org.jotube.keys;

import android.content.Context;
import android.content.SharedPreferences;

/** Which app each remapped remote button opens. "" = the button keeps its own app (YouTube / Netflix). */
final class Mapping {
    static final String YOUTUBE = "youtube";
    static final String NETFLIX = "netflix";
    static final String KEEP = "";
    private static final String PREFS = "mapping";

    private Mapping() {
    }

    static String defaultFor(String button) {
        return YOUTUBE.equals(button) ? KeyService.JOTUBE : KeyService.PLEX;
    }

    static String get(Context context, String button) {
        return prefs(context).getString(button, defaultFor(button));
    }

    static void set(Context context, String button, String packageName) {
        prefs(context).edit().putString(button, packageName).apply();
    }

    static void reset(Context context) {
        prefs(context).edit().clear().apply();
    }

    static boolean isDefault(Context context, String button) {
        return defaultFor(button).equals(get(context, button));
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
