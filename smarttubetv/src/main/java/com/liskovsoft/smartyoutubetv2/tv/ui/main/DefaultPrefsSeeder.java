package com.liskovsoft.smartyoutubetv2.tv.ui.main;

import android.content.Context;
import android.content.res.AssetManager;

import com.liskovsoft.sharedutils.mylogger.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Personal builds can ship their own default settings in assets/default_prefs/&lt;prefs dir&gt;/&lt;key&gt;
 * (same layout as files/ in a backup). A file is copied only when it doesn't exist yet,
 * so the user's own changes are never overwritten.
 */
public final class DefaultPrefsSeeder {
    private static final String TAG = DefaultPrefsSeeder.class.getSimpleName();
    private static final String ASSETS_ROOT = "default_prefs";

    private DefaultPrefsSeeder() {
    }

    public static void seed(Context context) {
        if (context == null) {
            return;
        }

        AssetManager assets = context.getAssets();

        try {
            String[] dirs = assets.list(ASSETS_ROOT);

            if (dirs == null) {
                return;
            }

            for (String dir : dirs) {
                String[] keys = assets.list(ASSETS_ROOT + "/" + dir);

                if (keys == null) {
                    continue;
                }

                File targetDir = new File(context.getFilesDir(), dir);

                for (String key : keys) {
                    File target = new File(targetDir, key);

                    if (target.exists()) {
                        continue;
                    }

                    if (!targetDir.exists() && !targetDir.mkdirs()) {
                        Log.e(TAG, "Can't create %s", targetDir);
                        return;
                    }

                    copy(assets, ASSETS_ROOT + "/" + dir + "/" + key, target);
                    Log.d(TAG, "Default prefs applied: %s/%s", dir, key);
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Can't apply default prefs: %s", e.getMessage());
        }
    }

    private static void copy(AssetManager assets, String assetPath, File target) throws IOException {
        // NOTE: no try-with-resources (minSdk 17)
        InputStream in = null;
        OutputStream out = null;
        try {
            in = assets.open(assetPath);
            out = new FileOutputStream(target);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        } finally {
            if (in != null) {
                try { in.close(); } catch (IOException ignored) { }
            }
            if (out != null) {
                try { out.close(); } catch (IOException ignored) { }
            }
        }
    }
}
