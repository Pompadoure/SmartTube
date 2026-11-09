package com.liskovsoft.smartyoutubetv2.mobile.cast;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.mediarouter.media.MediaRouteSelector;
import androidx.mediarouter.media.MediaRouter;
import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaLoadRequestData;
import com.google.android.gms.cast.MediaMetadata;
import com.google.android.gms.cast.MediaStatus;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.SessionManagerListener;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;
import com.google.android.gms.common.images.WebImage;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.misc.CastAudioStream;
import com.liskovsoft.smartyoutubetv2.common.misc.CastSource;

import java.util.ArrayList;
import java.util.List;

/**
 * JoTube: Cast from the phone. The phone's player stays the master: it keeps playing (silently, see CastAudioTap) and the
 * receiver gets its sound as a stream. Pause, play and seek in the app are mirrored to the receiver, a new track (also
 * the next one when a track ends) is loaded on the receiver, and pause/play on the receiver is mirrored back.
 */
public final class CastController {
    private static final String TAG = "JoTubeCast";
    private static final long TICK_MS = 400;
    private static final long SEEK_JUMP_MS = 1500;   // the position moved more than playing explains: a seek
    private static final long ECHO_MS = 1500;        // receiver status changes this soon after our own command are echoes

    /** What the player screen offers to the cast code. */
    public interface Host {
        Video video();
        long positionMs();
        boolean isPlaying();
        void setPlaying(boolean play);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static CastContext sContext;
    private static Context sAppContext;
    private static Host sHost;
    private static boolean sListening;
    private static String sLoadedId;       // the video the receiver has
    private static long sLastPos;
    private static long sLastTick;
    private static boolean sLastPlaying;
    private static long sIgnoreReceiverUntil;
    private static boolean sTicking;

    private CastController() {}

    public static void attach(Context context, Host host) {
        sHost = host;
        ensureInit(context);
    }

    public static void detach(Host host) {
        if (sHost == host) {
            sHost = null;
        }
    }

    private static boolean ensureInit(Context context) {
        if (sContext != null) {
            return true;
        }
        try {
            sAppContext = context.getApplicationContext();
            CastSource.setListener(() -> MAIN.post(CastController::onNewSource));
            sContext = CastContext.getSharedInstance(sAppContext);
            if (!sListening) {
                sContext.getSessionManager().addSessionManagerListener(LISTENER, CastSession.class);
                sListening = true;
            }
            return true;
        } catch (Throwable e) { // no Google Play services, etc.
            Log.e(TAG, "Cast is not available: " + e);
            return false;
        }
    }

