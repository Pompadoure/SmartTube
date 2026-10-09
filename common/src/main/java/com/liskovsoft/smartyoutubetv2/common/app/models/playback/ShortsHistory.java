package com.liskovsoft.smartyoutubetv2.common.app.models.playback;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * JoTube: the Shorts that were played recently (kept over app restarts), so a new feed doesn't show them again.
 */
public final class ShortsHistory {
    private static final String PREFS = "jotube_shorts_history";
    private static final String KEY_IDS = "seen_ids";
    private static final int MAX_SIZE = 400;
    private static ShortsHistory sInstance;
    private final SharedPreferences mPrefs;
    private final LinkedHashSet<String> mSeen = new LinkedHashSet<>();

    private ShortsHistory(Context context) {
        mPrefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String saved = mPrefs.getString(KEY_IDS, null);

        if (!TextUtils.isEmpty(saved)) {
            mSeen.addAll(Arrays.asList(saved.split(",")));
        }
    }

    public static synchronized ShortsHistory instance(Context context) {
        if (sInstance == null && context != null) {
            sInstance = new ShortsHistory(context);
        }

        return sInstance;
    }

    public synchronized void markSeen(String videoId) {
        if (videoId == null || videoId.isEmpty()) {
            return;
        }

        mSeen.remove(videoId); // most recent at the end
        mSeen.add(videoId);

        Iterator<String> iterator = mSeen.iterator();
        while (mSeen.size() > MAX_SIZE && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }

        mPrefs.edit().putString(KEY_IDS, TextUtils.join(",", mSeen)).apply();
    }

    public synchronized boolean isSeen(String videoId) {
        return videoId != null && mSeen.contains(videoId);
    }

    /**
     * Removes the Shorts seen recently among the items added from the given index on. If the whole page was seen
     * already, it's kept as is (a repeated Short beats a feed that stops).
     */
    public void filterNew(VideoGroup group, int fromIndex) {
        if (group == null || group.isEmpty()) {
            return;
        }

        List<Video> videos = new ArrayList<>(group.getVideos());
        List<Video> seen = new ArrayList<>();
        int start = Math.max(0, fromIndex);

        for (int i = start; i < videos.size(); i++) {
            Video video = videos.get(i);

            if (video != null && video.isShorts && isSeen(video.videoId)) {
                seen.add(video);
            }
        }

        if (seen.isEmpty() || seen.size() >= videos.size() - start) {
            return;
        }

        for (Video video : seen) {
            group.remove(video);
        }
    }
}
