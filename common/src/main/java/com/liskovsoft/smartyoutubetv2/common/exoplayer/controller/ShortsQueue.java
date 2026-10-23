package com.liskovsoft.smartyoutubetv2.common.exoplayer.controller;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager;
import androidx.media3.exoplayer.source.preload.PreloadException;
import androidx.media3.exoplayer.source.preload.PreloadManagerListener;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * JoTube M3 (stage 4): Shorts are preloaded by a {@link DefaultPreloadManager}. Every queued Short is a source in the
 * manager; the player plays one Short at a time (a source taken from the manager), so there is no playlist and no
 * timeline to keep in sync. Switching to a queued Short = playing its (preloaded) source.
 *
 * The entry list is the single source of truth and is kept in feed order. Entries are never removed from the list during
 * a session (indices stay valid); Shorts more than KEEP_BEHIND behind the current one are only taken out of the manager.
 * The manager ranks a Short by a stable key (order preserving, with gaps for insertions).
 *
 * Only accessed from the main thread.
 */
public final class ShortsQueue {
    private static final int MAX_SIZE = 50;
    private static final int KEEP_BEHIND = 2;
    private static final int KEY_GAP = 1 << 16;
    // Stream urls are valid for ~6 h, the poToken ~12 h. Stay well below.
    public static final long FORMAT_INFO_MAX_AGE_MS = 60 * 60 * 1000;
    private static volatile ShortsQueue sActive;
    private static volatile Runnable sChangeListener; // JoTube: the preview player follows the queue

    private static final class Entry {
        final String videoId;
        final MediaItemFormatInfo formatInfo;
        final long addedTimeMs = System.currentTimeMillis();
        final MediaItem mediaItem;
        final int key;
        volatile boolean failed; // the preload failed (403, expired poToken...): swipe to it with a normal fetch
        boolean removed; // taken out of the preload manager (far behind the current Short): swipe to it with a normal fetch

        Entry(MediaItemFormatInfo formatInfo, MediaSource source, int key) {
            this.videoId = formatInfo.getVideoId();
            this.formatInfo = formatInfo;
            this.mediaItem = source.getMediaItem();
            this.key = key;
        }
    }

    private final DefaultPreloadManager mPreloadManager;
    private final ShortsPreloadControl mPreloadControl;
    private final List<Entry> mEntries = new ArrayList<>();
    private int mCurrentIndex;
    private boolean mReleased;
    // The first Short of a queue is still starting: the manager doesn't preload the next ones yet (they would share the
    // network with the first segments of the Short the user waits for)
    private boolean mPreloadHeld = true;
    private final PreloadManagerListener mPreloadListener;

    ShortsQueue(DefaultPreloadManager preloadManager, ShortsPreloadControl preloadControl, MediaItemFormatInfo formatInfo, MediaSource first) {
        mPreloadManager = preloadManager;
        mPreloadControl = preloadControl;
        // A failed preload never reaches the player's error callback: remember it so the swipe doesn't play a broken source
        mPreloadListener = new PreloadManagerListener() {
            @Override
            public void onError(PreloadException exception) {
                for (Entry e : mEntries) {
                    if (e.mediaItem.equals(exception.mediaItem)) {
                        e.failed = true;
                    }
                }
            }
        };
        mPreloadManager.addListener(mPreloadListener);
        Entry entry = new Entry(formatInfo, first, 0);
        mEntries.add(entry);
        mCurrentIndex = 0;
        publish();
        mPreloadManager.add(first, entry.key);
        mPreloadManager.setCurrentPlayingIndex(entry.key);
    }

    /**
     * The source to play for the Short (the preloaded one if the manager has loaded it already).
     */
    @Nullable
    MediaSource getMediaSource(int index) {
        return index >= 0 && index < mEntries.size() && !mReleased && !mEntries.get(index).removed ?
                mPreloadManager.getMediaSource(mEntries.get(index).mediaItem) : null;
    }

