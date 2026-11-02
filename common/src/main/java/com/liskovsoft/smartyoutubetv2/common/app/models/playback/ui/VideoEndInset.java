package com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui;

/**
 * Side panels (e.g. comments) open in a separate dialog activity on top of the player.
 * The panel reports its width here and the player leaves that room on the right, so the picture isn't hidden behind it.
 */
public final class VideoEndInset {
    public interface Listener {
        void onVideoEndInset(int px);
    }

    private static int sInset;
    private static Listener sListener;

    private VideoEndInset() {
    }

    public static void set(int px) {
        px = Math.max(0, px);
        if (px == sInset) {
            return;
        }
        sInset = px;
        if (sListener != null) {
            sListener.onVideoEndInset(px);
        }
    }

    /** Only removes the listener if it is still the current one (a new player may already have taken over). */
    public static void removeListener(Listener listener) {
        if (sListener == listener) {
            sListener = null;
        }
    }

    public static void setListener(Listener listener) {
        sListener = listener;
        if (listener != null) {
            listener.onVideoEndInset(sInset);
        }
    }
}
