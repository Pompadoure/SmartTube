package com.liskovsoft.smartyoutubetv2.mobile.ui.playback;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import androidx.annotation.Nullable;
import androidx.media3.common.Player;

import com.liskovsoft.sharedutils.mylogger.Log;

/**
 * Keeps the CPU and Wi-Fi awake for as long as the user wants music to play, also in the gap between two songs.
 * <p>
 * ExoPlayer's own wake mode only holds the locks while the player is READY or BUFFERING. When a song ends the player is
 * ENDED/IDLE until the app has fetched the next song (metadata, format info), and with the screen off the phone may
 * then throttle the network (slow load, UnknownHostException). This lock is held while playWhenReady is true, and it is
 * released at once when the user pauses, when the player is released, or (after a safety timeout) when nothing plays
 * on after the last song.
 */
final class PlaybackNetworkLock {
    private static final String TAG = PlaybackNetworkLock.class.getSimpleName();
    private static final long TIMEOUT_MS = 5 * 60 * 1000; // safety: the lock never outlives this without being renewed
    private static final long RENEW_MS = 2 * 60 * 1000;
    private static final Handler sHandler = new Handler(Looper.getMainLooper());

    private static Player sPlayer;
    private static PowerManager.WakeLock sWakeLock;
    private static WifiManager.WifiLock sWifiLock;

    private static final Runnable sRenew = new Runnable() {
        @Override
        public void run() {
            Player player = sPlayer;
            // The end of the queue (ENDED) and a stuck IDLE player aren't renewed: the lock runs out by itself
            if (player != null && player.getPlayWhenReady() && player.getPlaybackState() != Player.STATE_ENDED && player.getPlaybackState() != Player.STATE_IDLE) {
                acquire();
                sHandler.postDelayed(this, RENEW_MS);
            } else {
                sHandler.postDelayed(sRelease, TIMEOUT_MS);
            }
        }
    };

    // The end of the queue or a stuck player: both locks are given back after the safety timeout
    private static final Runnable sRelease = PlaybackNetworkLock::release;

    private static final Player.Listener sListener = new Player.Listener() {
        @Override
        public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
            update();
        }

        @Override
        public void onPlaybackStateChanged(int playbackState) {
            update();
        }
    };

    private PlaybackNetworkLock() {
    }

    static void attach(Context context, Player player) {
        detach();

        if (context == null || player == null) {
            return;
        }

        Context app = context.getApplicationContext();

        try {
            PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                sWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "JoTube:music");
                sWakeLock.setReferenceCounted(false);
            }
            WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                sWifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "JoTube:music");
                sWifiLock.setReferenceCounted(false);
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "Can't create the locks: %s", e.getMessage());
            return;
        }

        sPlayer = player;
        player.addListener(sListener);
        update();
    }

    static void detach() {
        sHandler.removeCallbacks(sRenew);
        sHandler.removeCallbacks(sRelease);
        Player player = sPlayer;
        sPlayer = null;
        if (player != null) {
            try {
                player.removeListener(sListener);
            } catch (RuntimeException e) {
                // the player is released already
            }
        }
        release();
        sWakeLock = null;
        sWifiLock = null;
    }

    private static void update() {
        Player player = sPlayer;
        sHandler.removeCallbacks(sRenew);
        sHandler.removeCallbacks(sRelease);

        if (player != null && player.getPlayWhenReady() && player.getPlaybackState() != Player.STATE_IDLE) {
            acquire();
            if (player.getPlaybackState() != Player.STATE_ENDED) {
                sHandler.postDelayed(sRenew, RENEW_MS);
            } else {
                sHandler.postDelayed(sRelease, TIMEOUT_MS);
            }
        } else if (player != null && player.getPlayWhenReady()) {
            // IDLE while the user wants playback: the next song is being opened; keep the network awake for a while
            acquire();
            sHandler.postDelayed(sRelease, TIMEOUT_MS);
        } else {
            release();
        }
    }

    private static void acquire() {
        try {
            if (sWakeLock != null) {
                sWakeLock.acquire(TIMEOUT_MS);
            }
            if (sWifiLock != null && !sWifiLock.isHeld()) {
                sWifiLock.acquire();
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "Can't acquire the locks: %s", e.getMessage());
        }
    }

    private static void release() {
        try {
            if (sWakeLock != null && sWakeLock.isHeld()) {
                sWakeLock.release();
            }
            if (sWifiLock != null && sWifiLock.isHeld()) {
                sWifiLock.release();
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "Can't release the locks: %s", e.getMessage());
        }
    }
}
