package com.liskovsoft.smartyoutubetv2.common.exoplayer.other;

import android.content.Context;
import com.liskovsoft.sharedutils.mylogger.Log;
import java.io.File;
import java.io.FileInputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class Ablation {
    private static final String TAG = "Ablation";
    private static Set<String> sWords = null;
    private static final Set<String> sLoggedWords = new HashSet<>();

    public static synchronized boolean has(Context ctx, String word) {
        if (sWords == null) {
            sWords = new HashSet<>();
            try {
                File dir = ctx.getExternalFilesDir(null);
                File file = dir != null ? new File(dir, "ablate.txt") : null;
                if (file != null && file.exists()) {
                    try (FileInputStream in = new FileInputStream(file)) {
                        byte[] data = new byte[(int) Math.min(file.length(), 200)];
                        int n = in.read(data);
                        if (n > 0) {
                            String content = new String(data, 0, n).toLowerCase();
                            String[] words = content.split("[\\s,]+");
                            sWords.addAll(Arrays.asList(words));
                            Log.d(TAG, "Ablation: words=" + content);
                        }
                    }
                }
            } catch (Exception e) {
                // Return false on error
            }
        }
        boolean active = sWords.contains(word);
        if (active && sLoggedWords.add(word)) {
            Log.d(TAG, "Ablation: " + word + "=true");
        }
        return active;
    }
}
