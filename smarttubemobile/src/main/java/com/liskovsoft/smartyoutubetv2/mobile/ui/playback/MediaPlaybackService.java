package com.liskovsoft.smartyoutubetv2.mobile.ui.playback;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.Nullable;
import androidx.media3.common.ForwardingPlayer;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.session.DefaultMediaNotificationProvider;
import androidx.media3.session.MediaController;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;
import androidx.media3.session.SessionToken;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.mobile.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Background playback: the foreground service with the media notification (lock screen, notification shade and the
 * status bar chip). The player itself stays with the playback screen; the service only holds a media session around
 * it, so the system keeps the process alive while it plays and shows the controls.
 */
public class MediaPlaybackService extends MediaSessionService {
    private static final String TAG = MediaPlaybackService.class.getSimpleName();
    private static final Handler sHandler = new Handler(Looper.getMainLooper());
    private static MediaSession sSession;
    private static SessionPlayer sPlayer;
    private static MediaController sController;
    private static com.google.common.util.concurrent.ListenableFuture<MediaController> sControllerFuture;
    private static Video sVideo; // strong: the presenter only keeps a weak reference
    private static MediaPlaybackService sService;

    private static Player sPodcastPlayer;

    /** The YouTube player belongs to the session from now on (called when the playback screen creates its player). */
    public static void bind(Context context, Player player) {
        Context app = context.getApplicationContext();
        sPlayer = new SessionPlayer(app, player);
        com.liskovsoft.smartyoutubetv2.mobile.ui.podcast.PodcastPlayer.pause(); // one sound at a time
        attach(app, sPlayer);
    }

    /** The podcast player belongs to the session (its media items carry title, show and cover themselves). */
    public static void bindPodcast(Context context, Player player) {
        sPodcastPlayer = player;
        if (sPlayer != null) {
            sPlayer.setPlayWhenReady(false); // one sound at a time
        }
        attach(context.getApplicationContext(), player);
    }

    private static Context sApp;