    /** The cast button: the device list, or the controls (pause, disconnect) while connected. */
    public static void showPicker(Activity activity) {
        try {
            if (!ensureInit(activity)) {
                Toast.makeText(activity, "Cast fungerar inte på den här enheten", Toast.LENGTH_LONG).show();
                return;
            }

            CastSession session = sContext.getSessionManager().getCurrentCastSession();
            if (session != null && session.isConnected()) {
                showControls(activity, session);
            } else {
                showChooser(activity);
            }
        } catch (Throwable e) {
            Log.e(TAG, "Cast picker failed: " + e, e);
            Toast.makeText(activity, "Cast fungerade inte: " + e.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
        }
    }

    // The app theme is android's Material, not AppCompat, so the stock MediaRouter dialogs would crash. Own simple list.
    private static void showChooser(Activity activity) {
        MediaRouteSelector selector = sContext.getMergedSelector();
        if (selector == null) {
            Toast.makeText(activity, "Cast är inte redo än", Toast.LENGTH_SHORT).show();
            return;
        }

        final MediaRouter router = MediaRouter.getInstance(activity);
        final List<MediaRouter.RouteInfo> routes = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        final android.widget.ArrayAdapter<String> adapter =
                new android.widget.ArrayAdapter<>(activity, android.R.layout.simple_list_item_1, names);
        final Runnable refresh = () -> {
            routes.clear();
            names.clear();
            for (MediaRouter.RouteInfo route : router.getRoutes()) {
                if (!route.isDefaultOrBluetooth() && route.isEnabled() && route.matchesSelector(selector)) {
                    routes.add(route);
                    names.add(route.getName());
                }
            }
            if (names.isEmpty()) {
                names.add("Söker efter enheter…");
            }
            adapter.notifyDataSetChanged();
        };
        final MediaRouter.Callback callback = new MediaRouter.Callback() {
            @Override public void onRouteAdded(MediaRouter r, MediaRouter.RouteInfo route) { refresh.run(); }
            @Override public void onRouteRemoved(MediaRouter r, MediaRouter.RouteInfo route) { refresh.run(); }
            @Override public void onRouteChanged(MediaRouter r, MediaRouter.RouteInfo route) { refresh.run(); }
        };
        router.addCallback(selector, callback,
                MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY | MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN);
        refresh.run();

        AlertDialog dialog = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Casta till")
                .setAdapter(adapter, (d, which) -> {
                    if (which < routes.size()) {
                        routes.get(which).select();
                    }
                })
                .setNegativeButton("Avbryt", null)
                .create();
        dialog.setOnDismissListener(d -> router.removeCallback(callback));
        dialog.show();
    }

    private static void showControls(Activity activity, CastSession session) {
        String device = session.getCastDevice() != null ? session.getCastDevice().getFriendlyName() : "Cast";
        new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Castar till " + device)
                .setItems(new String[]{"Spela / pausa", "Koppla från"}, (d, which) -> {
                    if (which == 0) {
                        Host host = sHost;
                        if (host != null) {
                            host.setPlaying(!host.isPlaying());
                        }
                    } else {
                        sContext.getSessionManager().endCurrentSession(true);
                    }
                })
                .setNegativeButton("Stäng", null)
                .show();
    }

    public static boolean isCasting() {
        if (sContext == null) {
            return false;
        }
        CastSession session = sContext.getSessionManager().getCurrentCastSession();
        return session != null && session.isConnected();
    }

    /** A new video was opened while connected: the receiver gets it too (the phone stays silent). */
    private static void onNewSource() {
        CastSession session = connectedSession();
        if (session != null) {
            loadOnReceiver(session, true, -1);
        }
    }

    private static CastSession connectedSession() {
        if (sContext == null || sHost == null) {
            return null;
        }
        CastSession session = sContext.getSessionManager().getCurrentCastSession();
        return session != null && session.isConnected() ? session : null;
    }

    /** Sends the current track to the receiver, from the phone's position (or from atMs after a seek). */
    private static void loadOnReceiver(CastSession session, boolean newTrack, long atMs) {
        Host host = sHost;
        RemoteMediaClient remote = session != null ? session.getRemoteMediaClient() : null;
        Video video = host != null ? host.video() : null;
        MediaItemFormatInfo info = CastSource.getFormatInfo();
        if (remote == null || video == null || info == null || video.videoId == null
                || !video.videoId.equals(CastSource.getVideoId())) {
            Log.e(TAG, "Nothing to cast yet");
            return;
        }

        long durationMs = 0;
        try {
            durationMs = Long.parseLong(info.getLengthSeconds()) * 1000;
        } catch (Exception ignored) {
        }
        if (info.isLive() || durationMs <= 0) {
            Log.e(TAG, "Not castable: live=" + info.isLive() + " duration=" + durationMs);
            Toast.makeText(sAppContext, "Den här videon går inte att casta (livesändning eller okänd längd)", Toast.LENGTH_LONG).show();
            return;
        }

        long positionMs = atMs >= 0 ? atMs : host.positionMs();
        String receiverUrl = CastAudioStream.prepare(durationMs, newTrack);
        if (receiverUrl == null) {
            Toast.makeText(sAppContext, "Kunde inte starta ljudströmmen för casting", Toast.LENGTH_LONG).show();
            return;
        }

        MediaMetadata metadata = new MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK);
        metadata.putString(MediaMetadata.KEY_TITLE, video.getTitle() != null ? video.getTitle() : "");
        if (video.getAuthor() != null) {
            metadata.putString(MediaMetadata.KEY_ARTIST, video.getAuthor());
        }
        String image = video.getCardImageUrl();
        if (image != null) {
            metadata.addImage(new WebImage(Uri.parse(image)));
        }

        MediaInfo mediaInfo = new MediaInfo.Builder(receiverUrl)
                .setContentType("audio/wav")
                .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                .setStreamDuration(durationMs)
                .setMetadata(metadata)
                .build();

        boolean playing = host.isPlaying();
        sLoadedId = video.videoId;
        sLastPos = positionMs;
        sLastTick = SystemClock.elapsedRealtime();
        sLastPlaying = playing;
        sIgnoreReceiverUntil = sLastTick + 4000; // the receiver reports loading / buffering for a while

        remote.unregisterCallback(sRemoteCallback);
        remote.registerCallback(sRemoteCallback);
        remote.load(new MediaLoadRequestData.Builder()
                .setMediaInfo(mediaInfo)
                .setAutoplay(playing)
                .setCurrentTime(positionMs)
                .build()).setResultCallback(result -> {
            Log.d(TAG, "Receiver load result: code=" + result.getStatus().getStatusCode() + " ok=" + result.getStatus().isSuccess());
            if (!result.getStatus().isSuccess()) {
                Toast.makeText(sAppContext, "Mottagaren kunde inte ta emot strömmen (kod "
                        + result.getStatus().getStatusCode() + ")", Toast.LENGTH_LONG).show();
            }
        });
        Log.d(TAG, "Casting " + video.videoId + " from " + positionMs + " ms, playing=" + playing);
        startTicking();
    }

