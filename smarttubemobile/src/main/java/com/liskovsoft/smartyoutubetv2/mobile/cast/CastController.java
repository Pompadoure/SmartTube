package com.liskovsoft.smartyoutubetv2.mobile.cast;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import androidx.mediarouter.media.MediaRouteSelector;
import androidx.mediarouter.media.MediaRouter;
import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaLoadRequestData;
import com.google.android.gms.cast.MediaMetadata;
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
 * JoTube: Cast from the phone. The device picker is the stock MediaRouter dialog. When a Cast device is picked, the
 * stream URL of the current video (or only its audio in the music player) is sent to the Default Media Receiver and
 * the phone's own playback pauses. When the session ends the phone continues from where the receiver was.
 */
public final class CastController {
    private static final String TAG = "JoTubeCast";

    /** What the player screen offers to the cast code. */
    public interface Host {
        Video video();
        long positionMs();
        boolean audioOnly();
        void pauseLocal();
        void resumeLocal(long positionMs);
    }

    private static CastContext sContext;
    private static Context sAppContext;
    private static Host sHost;
    private static boolean sListening;
    private static long sLoadedPositionMs;

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
            CastSource.setListener(() -> new Handler(Looper.getMainLooper()).post(CastController::onNewSource));
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
                        RemoteMediaClient remote = session.getRemoteMediaClient();
                        if (remote != null) {
                            remote.togglePlayback();
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

    /** A new video was opened while connected: send it to the receiver too and keep the phone quiet. */
    private static void onNewSource() {
        if (sContext == null || sHost == null) {
            return;
        }
        CastSession session = sContext.getSessionManager().getCurrentCastSession();
        if (session != null && session.isConnected()) {
            loadOnReceiver(session);
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                Host host = sHost;
                if (host != null && isCasting()) {
                    host.pauseLocal();
                }
            }, 1500);
        }
    }

    private static void loadOnReceiver(CastSession session) {
        Host host = sHost;
        if (host == null || session == null) {
            return;
        }
        RemoteMediaClient remote = session.getRemoteMediaClient();
        Video video = host.video();
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
        if (info.isLive() || durationMs <= 0 || !(info.containsSabrFormats() || info.containsDashFormats())) {
            Log.e(TAG, "Not castable: live=" + info.isLive() + " duration=" + durationMs + " sabr=" + info.containsSabrFormats()
                    + " dash=" + info.containsDashFormats());
            Toast.makeText(sAppContext, "Den här videon går inte att casta (livesändning eller okänt format)", Toast.LENGTH_LONG).show();
            return;
        }

        // The receiver gets the sound as a stream from this phone (the app plays the track with its own engine)
        sLoadedPositionMs = host.positionMs();
        String receiverUrl = CastAudioStream.start(sAppContext, info, sLoadedPositionMs, durationMs);
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

        host.pauseLocal();
        remote.unregisterCallback(sRemoteCallback);
        remote.registerCallback(sRemoteCallback);
        remote.load(new MediaLoadRequestData.Builder()
                .setMediaInfo(mediaInfo)
                .setAutoplay(true)
                .setCurrentTime(sLoadedPositionMs)
                .build()).setResultCallback(result -> {
            Log.d(TAG, "Receiver load result: code=" + result.getStatus().getStatusCode() + " ok=" + result.getStatus().isSuccess());
            if (!result.getStatus().isSuccess()) {
                Toast.makeText(sAppContext, "Mottagaren kunde inte ta emot strömmen (kod "
                        + result.getStatus().getStatusCode() + ")", Toast.LENGTH_LONG).show();
            }
        });
        Log.d(TAG, "Casting " + video.videoId + " from " + sLoadedPositionMs + " ms");
    }

    /** Tells why the receiver stopped (a rejected stream ends as IDLE with an error). */
    private static final RemoteMediaClient.Callback sRemoteCallback = new RemoteMediaClient.Callback() {
        private int mLastState = -1;

        @Override
        public void onStatusUpdated() {
            RemoteMediaClient remote = sContext != null && sContext.getSessionManager().getCurrentCastSession() != null
                    ? sContext.getSessionManager().getCurrentCastSession().getRemoteMediaClient() : null;
            com.google.android.gms.cast.MediaStatus status = remote != null ? remote.getMediaStatus() : null;
            if (status == null || status.getPlayerState() == mLastState) {
                return;
            }
            mLastState = status.getPlayerState();
            Log.d(TAG, "Receiver state=" + status.getPlayerState() + " idleReason=" + status.getIdleReason());
            if (status.getPlayerState() == com.google.android.gms.cast.MediaStatus.PLAYER_STATE_IDLE
                    && status.getIdleReason() == com.google.android.gms.cast.MediaStatus.IDLE_REASON_ERROR) {
                Toast.makeText(sAppContext, "Mottagaren kunde inte spela strömmen", Toast.LENGTH_LONG).show();
            }
        }
    };

    private static final SessionManagerListener<CastSession> LISTENER = new SessionManagerListener<CastSession>() {
        @Override public void onSessionStarting(CastSession session) {}

        @Override public void onSessionStarted(CastSession session, String sessionId) {
            loadOnReceiver(session);
        }

        @Override public void onSessionStartFailed(CastSession session, int error) {
            Log.e(TAG, "Session start failed: " + error);
        }

        @Override public void onSessionEnding(CastSession session) {
            RemoteMediaClient remote = session != null ? session.getRemoteMediaClient() : null;
            if (remote != null && remote.hasMediaSession()) {
                sLoadedPositionMs = remote.getApproximateStreamPosition();
            }
        }

        @Override public void onSessionEnded(CastSession session, int error) {
            CastAudioStream.stop();
            Host host = sHost;
            if (host != null) {
                host.resumeLocal(sLoadedPositionMs);
            }
        }

        @Override public void onSessionResuming(CastSession session, String sessionId) {}
        @Override public void onSessionResumed(CastSession session, boolean wasSuspended) {}
        @Override public void onSessionResumeFailed(CastSession session, int error) {}
        @Override public void onSessionSuspended(CastSession session, int reason) {}
    };
}
