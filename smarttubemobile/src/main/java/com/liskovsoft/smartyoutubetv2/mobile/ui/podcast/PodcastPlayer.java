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
import java.util.List;

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

        List<MediaItem> items = new ArrayList<>();
        for (PodcastRepository.Episode episode : sQueue) {
            MediaMetadata.Builder metadata = new MediaMetadata.Builder()
                    .setTitle(episode.title)
                    .setArtist(episode.feedTitle);
            if (episode.image != null) {
                metadata.setArtworkUri(Uri.parse(episode.image));
            }
            items.add(new MediaItem.Builder()
                    .setMediaId(episode.guid != null ? episode.guid : episode.audioUrl)
                    .setUri(episode.audioUrl)
                    .setMediaMetadata(metadata.build())
                    .build());
        }

        long start = PodcastRepository.getPosition(sContext, sQueue.get(index));
        player.setMediaItems(items, index, start > 5_000 ? start : 0);
        player.setPlaybackParameters(new PlaybackParameters(sSpeed));
        player.prepare();
        player.setPlayWhenReady(true);

        MediaPlaybackService.bindPodcast(sContext, player);
        sHandler.removeCallbacks(sSaveTick);
        sHandler.postDelayed(sSaveTick, 5_000);
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
        MediaPlaybackService.unbindPodcast();
    }

    private static void savePosition() {
        PodcastRepository.Episode episode = current();
        if (episode != null && sPlayer != null) {
            PodcastRepository.savePosition(sContext, episode, sPlayer.getCurrentPosition(), sPlayer.getDuration() == C.TIME_UNSET ? 0 : sPlayer.getDuration());
        }
    }
}
