package com.liskovsoft.smartyoutubetv2.common.exoplayer.controller;

import androidx.media3.exoplayer.source.preload.DefaultPreloadManager.PreloadStatus;
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl;

/**
 * JoTube M3: how much of each queued Short {@link androidx.media3.exoplayer.source.preload.DefaultPreloadManager}
 * loads in advance. The ranking data of a Short is a stable key (order-preserving, with gaps so a Short can be inserted
 * between two others); {@link ShortsQueue} publishes the keys in feed order and the playing position.
 * Read from the preload thread, written from the main thread: the state is an immutable snapshot.
 */
public final class ShortsPreloadControl implements TargetPreloadStatusControl<Integer, PreloadStatus> {
    public static final long NEXT_PRELOAD_MS = 6_000;
    public static final long NEXT_2_PRELOAD_MS = 3_000;
    public static final long PREVIOUS_PRELOAD_MS = 3_000; // the swipe back

    private static final class State {
        final int[] keys;
        final int current;

        State(int[] keys, int current) {
            this.keys = keys;
            this.current = current;
        }
    }

    private volatile State mState = new State(new int[0], -1);

    void update(int[] keysInFeedOrder, int currentIndex) {
        mState = new State(keysInFeedOrder, currentIndex);
    }

    @Override
    public PreloadStatus getTargetPreloadStatus(Integer key) {
        State state = mState;
        int index = -1;

        for (int i = 0; i < state.keys.length; i++) {
            if (state.keys[i] == key) {
                index = i;
                break;
            }
        }

        if (index == -1 || state.current == -1) {
            return PreloadStatus.PRELOAD_STATUS_NOT_PRELOADED;
        }

        // Default start position (no start given): the player starts a Short there, so the preloaded period is reused
        switch (index - state.current) {
            case 1:
                return PreloadStatus.specifiedRangeLoaded(NEXT_PRELOAD_MS);
            case 2:
                return PreloadStatus.specifiedRangeLoaded(NEXT_2_PRELOAD_MS);
            case -1:
                return PreloadStatus.specifiedRangeLoaded(PREVIOUS_PRELOAD_MS);
            default:
                return PreloadStatus.PRELOAD_STATUS_NOT_PRELOADED;
        }
    }
}