    public int indexOf(String videoId) {
        if (videoId == null) {
            return -1;
        }

        for (int i = 0; i < mEntries.size(); i++) {
            if (videoId.equals(mEntries.get(i).videoId)) {
                return i;
            }
        }

        return -1;
    }

    int size() {
        return mEntries.size();
    }

    boolean contains(String videoId) {
        return indexOf(videoId) != -1;
    }

    int getCurrentIndex() {
        return mCurrentIndex;
    }

    String getVideoId(int index) {
        return index >= 0 && index < mEntries.size() ? mEntries.get(index).videoId : null;
    }

    public String getCurrentVideoId() {
        return getVideoId(mCurrentIndex);
    }

    void setCurrentIndex(int index) {
        mCurrentIndex = index;

        if (!mReleased && index >= 0 && index < mEntries.size()) {
            publish();
            notifyChanged();
            removeFarBehind(index);
            mPreloadManager.setCurrentPlayingIndex(mEntries.get(index).key);
            // JoTube: the neighbours are preloaded once the new Short plays (READY: releasePreloadHold), not during the
            // switch: the preload manager works on the playback thread (the player's looper) and its track selections
            // and loads delayed the switch (device log 2026-10-23: renderers enabled +180..300 ms after setMediaSource,
            // first frame +357..605 ms). Failsafe: released after PRELOAD_HOLD_MAX_MS anyway.
            mPreloadHeld = true;
            com.liskovsoft.smartyoutubetv2.common.utils.Utils.removeCallbacks(mReleaseHold);
            com.liskovsoft.smartyoutubetv2.common.utils.Utils.postDelayed(mReleaseHold, PRELOAD_HOLD_MAX_MS);
        }
    }

    private static final long PRELOAD_HOLD_MAX_MS = 1_500;
    private final Runnable mReleaseHold = this::releasePreloadHold;

    // Bounds the memory of a long session: a played Short far behind isn't swiped back to
    private void removeFarBehind(int current) {
        for (int i = 0; i < current - KEEP_BEHIND; i++) {
            Entry entry = mEntries.get(i);

            if (!entry.removed) {
                entry.removed = true;
                mPreloadManager.remove(entry.mediaItem);
            }
        }
    }

    boolean isFull() {
        return mEntries.size() >= MAX_SIZE;
    }

    /**
     * The stream urls of the item are still valid.
     */
    boolean isActual(int index) {
        Entry entry = index >= 0 && index < mEntries.size() ? mEntries.get(index) : null;
        return entry != null && !entry.failed && !entry.removed && entry.formatInfo.isCacheActual() &&
                System.currentTimeMillis() - entry.addedTimeMs < FORMAT_INFO_MAX_AGE_MS;
    }

    /**
     * Insert at the given distance after the current item (1 = plays next), keeping the feed order.
     *
     * @return false if the Short couldn't be queued
     */
    boolean insertAt(int distance, MediaItemFormatInfo formatInfo, MediaSource source) {
        if (mReleased) {
            return false;
        }

        int index = Math.min(mCurrentIndex + Math.max(1, distance), mEntries.size());
        int before = index > 0 ? mEntries.get(index - 1).key : -KEY_GAP;
        int key;

        if (index == mEntries.size()) {
            key = before + KEY_GAP;
        } else {
            int after = mEntries.get(index).key;

            if (after - before < 2) {
                return false; // no gap left between the neighbours (very rare: ~16 inserts into the same gap)
            }

            key = before + (after - before) / 2;
        }

        mEntries.add(index, new Entry(formatInfo, source, key));
        publish();
        notifyChanged();
        mPreloadManager.add(source, key);

        if (!mPreloadHeld) {
            mPreloadManager.invalidate();
        }

        return true;
    }

    /**
     * The playing Short has started (ready): the manager may preload the queued ones now.
     */
    void releasePreloadHold() {
        com.liskovsoft.smartyoutubetv2.common.utils.Utils.removeCallbacks(mReleaseHold);

        if (!mPreloadHeld) {
            return;
        }

        mPreloadHeld = false;

        if (!mReleased && mEntries.size() > 1) {
            mPreloadManager.invalidate();
        }
    }

