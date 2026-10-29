package org.jotube.keys;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Remaps the branded buttons of the Google TV remote (keylayout Vendor_18d1_Product_9451.kl on the Streamer:
 * YouTube = BUTTON_3, Netflix = BUTTON_4). The key is consumed (the system doesn't open YouTube / Netflix) and the
 * mapped app is started on key up.
 */
public class KeyService extends AccessibilityService {
    private static final String TAG = "JoKeys";
    static final String JOTUBE = "org.smarttube.johan.media3";
    static final String PLEX = "com.plexapp.android";

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        String target = targetFor(event.getKeyCode());

        if (target == null) {
            return false; // every other key goes on unchanged
        }

        if (event.getAction() == KeyEvent.ACTION_UP && event.getRepeatCount() == 0) {
            launch(target);
        }

        return true; // down and up: consumed, so the system's own app isn't opened
    }

    private static String targetFor(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_3: // YouTube
                return JOTUBE;
            case KeyEvent.KEYCODE_BUTTON_4: // Netflix
                return PLEX;
            default:
                return null;
        }
    }

    private void launch(String packageName) {
        Intent intent = getPackageManager().getLeanbackLaunchIntentForPackage(packageName);

        if (intent == null) {
            intent = getPackageManager().getLaunchIntentForPackage(packageName);
        }

        if (intent == null) {
            Log.w(TAG, "Not installed: " + packageName);
            return;
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);

        try {
            startActivity(intent);
            Log.d(TAG, "Started " + packageName);
        } catch (RuntimeException e) {
            Log.e(TAG, "Can't start " + packageName + ": " + e);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }
}
