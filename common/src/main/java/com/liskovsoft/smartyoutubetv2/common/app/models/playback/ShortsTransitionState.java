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

    private ShortsTransitionState() {
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
