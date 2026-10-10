package com.liskovsoft.smartyoutubetv2.common.app.models.playback;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.service.VideoStateService;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.service.VideoStateService.State;
import com.liskovsoft.smartyoutubetv2.common.prefs.BlockedChannelData;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;

/**
 * JoTube: what plays after a regular video, like on YouTube: one of the videos related to the one that just played
 * (YouTube's own autoplay pick or one of the top related videos), never the next card of the row the video was
 * started from. Videos played recently (kept over app restarts) or mostly watched are skipped, so the same
 * videos don't come back again and again.<br/>
 * Everything is picked from the metadata that is loaded anyway (the suggestions), no extra requests.
 */
public final class SmartNext {
    private static final String TAG = SmartNext.class.getSimpleName();
    private static final String PREFS = "jotube_recent_videos";
    private static final String KEY_IDS = "played_ids";
    private static final int MAX_RECENT = 300;
    private static final int MAX_RELATED = 8; // top related videos to choose from
    private static final float AUTOPLAY_CHANCE = 0.5f; // YouTube's own pick, when it is a fresh one
    private static final float WATCHED_PERCENT = 70;
    private static final long SAVE_DELAY_MS = 3_000;
    private static final long SAVE_MAX_DELAY_MS = 30_000;
    private static SmartNext sInstance;
    private final SharedPreferences mPrefs;
    private final LinkedHashSet<String> mRecent = new LinkedHashSet<>(); // oldest first
    private final Random mRandom = new Random();
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mSave = this::save;
    private final Context mContext;
    private long mSavePendingSinceMs;

    private SmartNext(Context context) {
        mContext = context.getApplicationContext();
        mPrefs = mContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String saved = mPrefs.getString(KEY_IDS, null);

        if (!TextUtils.isEmpty(saved)) {
            mRecent.addAll(Arrays.asList(saved.split(",")));
        }
    }

    public static synchronized SmartNext instance(Context context) {
        if (sInstance == null && context != null) {
            sInstance = new SmartNext(context);
        }

        return sInstance;
    }

    /**
     * A regular video started playing.
     */
    public synchronized void markPlayed(String videoId) {
        if (TextUtils.isEmpty(videoId)) {
            return;
        }

        mRecent.remove(videoId); // most recent at the end
        mRecent.add(videoId);

        Iterator<String> iterator = mRecent.iterator();
        while (mRecent.size() > MAX_RECENT && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }

        long nowMs = System.currentTimeMillis();
        if (mSavePendingSinceMs == 0) {
            mSavePendingSinceMs = nowMs;
        }
        if (nowMs - mSavePendingSinceMs < SAVE_MAX_DELAY_MS) {
            mHandler.removeCallbacks(mSave);
            mHandler.postDelayed(mSave, SAVE_DELAY_MS);
        }
    }

    private synchronized boolean isRecent(String videoId) {
        return mRecent.contains(videoId);
    }

    private void save() {
        String value;

        synchronized (this) {
            mSavePendingSinceMs = 0;
            value = TextUtils.join(",", mRecent);
        }

        mPrefs.edit().putString(KEY_IDS, value).apply();
    }

    /**
     * The video to play after {@code current}, or null when there is nothing suitable (the caller falls back to
     * YouTube's autoplay pick as before).
     *
     * @param autoplay YouTube's own autoplay pick (from the metadata)
     * @param suggestions the suggestion rows of the metadata (the first one is the related videos)
     */
    public Video pick(Video current, MediaItem autoplay, List<MediaGroup> suggestions) {
        if (current == null || current.videoId == null) {
            return null;
        }

        Video autoplayVideo = autoplay != null ? Video.from(autoplay) : null;
        boolean autoplayFresh = isFresh(current, autoplayVideo);

        // The related videos first (the first row), then the other rows when it has too few fresh ones
        List<Video> related = new ArrayList<>();

        if (suggestions != null) {
            for (MediaGroup group : suggestions) {
                if (related.size() >= MAX_RELATED) {
                    break;
                }

                if (group == null || group.getMediaItems() == null) {
                    continue;
                }

                for (MediaItem item : group.getMediaItems()) {
                    if (related.size() >= MAX_RELATED) {
                        break;
                    }

                    Video video = item != null ? Video.from(item) : null;

                    if (isFresh(current, video) && (autoplayVideo == null || !video.videoId.equals(autoplayVideo.videoId))
                            && !containsId(related, video.videoId)) {
                        related.add(video);
                    }
                }
            }
        }

        Video result;
        String reason;

        if (autoplayFresh && (related.isEmpty() || mRandom.nextFloat() < AUTOPLAY_CHANCE)) {
            result = autoplayVideo;
            reason = "autoplay";
        } else if (!related.isEmpty()) {
            // The higher the better: weights n, n-1, ..., 1
            int total = related.size() * (related.size() + 1) / 2;
            int ticket = mRandom.nextInt(total);
            int index = 0;
            for (int weight = related.size(); weight > 0; weight--, index++) {
                ticket -= weight;
                if (ticket < 0) {
                    break;
                }
            }
            result = related.get(Math.min(index, related.size() - 1));
            reason = "related #" + (index + 1) + " of " + related.size();
        } else {
            result = null;
            reason = "nothing fresh";
        }

        Log.d(TAG, "Next after %s: %s (%s, autoplay %s %s)", current.videoId, result != null ? result.videoId : null, reason,
                autoplayVideo != null ? autoplayVideo.videoId : null, autoplayFresh ? "fresh" : "skipped");

        return result;
    }

    private boolean isFresh(Video current, Video video) {
        if (video == null || video.videoId == null || video.videoId.equals(current.videoId)) {
            return false;
        }

        if (video.isShorts || video.isLive || video.isUpcoming || video.isMix() || video.hasPlaylist()) {
            return false;
        }

        if (isRecent(video.videoId) || video.percentWatched >= WATCHED_PERCENT) {
            return false;
        }

        VideoStateService stateService = VideoStateService.instance(mContext);
        State state = stateService != null ? stateService.getByVideoId(video.videoId) : null;

        if (state != null && state.durationMs > 0 && state.positionMs * 100f / state.durationMs >= WATCHED_PERCENT) {
            return false;
        }

        BlockedChannelData blocked = BlockedChannelData.instance(mContext);

        return blocked == null || blocked.isEmpty() || !blocked.containsChannel(video.channelId, video.getAuthor());
    }

    private static boolean containsId(List<Video> videos, String videoId) {
        for (Video video : videos) {
            if (videoId.equals(video.videoId)) {
                return true;
            }
        }

        return false;
    }
}
