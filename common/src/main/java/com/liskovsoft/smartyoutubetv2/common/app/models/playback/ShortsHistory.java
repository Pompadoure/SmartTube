package com.liskovsoft.smartyoutubetv2.common.app.models.playback;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.liskovsoft.mediaserviceinterfaces.ContentService;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;

import io.reactivex.Observable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * JoTube: the Shorts that were played recently (kept over app restarts), so a new feed doesn't show them again.<br/>
 * Also the Shorts the user engaged with (watched to the end, most of it, or liked): the next pages and new feeds
 * are seeded with them now and then (YouTube's own "more like this" sequence of that Short).
 */
public final class ShortsHistory {
    private static final String TAG = ShortsHistory.class.getSimpleName();
    private static final String PREFS = "jotube_shorts_history";
    private static final String KEY_IDS = "seen_ids";
    private static final String KEY_SEEDS = "engaged_seeds";
    private static final int MAX_SIZE = 2000;
    private static final int MAX_SEEDS = 30; // persisted engaged Shorts (new feeds)
    private static final int MAX_SESSION_SEEDS = 5; // engaged Shorts of this run (next pages)
    private static final float SEED_CHANCE = 0.5f;
    private static final long SAVE_DELAY_MS = 3_000;
    private static final long SAVE_MAX_DELAY_MS = 30_000; // steady swiping still saves now and then
    private static final int RECENT_SEEDS = 10; // new feeds are seeded with one of the most recent engaged Shorts
    /**
     * First page of a feed: when every Short of it was seen already, this many are kept anyway (the first Short and
     * the preload lookahead are there at once, the next page comes in the background).
     */
    private static final int FIRST_PAGE_MIN_KEEP = 5;
    /**
     * Background pages: fewer new Shorts than this on a page and the caller loads one more page.
     */
    public static final int MIN_NEW_PER_PAGE = 3;
    private static ShortsHistory sInstance;
    private final SharedPreferences mPrefs;
    private final LinkedHashSet<String> mSeen = new LinkedHashSet<>();
    private final LinkedHashMap<String, String> mSeeds = new LinkedHashMap<>(); // videoId -> reel params, oldest first
    private final List<String> mSessionSeeds = new ArrayList<>(); // videoIds, oldest first
    private final Random mRandom = new Random();
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mSaveSeen = this::saveSeen;
    private long mSavePendingSinceMs;

    private ShortsHistory(Context context) {
        mPrefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String saved = mPrefs.getString(KEY_IDS, null);

        if (!TextUtils.isEmpty(saved)) {
            mSeen.addAll(Arrays.asList(saved.split(",")));
        }

        String seeds = mPrefs.getString(KEY_SEEDS, null);

        if (!TextUtils.isEmpty(seeds)) {
            for (String entry : seeds.split("\n")) {
                String[] parts = entry.split("\t");
                if (parts.length == 2 && !parts[0].isEmpty() && !parts[1].isEmpty()) {
                    mSeeds.put(parts[0], parts[1]);
                }
            }
        }
    }

    public static synchronized ShortsHistory instance(Context context) {
        if (sInstance == null && context != null) {
            sInstance = new ShortsHistory(context);
        }

        return sInstance;
    }

    /**
     * A new Shorts feed: now and then seeded with a Short the user engaged with (decided per subscription,
     * the seed request replaces the seedless one).
     */
    public static Observable<MediaGroup> newFeedObserve(Context context, ContentService service) {
        Context appContext = context != null ? context.getApplicationContext() : null;

        return Observable.<MediaGroup>defer(() -> {
            ShortsHistory history = instance(appContext);
            String[] seed = history != null ? history.pickFeedSeed() : null;

            if (seed != null) {
                Log.d(TAG, "Shorts feed: new feed seeded with the engaged Short %s", seed[0]);
                return service.getShortsObserve(seed[0], seed[1]);
            }

            return service.getShortsObserve();
        });
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

        // Batched: not the whole list on every swipe (but at least every SAVE_MAX_DELAY_MS)
        long nowMs = System.currentTimeMillis();
        if (mSavePendingSinceMs == 0) {
            mSavePendingSinceMs = nowMs;
        }
        if (nowMs - mSavePendingSinceMs < SAVE_MAX_DELAY_MS) {
            mHandler.removeCallbacks(mSaveSeen);
            mHandler.postDelayed(mSaveSeen, SAVE_DELAY_MS);
        }
    }

    private void saveSeen() {
        String ids;

        synchronized (this) {
            mSavePendingSinceMs = 0;
            ids = TextUtils.join(",", mSeen);
        }

        mPrefs.edit().putString(KEY_IDS, ids).apply();
    }

    public synchronized boolean isSeen(String videoId) {
        return videoId != null && mSeen.contains(videoId);
    }

    /**
     * The user watched the Short to the end (or most of it) or liked it.
     *
     * @param params its reel params (null: not a Short of the Shorts feed, can't be a seed)
     */
    public synchronized void markEngaged(String videoId, String params, String reason) {
        if (videoId == null || videoId.isEmpty() || params == null || params.isEmpty() ||
                params.contains("\t") || params.contains("\n") || videoId.contains("\t") || videoId.contains("\n")) {
            return;
        }

        boolean isNew = !mSessionSeeds.contains(videoId);

        mSessionSeeds.remove(videoId); // most recent at the end
        mSessionSeeds.add(videoId);

        while (mSessionSeeds.size() > MAX_SESSION_SEEDS) {
            mSessionSeeds.remove(0);
        }

        mSeeds.remove(videoId);
        mSeeds.put(videoId, params);

        Iterator<String> iterator = mSeeds.keySet().iterator();
        while (mSeeds.size() > MAX_SEEDS && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }

        if (isNew) {
            Log.d(TAG, "Shorts feed: engaged with %s (%s)", videoId, reason);
            saveSeeds();
        }
    }

    private void saveSeeds() {
        List<String> entries = new ArrayList<>();
        for (Map.Entry<String, String> entry : mSeeds.entrySet()) {
            entries.add(entry.getKey() + "\t" + entry.getValue());
        }
        mPrefs.edit().putString(KEY_SEEDS, TextUtils.join("\n", entries)).apply();
    }

    /**
     * The next page: now and then from the sequence of one of the last Shorts the user engaged with in this run.
     *
     * @return videoId or null (the usual next page)
     */
    public synchronized String pickSessionSeed() {
        if (mSessionSeeds.isEmpty() || mRandom.nextFloat() >= SEED_CHANCE) {
            return null;
        }

        // Used once: the sequence of a Short is always the same, picking it again would bring back the same page
        return mSessionSeeds.remove(mRandom.nextInt(mSessionSeeds.size()));
    }

    /**
     * A new feed: now and then seeded with one of the Shorts the user engaged with (also in the earlier runs).
     *
     * @return {videoId, params} or null (the usual seedless feed)
     */
    public synchronized String[] pickFeedSeed() {
        if (mSeeds.isEmpty() || mRandom.nextFloat() >= SEED_CHANCE) {
            return null;
        }

        // One of the most recent ones (the map is oldest first), then cooled down: a seed gives the same first page
        // every time, and a seed that stopped working (deleted Short, expired params) costs a request only now and then
        int recent = Math.min(RECENT_SEEDS, mSeeds.size());
        int index = mSeeds.size() - recent + mRandom.nextInt(recent);

        for (Map.Entry<String, String> entry : mSeeds.entrySet()) {
            if (index-- == 0) {
                String[] seed = {entry.getKey(), entry.getValue()};
                // Cool-down, not removal (a warm-up feed may never be opened): it goes to the oldest place, so the
                // next feeds pick other ones, and it's dropped when newer engaged Shorts push it out
                LinkedHashMap<String, String> reordered = new LinkedHashMap<>();
                reordered.put(seed[0], seed[1]);
                for (Map.Entry<String, String> other : mSeeds.entrySet()) {
                    if (!other.getKey().equals(seed[0])) {
                        reordered.put(other.getKey(), other.getValue());
                    }
                }
                mSeeds.clear();
                mSeeds.putAll(reordered);
                saveSeeds();
                return seed;
            }
        }

        return null;
    }

    /**
     * First page of a feed. See {@link #filterNew(VideoGroup, int, int)}.
     */
    public int filterNew(VideoGroup group, int fromIndex) {
        return filterNew(group, fromIndex, FIRST_PAGE_MIN_KEEP);
    }

    /**
     * Removes the Shorts seen recently among the items added from the given index on. If the whole page was seen
     * already, the first minKeepAllSeen of them are kept (a repeated Short beats a feed that stops).
     *
     * @return how many not seen items the page has (the caller may load one more page when it's few)
     */
    public int filterNew(VideoGroup group, int fromIndex, int minKeepAllSeen) {
        if (group == null || group.isEmpty()) {
            return 0;
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

        int unseen = Math.max(0, videos.size() - start - seen.size());

        if (seen.isEmpty()) {
            return unseen;
        }

        if (unseen == 0) {
            // Everything on the page was seen: keep a few
            for (int i = Math.max(0, minKeepAllSeen); i < seen.size(); i++) {
                group.remove(seen.get(i));
            }
            Log.d(TAG, "Shorts feed: the whole page (%s) was seen already, kept %s", seen.size(), Math.min(seen.size(), Math.max(0, minKeepAllSeen)));
            return 0;
        }

        for (Video video : seen) {
            group.remove(video);
        }

        return unseen;
    }

    /**
     * Light shuffle of the not seen Shorts added from the given index on (a new page that nothing was taken from yet:
     * not shown, not preloaded). Other items keep their places.
     */
    public void shuffleNew(VideoGroup group, int fromIndex) {
        if (group == null || group.isEmpty()) {
            return;
        }

        List<Video> videos = group.getVideos();
        int start = Math.max(0, fromIndex);

        if (start >= videos.size() - 1) {
            return;
        }

        List<Video> tail = new ArrayList<>(videos.subList(start, videos.size()));
        boolean[] movable = new boolean[tail.size()];
        List<Video> shuffled = new ArrayList<>();

        for (int i = 0; i < tail.size(); i++) {
            Video video = tail.get(i);
            movable[i] = video != null && video.isShorts && !isSeen(video.videoId);
            if (movable[i]) {
                shuffled.add(video);
            }
        }

        if (shuffled.size() < 2) {
            return;
        }

        Collections.shuffle(shuffled, mRandom);

        List<Video> newTail = new ArrayList<>(tail.size());
        int next = 0;

        for (int i = 0; i < tail.size(); i++) {
            newTail.add(movable[i] ? shuffled.get(next++) : tail.get(i));
        }

        for (Video video : tail) {
            group.remove(video);
        }

        for (Video video : newTail) {
            group.add(video);
        }
    }
}
