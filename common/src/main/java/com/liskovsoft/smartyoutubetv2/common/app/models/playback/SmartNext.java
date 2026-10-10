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
    private static final float AUTOPLAY_SAME_CHANNEL_CHANCE = 0.25f; // ...when it's from the channel just played
    private static final int MAX_SAME_CHANNEL_IN_ROW = 2; // then the next one is from another channel
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
    private final List<String[]> mRecentChannels = new ArrayList<>(); // {channelId, name} of the last played videos
    private String mLastPlayedId;

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
    public synchronized void markPlayed(Video video) {
        String videoId = video != null ? video.videoId : null;

        if (TextUtils.isEmpty(videoId)) {
            return;
        }

        if (!videoId.equals(mLastPlayedId)) { // the same video again (e.g. back from a Short) isn't one more in a row
            mLastPlayedId = videoId;
            mRecentChannels.add(new String[] {channelIdOf(video), channelNameOf(video)});
            while (mRecentChannels.size() > MAX_SAME_CHANNEL_IN_ROW) {
                mRecentChannels.remove(0);
            }
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
        // Variety: after MAX_SAME_CHANNEL_IN_ROW videos of one channel in a row the next one is from another channel
        boolean channelRunFull = isChannelRunFull(current);

        Video result = null;
        String reason = "nothing fresh";

        for (int pass = 0; pass < 2 && result == null; pass++) {
            // Second pass: nothing fresh from another channel, so the channel rule is dropped (still fresh videos)
            boolean otherChannelOnly = channelRunFull && pass == 0;
            boolean autoplayFresh = isFresh(current, autoplayVideo)
                    && !(otherChannelOnly && sameChannel(current, autoplayVideo));
            List<Video> related = collectRelated(current, autoplayVideo, suggestions, otherChannelOnly);

            boolean autoplaySameChannel = sameChannel(current, autoplayVideo);
            float autoplayChance = autoplaySameChannel ? AUTOPLAY_SAME_CHANNEL_CHANCE : AUTOPLAY_CHANCE;

            if (autoplayFresh && (related.isEmpty() || mRandom.nextFloat() < autoplayChance)) {
                result = autoplayVideo;
                reason = "autoplay" + (autoplaySameChannel ? ", same channel" : "");
            } else if (!related.isEmpty()) {
                // The higher the better (2n, 2n-2, ..., 2), a video of the same channel counts half
                int[] weights = new int[related.size()];
                int total = 0;
                for (int i = 0; i < related.size(); i++) {
                    int weight = (related.size() - i) * 2;
                    weights[i] = sameChannel(current, related.get(i)) ? Math.max(1, weight / 2) : weight;
                    total += weights[i];
                }
                int ticket = mRandom.nextInt(total);
                int index = 0;
                while (index < related.size() - 1 && ticket >= weights[index]) {
                    ticket -= weights[index];
                    index++;
                }
                result = related.get(index);
                reason = "related #" + (index + 1) + " of " + related.size()
                        + (sameChannel(current, result) ? ", same channel" : ", other channel");
            }

            if (result != null && channelRunFull) {
                reason += pass == 0 ? ", channel limit" : ", channel limit dropped";
            }
        }

        Log.d(TAG, "Next after %s: %s (%s, autoplay %s)", current.videoId, result != null ? result.videoId : null, reason,
                autoplayVideo != null ? autoplayVideo.videoId : null);

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

    /**
     * The related videos first (the first row), then the other rows when it has too few fresh ones.
     */
    private List<Video> collectRelated(Video current, Video autoplayVideo, List<MediaGroup> suggestions, boolean otherChannelOnly) {
        List<Video> related = new ArrayList<>();

        if (suggestions == null) {
            return related;
        }

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
                        && !containsId(related, video.videoId) && !(otherChannelOnly && sameChannel(current, video))) {
                    related.add(video);
                }
            }
        }

        return related;
    }

    private static String channelIdOf(Video video) {
        return video != null && !TextUtils.isEmpty(video.channelId) ? video.channelId : null;
    }

    private static String channelNameOf(Video video) {
        String author = video != null ? video.getAuthor() : null;
        return TextUtils.isEmpty(author) ? null : author.trim().toLowerCase();
    }

    /**
     * Ids when both are known, else the channel names (suggestion cards often have no channel id).
     */
    private static boolean sameChannel(String id1, String name1, String id2, String name2) {
        if (id1 != null && id2 != null) {
            return id1.equals(id2);
        }

        return name1 != null && name1.equals(name2);
    }

    private static boolean sameChannel(Video video1, Video video2) {
        if (video1 == null || video2 == null) {
            return false;
        }

        return sameChannel(channelIdOf(video1), channelNameOf(video1), channelIdOf(video2), channelNameOf(video2));
    }

    /**
     * The last MAX_SAME_CHANNEL_IN_ROW played videos (the current one included) are all from its channel.
     */
    private synchronized boolean isChannelRunFull(Video current) {
        if (mRecentChannels.size() < MAX_SAME_CHANNEL_IN_ROW) {
            return false;
        }

        String id = channelIdOf(current);
        String name = channelNameOf(current);

        if (id == null && name == null) {
            return false;
        }

        for (String[] channel : mRecentChannels) {
            if (!sameChannel(id, name, channel[0], channel[1])) {
                return false;
            }
        }

        return true;
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
