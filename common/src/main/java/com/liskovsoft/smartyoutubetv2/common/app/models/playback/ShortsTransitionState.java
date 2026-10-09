package com.liskovsoft.smartyoutubetv2.common.app.models.playback;

/**
 * Shared state between the video loader (common module) and the playback UI (tv module)
 * used to animate transitions between Shorts.
 */
public final class ShortsTransitionState {
    public static final int DIRECTION_NONE = 0;
    public static final int DIRECTION_NEXT = 1;
    public static final int DIRECTION_PREVIOUS = -1;

    private static volatile int sDirection = DIRECTION_NONE;
    private static volatile boolean sIsShortsMode;
    private static volatile boolean sIsLive;

    private ShortsTransitionState() {
    }

    /**
     * True while a Short is loaded in the player (read from the playback thread).
     */
    public static boolean isShortsMode() {
        return sIsShortsMode;
    }

    public static void setShortsMode(boolean isShortsMode) {
        sIsShortsMode = isShortsMode;
    }

    /**
     * True while a live stream is loaded in the player (read from the playback thread).
     */
    public static boolean isLive() {
        return sIsLive;
    }

    private static volatile String sResumeVideoId;
    private static volatile long sResumeTimeMs;

    /**
     * JoTube: the Shorts player was left to the sidebar (left key), going back into the Shorts section continues here.
     */
    public static void setResumeVideoId(String videoId) {
        sResumeVideoId = videoId;
        sResumeTimeMs = System.currentTimeMillis();
    }

    /**
     * Only right after leaving the player (the screen below might not be the browse screen).
     */
    public static String consumeResumeVideoId() {
        String videoId = sResumeVideoId;
        sResumeVideoId = null;
        return System.currentTimeMillis() - sResumeTimeMs < 5_000 ? videoId : null;
    }

    public static void setLive(boolean isLive) {
        sIsLive = isLive;
    }

    public static void setDirection(int direction) {
        sDirection = direction;
    }

    /**
     * Vertical (original aspect ratio) thumbnail. Exists for Shorts.
     */
    public static String getThumbnailUrl(String videoId) {
        return videoId != null ? "https://i.ytimg.com/vi/" + videoId + "/oardefault.jpg" : null;
    }

    /**
     * Returns the pending direction and resets it, so it's applied only once.
     */
    public static int consumeDirection() {
        int direction = sDirection;
        sDirection = DIRECTION_NONE;
        return direction;
    }
}
