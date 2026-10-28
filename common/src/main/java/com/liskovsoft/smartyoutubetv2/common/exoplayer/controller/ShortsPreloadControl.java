package com.liskovsoft.smartyoutubetv2.common.exoplayer.controller;

import androidx.media3.exoplayer.source.preload.DefaultPreloadManager.PreloadStatus;
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl;

import android.content.Context;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.other.Ablation;

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

    private final Context mContext;
    // JoTube: the spare player prepares the next Short itself (its own source: the preloaded ones play only on the
    // preload player's thread). Preloading the Shorts ahead too downloaded every start chunk twice (device log
    // 2026-10-28 16:26: 3-5 start chunks of 1-2 MB per swipe; the spare's took up to 2.3 s). Word "pmnext": old behaviour.
    private static volatile boolean sSpareCoversNext;

    public static void setSpareCoversNext(boolean covers) {
        sSpareCoversNext = covers;
    }

    public ShortsPreloadControl(Context context) {
        mContext = context;
    }

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

    /**
     * Preload order (lower first): next, the one after, the previous one, then the rest by distance.
     * Keys can't be compared directly: ties and midpoint inserts would give an arbitrary order.
     */
    public int getPriority(int key) {
        State state = mState;

        for (int i = 0; i < state.keys.length; i++) {
            if (state.keys[i] == key) {
                int distance = i - state.current;
                return distance > 0 ? distance : distance == 0 ? 0 : (distance == -1 ? 3 : 100 - distance);
            }
        }

        return Integer.MAX_VALUE;
    }

    @Override
    public PreloadStatus getTargetPreloadStatus(Integer key) {
        if (Ablation.has(mContext, "nopm")) {
            return PreloadStatus.PRELOAD_STATUS_NOT_PRELOADED;
        }
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

        // With the spare player nothing is preloaded: the playing Short and the previous one were downloaded again by the
        // preload manager too (its sources play only on one of the two players): log 2026-10-28 16:48, 3 start chunks per swipe
        if (sSpareCoversNext && index != state.current && !Ablation.has(mContext, "pmnext")) {
            return PreloadStatus.PRELOAD_STATUS_NOT_PRELOADED;
        }

        // Default start position (no start given): the player starts a Short there, so the preloaded period is reused
        switch (index - state.current) {
            case 1:
                return PreloadStatus.specifiedRangeLoaded(NEXT_PRELOAD_MS);
            case 2:
                return PreloadStatus.specifiedRangeLoaded(NEXT_2_PRELOAD_MS);
            case 0:
                // The playing Short: anything but NOT_PRELOADED, which would clear what was preloaded for it
                // before the player takes the period over (review: the switch updates this before setMediaSource)
                return PreloadStatus.specifiedRangeLoaded(NEXT_PRELOAD_MS);
            case -1:
                return PreloadStatus.specifiedRangeLoaded(PREVIOUS_PRELOAD_MS);
            default:
                return PreloadStatus.PRELOAD_STATUS_NOT_PRELOADED;
        }
    }
}
