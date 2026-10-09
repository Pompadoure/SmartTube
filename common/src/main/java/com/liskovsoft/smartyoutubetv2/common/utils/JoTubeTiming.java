package com.liskovsoft.smartyoutubetv2.common.utils;

import android.os.SystemClock;

import com.liskovsoft.sharedutils.mylogger.Log;

/**
 * JoTube: timing log of a regular video start, every step with the ms since the click (tag "JoTubeTiming").
 */
public final class JoTubeTiming {
    private static final String TAG = "JoTubeTiming";
    private static volatile long sClickMs; // 0: no start is being measured

    private JoTubeTiming() {
    }

    public static void click(String videoId) {
        sClickMs = SystemClock.uptimeMillis();
        Log.d(TAG, "click: %s", videoId);
    }

    public static void mark(String step) {
        long clickMs = sClickMs;

        if (clickMs != 0) {
            Log.d(TAG, "%s: +%s ms", step, SystemClock.uptimeMillis() - clickMs);
        }
    }

    public static void markFirstFrame() {
        mark("first frame");
        sClickMs = 0;
    }

    public static void cancel() {
        sClickMs = 0;
    }
}
