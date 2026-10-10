package com.liskovsoft.smartyoutubetv2.common.app.models.playback;

import com.liskovsoft.mediaserviceinterfaces.data.MediaItemMetadata;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JoTube: the metadata (title, channel avatar, likes, suggestions) of the next Short, loaded while the current one
 * plays. The side panel then shows the channel avatar at once, and the Short's own metadata request isn't needed
 * (taken once, then the usual request again).
 */
public final class ShortsMetadataCache {
    private static final int MAX_SIZE = 6;
    private static final long MAX_AGE_MS = 3 * 60 * 1_000;
    private static final Map<String, Entry> sCache = new LinkedHashMap<>();

    private static final class Entry {
        final MediaItemMetadata metadata;
        final long timeMs = System.currentTimeMillis();

        Entry(MediaItemMetadata metadata) {
            this.metadata = metadata;
        }

        boolean isFresh() {
            return System.currentTimeMillis() - timeMs < MAX_AGE_MS;
        }
    }

    private ShortsMetadataCache() {
    }

    public static synchronized void put(String videoId, MediaItemMetadata metadata) {
        if (videoId == null || metadata == null) {
            return;
        }

        sCache.remove(videoId);
        sCache.put(videoId, new Entry(metadata));

        Iterator<String> iterator = sCache.keySet().iterator();
        while (sCache.size() > MAX_SIZE && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    public static synchronized boolean contains(String videoId) {
        Entry entry = videoId != null ? sCache.get(videoId) : null;
        return entry != null && entry.isFresh();
    }

    /**
     * The fresh metadata of the video (removed from the cache) or null.
     */
    public static synchronized MediaItemMetadata take(String videoId) {
        Entry entry = videoId != null ? sCache.remove(videoId) : null;
        return entry != null && entry.isFresh() ? entry.metadata : null;
    }

    public static synchronized void clear() {
        sCache.clear();
    }
}
