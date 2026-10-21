package com.liskovsoft.smartyoutubetv2.mobile.ui.podcast;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import com.liskovsoft.smartyoutubetv2.mobile.ui.playback.MediaPlaybackService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * The podcast player: one process wide ExoPlayer that lives with the media service (not with a screen), so the
 * episode goes on with the screen off or the app closed. Remembers the position of every episode.
 */
public final class PodcastPlayer {
    private static final Handler sHandler = new Handler(Looper.getMainLooper());
    private static ExoPlayer sPlayer;
    private static List<PodcastRepository.Episode> sQueue = new ArrayList<>();
    private static Context sContext;
    private static float sSpeed = 1f;
    /** Episodes the user queued ("Spela härnäst" / "Lägg till i kön"): a block right after the playing one. */
    private static final Set<PodcastRepository.Episode> sQueued = Collections.newSetFromMap(new IdentityHashMap<>());
    private static Runnable sQueueListener;

    private PodcastPlayer() {
    }

    private static final Runnable sSaveTick = new Runnable() {
        @Override
        public void run() {
            savePosition();
            if (sPlayer != null) {
                sHandler.postDelayed(this, 5_000);
            }
        }
    };

    private static ExoPlayer get(Context context) {
        if (sPlayer == null) {
            sContext = context.getApplicationContext();
            sPlayer = new ExoPlayer.Builder(sContext)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
                    .setHandleAudioBecomingNoisy(true)
                    .setWakeMode(C.WAKE_MODE_NETWORK)
                    .setSeekBackIncrementMs(10_000)
                    .setSeekForwardIncrementMs(30_000)
                    .build();
            sPlayer.addListener(new Player.Listener() {
                @Override
                public void onIsPlayingChanged(boolean isPlaying) {
                    if (!isPlaying) {
                        savePosition();
                    }
                }

                @Override
                public void onMediaItemTransition(MediaItem item, int reason) {
                    // a queued episode that starts is no longer "next"
                    sQueued.remove(current());
                    notifyQueueChanged();

                    // the next episode of the queue starts where the listener left it
                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
                        PodcastRepository.Episode episode = current();
                        if (episode != null) {
                            long saved = PodcastRepository.getPosition(sContext, episode);
                            if (saved > 5_000) {
                                sPlayer.seekTo(saved);
                            }
                        }
                    }
                }
            });
        }
        return sPlayer;
    }

    public static ExoPlayer player(Context context) {
        return get(context);
    }

    /** Plays [index] of [queue] (the episodes as listed on screen), resuming where it was left. */
    public static void play(Context context, List<PodcastRepository.Episode> queue, int index) {
        if (queue == null || index < 0 || index >= queue.size()) {
            return;
        }

        ExoPlayer player = get(context);
        sQueue = new ArrayList<>(queue);

        // JoTube: the episodes the user queued stay next (after the picked one), then the rest of the list
        List<PodcastRepository.Episode> queued = queued();
        sQueued.clear();
        PodcastRepository.Episode picked = sQueue.get(index);
        for (int i = queued.size() - 1; i >= 0; i--) {
            if (sameEpisode(queued.get(i), picked)) {
                queued.remove(i); // the picked one plays now
            }
        }
        if (!queued.isEmpty()) {
            for (PodcastRepository.Episode episode : queued) {
                removeByKey(sQueue, episode, picked);
            }
            index = sQueue.indexOf(picked);
            sQueue.addAll(index + 1, queued);
            sQueued.addAll(queued);
        }

        List<MediaItem> items = new ArrayList<>();
        for (PodcastRepository.Episode episode : sQueue) {
            items.add(toMediaItem(episode));
        }

        // Only an episode the user picks counts as listened to again (not the ones that follow in the queue)
        PodcastRepository.clearFinished(sContext, sQueue.get(index));
        long start = PodcastRepository.getPosition(sContext, sQueue.get(index));
        player.setMediaItems(items, index, start > 5_000 ? start : 0);
        player.setPlaybackParameters(new PlaybackParameters(sSpeed));
        player.prepare();
        player.setPlayWhenReady(true);

        MediaPlaybackService.bindPodcast(sContext, player);
        sHandler.removeCallbacks(sSaveTick);
        sHandler.postDelayed(sSaveTick, 5_000);
        notifyQueueChanged();
    }

    private static MediaItem toMediaItem(PodcastRepository.Episode episode) {
        MediaMetadata.Builder metadata = new MediaMetadata.Builder()
                .setTitle(episode.title)
                .setArtist(episode.feedTitle);
        if (episode.image != null) {
            metadata.setArtworkUri(Uri.parse(episode.image));
        }
        return new MediaItem.Builder()
                .setMediaId(key(episode))
                .setUri(episode.audioUrl)
                .setMediaMetadata(metadata.build())
                .build();
    }

    private static String key(PodcastRepository.Episode episode) {
        return episode.guid != null ? episode.guid : episode.audioUrl;
    }

    private static boolean sameEpisode(PodcastRepository.Episode a, PodcastRepository.Episode b) {
        return a == b || (a != null && b != null && key(a) != null && key(a).equals(key(b)));
    }

    /** Removes [episode] (same guid/url) from [list], but never [keep]. */
    private static void removeByKey(List<PodcastRepository.Episode> list, PodcastRepository.Episode episode, PodcastRepository.Episode keep) {
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i) != keep && sameEpisode(list.get(i), episode)) {
                list.remove(i);
            }
        }
    }

    ///////////////////// queue

    /** The queue screen listens (one at a time). */
    public static void setQueueListener(Runnable listener) {
        sQueueListener = listener;
    }

    private static void notifyQueueChanged() {
        if (sQueueListener != null) {
            sQueueListener.run();
        }
    }

    /** "Spela härnäst": right after the playing episode. Starts it when nothing plays. */
    public static void playNext(Context context, PodcastRepository.Episode episode) {
        enqueue(context, episode, true);
    }

    /** "Lägg till i kön": after the other queued episodes. Starts it when nothing plays. */
    public static void addToQueue(Context context, PodcastRepository.Episode episode) {
        enqueue(context, episode, false);
    }

    private static void enqueue(Context context, PodcastRepository.Episode episode, boolean next) {
        if (episode == null || episode.audioUrl == null) {
            return;
        }

        if (!isActive()) {
            List<PodcastRepository.Episode> single = new ArrayList<>();
            single.add(episode);
            play(context, single, 0);
            return;
        }

        PodcastRepository.Episode playing = current();
        if (sameEpisode(playing, episode)) {
            return; // already playing
        }

        // the user picked it: it counts as not listened to again (like a tap on it)
        PodcastRepository.clearFinished(sContext, episode);

        removeUpcoming(episode);
        int position = sPlayer.getCurrentMediaItemIndex() + 1;
        if (!next) {
            while (position < sQueue.size() && sQueued.contains(sQueue.get(position))) {
                position++;
            }
        }
        sQueue.add(position, episode);
        sPlayer.addMediaItem(position, toMediaItem(episode));
        sQueued.add(episode);
        notifyQueueChanged();
    }

    /** The queued episodes after the playing one, in playing order. */
    public static List<PodcastRepository.Episode> queued() {
        List<PodcastRepository.Episode> result = new ArrayList<>();
        if (sPlayer == null) {
            return result;
        }
        for (int i = sPlayer.getCurrentMediaItemIndex() + 1; i < sQueue.size() && sQueued.contains(sQueue.get(i)); i++) {
            result.add(sQueue.get(i));
        }
        return result;
    }

    /** The episodes that follow the queued ones (from the list the episode was started from). */
    public static List<PodcastRepository.Episode> rest() {
        List<PodcastRepository.Episode> result = new ArrayList<>();
        if (sPlayer == null) {
            return result;
        }
        for (int i = sPlayer.getCurrentMediaItemIndex() + 1 + queued().size(); i < sQueue.size(); i++) {
            result.add(sQueue.get(i));
        }
        return result;
    }

    /** Removes an upcoming episode (queued or from the list). */
    public static void remove(PodcastRepository.Episode episode) {
        if (removeUpcoming(episode)) {
            notifyQueueChanged();
        }
    }

    private static boolean removeUpcoming(PodcastRepository.Episode episode) {
        if (sPlayer == null) {
            return false;
        }
        boolean removed = false;
        for (int i = sQueue.size() - 1; i > sPlayer.getCurrentMediaItemIndex(); i--) {
            if (sameEpisode(sQueue.get(i), episode)) {
                sQueued.remove(sQueue.remove(i));
                sPlayer.removeMediaItem(i);
                removed = true;
            }
        }
        return removed;
    }

    /** Plays an upcoming episode now (it resumes where it was left, see onMediaItemTransition). */
    public static void jumpTo(PodcastRepository.Episode episode) {
        if (sPlayer == null) {
            return;
        }
        int index = sQueue.indexOf(episode);
        if (index > sPlayer.getCurrentMediaItemIndex()) {
            PodcastRepository.clearFinished(sContext, episode);
            sPlayer.seekToDefaultPosition(index);
            sPlayer.setPlayWhenReady(true);
        }
    }

    /** The user dragged the queued episodes: [order] is their new order. */
    public static void reorder(List<PodcastRepository.Episode> order) {
        if (sPlayer == null) {
            return;
        }
        int start = sPlayer.getCurrentMediaItemIndex() + 1;
        for (int target = 0; target < order.size(); target++) {
            int from = sQueue.indexOf(order.get(target));
            int to = start + target;
            if (from < start || from == to) {
                continue;
            }
            sQueue.add(to, sQueue.remove(from));
            sPlayer.moveMediaItem(from, to);
        }
        notifyQueueChanged();
    }

    public static PodcastRepository.Episode current() {
        if (sPlayer == null) {
            return null;
        }
        int index = sPlayer.getCurrentMediaItemIndex();
        return index >= 0 && index < sQueue.size() ? sQueue.get(index) : null;
    }

    /** True while there is an episode loaded (playing or paused). */
    public static boolean isActive() {
        return sPlayer != null && sPlayer.getMediaItemCount() > 0;
    }

    public static void pause() {
        if (sPlayer != null && sPlayer.getPlayWhenReady()) {
            sPlayer.pause();
        }
    }

    public static float getSpeed() {
        return sSpeed;
    }

    public static void setSpeed(float speed) {
        sSpeed = speed;
        if (sPlayer != null) {
            sPlayer.setPlaybackParameters(new PlaybackParameters(speed));
        }
    }

    /** Stops the episode and lets go of the media notification. */
    public static void stop() {
        if (sPlayer == null) {
            return;
        }
        savePosition();
        sHandler.removeCallbacks(sSaveTick);
        sPlayer.stop();
        sPlayer.clearMediaItems();
        sQueued.clear();
        notifyQueueChanged();
        MediaPlaybackService.unbindPodcast();
    }

    private static void savePosition() {
        PodcastRepository.Episode episode = current();
        if (episode != null && sPlayer != null) {
            PodcastRepository.savePosition(sContext, episode, sPlayer.getCurrentPosition(), sPlayer.getDuration() == C.TIME_UNSET ? 0 : sPlayer.getDuration());
        }
    }
}