    public static void setChangeListener(@Nullable Runnable listener) {
        sChangeListener = listener;
    }

    private static void notifyChanged() {
        Runnable listener = sChangeListener;

        if (listener != null) {
            listener.run();
        }
    }

    /**
     * JoTube: format info of the Short that plays after the current one (null if none or its urls are stale).
     */
    public static MediaItemFormatInfo getNextFormatInfo() {
        ShortsQueue queue = sActive;

        if (queue == null || queue.mReleased) {
            return null;
        }

        int index = queue.mCurrentIndex + 1;

        return queue.isActual(index) ? queue.mEntries.get(index).formatInfo : null;
    }

    private void publish() {
        int[] keys = new int[mEntries.size()];

        for (int i = 0; i < keys.length; i++) {
            keys[i] = mEntries.get(i).key;
        }

        mPreloadControl.update(keys, mCurrentIndex);
    }

    private void release() {
        com.liskovsoft.smartyoutubetv2.common.utils.Utils.removeCallbacks(mReleaseHold);
        if (!mReleased) {
            mReleased = true;
            mPreloadManager.removeListener(mPreloadListener); // the manager outlives the queue
            mPreloadControl.update(new int[0], -1);
            mPreloadManager.reset();
        }
    }

    /**
     * Makes the queue the active one; the previous one is released (its preloaded sources are dropped).
     */
    static void setActive(@Nullable ShortsQueue queue) {
        ShortsQueue old = sActive;
        sActive = queue;

        if (old != null && old != queue) {
            old.release();
        }
    }

    public static ShortsQueue getActive() {
        return sActive;
    }

    /**
     * Used by the UI to skip the "black screen" reset when the video is already in the queue.
     */
    public static boolean isQueued(String videoId) {
        ShortsQueue queue = sActive;
        return queue != null && queue.indexOf(videoId) != -1 && queue.indexOf(videoId) != queue.getCurrentIndex();
    }

    /**
     * JoTube: ids of the queued Shorts after the current one (for preloading their images).
     */
    public static List<String> getUpcomingIds(int max) {
        ShortsQueue queue = sActive;
        List<String> result = new ArrayList<>();

        if (queue == null) {
            return result;
        }

        for (int i = queue.mCurrentIndex + 1; i < queue.mEntries.size() && result.size() < max; i++) {
            result.add(queue.mEntries.get(i).videoId);
        }

        return result;
    }

    /**
     * JoTube: false if the video is in the queue but the player is still on another item (a frame of the
     * previous Short, rendered before the switch to this one was made).
     *
     * @param playing the media item the player plays now
     */
    public static boolean isPlayerOn(String videoId, @Nullable MediaItem playing) {
        ShortsQueue queue = sActive;
        int index = queue != null ? queue.indexOf(videoId) : -1;
        return index == -1 || (playing != null && queue.mEntries.get(index).mediaItem.mediaId.equals(playing.mediaId));
    }

    /**
     * JoTube: the queued stream data of this Short must not be reused (e.g. the SABR server asked for a new player
     * response): the next load of it fetches the format info again, like after a failed preload.
     */
    public static void invalidate(String videoId) {
        ShortsQueue queue = sActive;
        int index = queue != null ? queue.indexOf(videoId) : -1;

        if (index != -1) {
            queue.mEntries.get(index).failed = true;
        }
    }

    /**
     * Format info of a Short that is already queued (e.g. the previous one), so going back
     * doesn't wait for the network. Null if the video isn't queued (or it's the current one).
     */
    public static MediaItemFormatInfo getQueuedFormatInfo(String videoId) {
        ShortsQueue queue = sActive;

        if (queue == null || !isQueued(videoId)) {
            return null;
        }

        int index = queue.indexOf(videoId);

        return queue.isActual(index) ? queue.mEntries.get(index).formatInfo : null;
    }
}