    // --- keeping the receiver in step with the phone's player ---------------------------------------------------------

    private static void startTicking() {
        if (!sTicking) {
            sTicking = true;
            MAIN.postDelayed(CastController::tick, TICK_MS);
        }
    }

    private static final Runnable sReload = () -> {
        CastSession session = connectedSession();
        if (session != null && sHost != null) {
            Log.d(TAG, "Seek: reload at " + sLastPos + " ms");
            loadOnReceiver(session, false, sLastPos);
        }
    };

    private static void tick() {
        CastSession session = connectedSession();
        Host host = sHost;
        if (session == null || host == null) {
            sTicking = false;
            return;
        }
        MAIN.postDelayed(CastController::tick, TICK_MS);

        Video video = host.video();
        RemoteMediaClient remote = session.getRemoteMediaClient();
        if (video == null || remote == null || video.videoId == null || !video.videoId.equals(sLoadedId)) {
            return; // a new track is on its way (onNewSource loads it)
        }

        long now = SystemClock.elapsedRealtime();
        long pos = host.positionMs();
        boolean playing = host.isPlaying();

        long expected = sLastPos + (sLastPlaying ? now - sLastTick : 0);
        if (Math.abs(pos - expected) > SEEK_JUMP_MS) {
            // The app seeked: the receiver is reloaded at the new place once the seeking has settled
            sLastPos = pos;
            sLastTick = now;
            MAIN.removeCallbacks(sReload);
            MAIN.postDelayed(sReload, 600);
            return;
        }
        sLastPos = pos;
        sLastTick = now;

        if (playing != sLastPlaying) {
            sLastPlaying = playing;
            sIgnoreReceiverUntil = now + ECHO_MS;
            if (playing) {
                remote.play();
            } else {
                remote.pause();
            }
        }
    }

    /** Pause / play on the receiver itself (Google Home, remote) is carried back to the phone's player. */
    private static final RemoteMediaClient.Callback sRemoteCallback = new RemoteMediaClient.Callback() {
        private int mLastState = -1;

        @Override
        public void onStatusUpdated() {
            CastSession session = sContext != null ? sContext.getSessionManager().getCurrentCastSession() : null;
            RemoteMediaClient remote = session != null ? session.getRemoteMediaClient() : null;
            MediaStatus status = remote != null ? remote.getMediaStatus() : null;
            if (status == null || status.getPlayerState() == mLastState) {
                return;
            }
            mLastState = status.getPlayerState();
            Log.d(TAG, "Receiver state=" + mLastState + " idleReason=" + status.getIdleReason());

            if (mLastState == MediaStatus.PLAYER_STATE_IDLE && status.getIdleReason() == MediaStatus.IDLE_REASON_ERROR) {
                Toast.makeText(sAppContext, "Mottagaren kunde inte spela strömmen", Toast.LENGTH_LONG).show();
                return;
            }

            Host host = sHost;
            if (host == null || SystemClock.elapsedRealtime() < sIgnoreReceiverUntil) {
                return;
            }
            if (mLastState == MediaStatus.PLAYER_STATE_PAUSED && sLastPlaying) {
                sLastPlaying = false;
                host.setPlaying(false);
            } else if (mLastState == MediaStatus.PLAYER_STATE_PLAYING && !sLastPlaying) {
                sLastPlaying = true;
                host.setPlaying(true);
            }
        }
    };

    private static final SessionManagerListener<CastSession> LISTENER = new SessionManagerListener<CastSession>() {
        @Override public void onSessionStarting(CastSession session) {}

        @Override public void onSessionStarted(CastSession session, String sessionId) {
            loadOnReceiver(session, false, -1);
        }

        @Override public void onSessionStartFailed(CastSession session, int error) {
            Log.e(TAG, "Session start failed: " + error);
        }

        @Override public void onSessionEnding(CastSession session) {}

        @Override public void onSessionEnded(CastSession session, int error) {
            // The phone's player has been running all along: it just gets its sound back
            MAIN.removeCallbacks(sReload);
            CastAudioStream.stop();
            sLoadedId = null;
        }

        @Override public void onSessionResuming(CastSession session, String sessionId) {}
        @Override public void onSessionResumed(CastSession session, boolean wasSuspended) {}
        @Override public void onSessionResumeFailed(CastSession session, int error) {}
        @Override public void onSessionSuspended(CastSession session, int reason) {}
    };
}
