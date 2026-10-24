package com.liskovsoft.smartyoutubetv2.common.app.views;

import com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerManager;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.listener.PlayerEventListener;

public interface PlaybackView extends PlayerManager {
    void showProgressBar(boolean show);

    /**
     * JoTube: the Shorts live switch (two players): the view starts playing {@code next} at once (it was prepared in a
     * second player) and slides it in; the core opens it with {@code open} when the slide is over.
     * @return false: the view can't, open it as usual
     */
    default boolean startShortsLiveSwitch(com.liskovsoft.smartyoutubetv2.common.app.models.data.Video next, Runnable open) {
        return false;
    }

    /** JoTube: a live switch is sliding: other swipes wait for it. */
    default boolean isShortsLiveSwitchSliding() {
        return false;
    }
}