    private static void setSessionActivityFor(boolean podcast) {
        if (sApp == null || sSession == null) {
            return;
        }

        Intent open = podcast
                ? new Intent(sApp, com.liskovsoft.smartyoutubetv2.mobile.ui.podcast.PodcastPlayerActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                : new Intent(sApp, PlaybackActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(PlaybackActivity.EXTRA_FROM_NOTIFICATION, true);
        sSession.setSessionActivity(PendingIntent.getActivity(sApp, podcast ? 1 : 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
    }

    private static void attach(Context app, Player player) {
        sApp = app;
        if (sSession == null) {
            Intent open = new Intent(app, PlaybackActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(PlaybackActivity.EXTRA_FROM_NOTIFICATION, true);
            PendingIntent pending = PendingIntent.getActivity(app, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            sSession = new MediaSession.Builder(app, player).setId("jotube").setSessionActivity(pending).build();
        } else {
            sSession.setPlayer(player);
        }

        if (player == sPodcastPlayer && sSession != null) {
            // tapping the notification of a podcast opens the podcast player
            Intent open = new Intent(app, com.liskovsoft.smartyoutubetv2.mobile.ui.podcast.PodcastPlayerActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            sSession.setSessionActivity(PendingIntent.getActivity(app, 1, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        } else if (sSession != null) {
            Intent open = new Intent(app, PlaybackActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(PlaybackActivity.EXTRA_FROM_NOTIFICATION, true);
            sSession.setSessionActivity(PendingIntent.getActivity(app, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        }

        if (sController == null && sControllerFuture == null) {
            // Connecting a controller binds the service; the service then posts the notification while playing
            try {
                SessionToken token = new SessionToken(app, new ComponentName(app, MediaPlaybackService.class));
                MediaController.Builder builder = new MediaController.Builder(app, token);
                final com.google.common.util.concurrent.ListenableFuture<MediaController> future = builder.buildAsync();
                sControllerFuture = future;
                future.addListener(() -> {
                    try {
                        MediaController controller = future.get();
                        if (sControllerFuture == future && sSession != null) {
                            sController = controller;
                        } else {
                            controller.release(); // unbound in the meantime
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Can't bind the media service: %s", e.getMessage());
                    }
                }, androidx.core.content.ContextCompat.getMainExecutor(app));
            } catch (RuntimeException e) {
                Log.e(TAG, "Can't bind the media service: %s", e.getMessage());
            }
        } else if (sService != null && sSession != null) {
            sService.addSession(sSession); // no-op if already known
        }
    }

    /** The YouTube playback screen released its player. */
    public static void unbind() {
        sPlayer = null;

        if (sPodcastPlayer != null && sSession != null) {
            sSession.setPlayer(sPodcastPlayer);
            setSessionActivityFor(true);
            return;
        }

        teardown();
    }

    /** The podcast player was stopped. */
    public static void unbindPodcast() {
        sPodcastPlayer = null;

        if (sPlayer != null && sSession != null) {
            sSession.setPlayer(sPlayer);
            setSessionActivityFor(false);
            return;
        }

        teardown();
    }

    private static void teardown() {
        sControllerFuture = null;
        if (sController != null) {
            sController.release();
            sController = null;
        }

        if (sSession != null) {
            if (sService != null) {
                sService.removeSession(sSession);
                sService.stopSelf();
            }
            sSession.release();
            sSession = null;
        }
    }

    public static Player getPodcastPlayer() {
        return sPodcastPlayer;
    }

    public static void setVideo(Video video) {
        sVideo = video;
    }

    public static Video getVideo() {
        return sVideo;
    }

    /** The current video changed (title, artist, cover). */
    public static void notifyMetadataChanged() {
        SessionPlayer player = sPlayer;
        if (player != null) {
            player.fireMetadataChanged();
        }
    }

    /** The engine's player (for the mini player in the feed), or null. */
    public static Player getPlayer() {
        return sPlayer != null ? sPlayer.getWrappedPlayer() : null;
    }

    public static boolean isActive() {
        return sSession != null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sService = this;

        DefaultMediaNotificationProvider provider = new DefaultMediaNotificationProvider.Builder(this).build();
        provider.setSmallIcon(R.drawable.ic_notification);
        setMediaNotificationProvider(provider);

        if (sSession != null) {
            addSession(sSession);
        }
    }

    @Nullable
    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return sSession;
    }

    @Override
    public void onDestroy() {
        sService = null;
        super.onDestroy();
    }

    /** Adds next/previous (the engine has one item at a time) and the metadata of the current video. */
    private static class SessionPlayer extends ForwardingPlayer {
        private final Context mContext;
        private final List<Player.Listener> mListeners = new ArrayList<>();

        SessionPlayer(Context context, Player player) {
            super(player);
            mContext = context;
        }

        @Override
        public void addListener(Player.Listener listener) {
            super.addListener(listener);
            mListeners.add(listener);
        }

        @Override
        public void removeListener(Player.Listener listener) {
            super.removeListener(listener);
            mListeners.remove(listener);
        }

        void fireMetadataChanged() {
            MediaMetadata metadata = getMediaMetadata();
            for (Player.Listener listener : new ArrayList<>(mListeners)) {
                listener.onMediaMetadataChanged(metadata);
            }
        }

        @Override
        public Commands getAvailableCommands() {
            return super.getAvailableCommands().buildUpon()
                    .add(COMMAND_SEEK_TO_NEXT)
                    .add(COMMAND_SEEK_TO_PREVIOUS)
                    .add(COMMAND_GET_METADATA)
                    .build();
        }

        @Override
        public boolean isCommandAvailable(int command) {
            return getAvailableCommands().contains(command);
        }

        @Override
        public boolean hasNextMediaItem() {
            return true;
        }

        @Override
        public boolean hasPreviousMediaItem() {
            return true;
        }

        @Override
        public void seekToNext() {
            sHandler.post(() -> PlaybackPresenter.instance(mContext).onNextClicked());
        }

        @Override
        public void seekToNextMediaItem() {
            seekToNext();
        }

        @Override
        public void seekToPrevious() {
            if (getCurrentPosition() > 5_000) {
                seekTo(0);
                return;
            }
            sHandler.post(() -> PlaybackPresenter.instance(mContext).onPreviousClicked());
        }

        @Override
        public void seekToPreviousMediaItem() {
            seekToPrevious();
        }

        @Override
        public MediaMetadata getMediaMetadata() {
            Video video = sVideo != null ? sVideo : PlaybackPresenter.instance(mContext).getVideo();
            if (video == null) {
                return super.getMediaMetadata();
            }

            MediaMetadata.Builder builder = new MediaMetadata.Builder()
                    .setTitle(video.getTitle())
                    .setArtist(video.getAuthor());
            String image = video.getCardImageUrl();
            if (image != null) {
                builder.setArtworkUri(Uri.parse(image));
            }
            return builder.build();
        }

        @Nullable
        @Override
        public MediaItem getCurrentMediaItem() {
            MediaItem item = super.getCurrentMediaItem();
            return item == null ? null : item.buildUpon().setMediaMetadata(getMediaMetadata()).build();
        }
    }
}
