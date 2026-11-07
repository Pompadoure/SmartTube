package com.liskovsoft.smartyoutubetv2.mobile.cast;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;
import androidx.mediarouter.app.MediaRouteChooserDialog;
import androidx.mediarouter.app.MediaRouteControllerDialog;
import androidx.mediarouter.media.MediaRouteSelector;
import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaLoadRequestData;
import com.google.android.gms.cast.MediaMetadata;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.SessionManagerListener;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;
import com.google.android.gms.common.images.WebImage;
import com.liskovsoft.mediaserviceinterfaces.data.MediaFormat;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.misc.CastSource;

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

    /** The cast button: the device list, or the controls (with Disconnect) while connected. */
    public static void showPicker(Activity activity) {
        if (!ensureInit(activity)) {
            Toast.makeText(activity, "Cast fungerar inte på den här enheten", Toast.LENGTH_LONG).show();
            return;
        }

        CastSession session = sContext.getSessionManager().getCurrentCastSession();
        if (session != null && session.isConnected()) {
            new MediaRouteControllerDialog(activity).show();
            return;
        }

        MediaRouteSelector selector = sContext.getMergedSelector();
        if (selector == null) {
            Toast.makeText(activity, "Cast är inte redo än", Toast.LENGTH_SHORT).show();
            return;
        }
        MediaRouteChooserDialog dialog = new MediaRouteChooserDialog(activity);
        dialog.setRouteSelector(selector);
        dialog.show();
    }

    public static boolean isCasting() {
        if (sContext == null) {
            return false;
        }
        CastSession session = sContext.getSessionManager().getCurrentCastSession();
        return session != null && session.isConnected();
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

        boolean audio = host.audioOnly();
        String[] pick = pickSource(info, audio);
        if (pick == null) {
            Log.e(TAG, "No directly playable stream for " + video.videoId + " (sabr=" + info.containsSabrFormats()
                    + " url=" + info.containsUrlFormats() + " hls=" + info.containsHlsUrl() + ")");
            Toast.makeText(sAppContext, "Den här videon går inte att casta (ingen direkt ström)", Toast.LENGTH_LONG).show();
            return;
        }

        MediaMetadata metadata = new MediaMetadata(audio ? MediaMetadata.MEDIA_TYPE_MUSIC_TRACK : MediaMetadata.MEDIA_TYPE_MOVIE);
        metadata.putString(MediaMetadata.KEY_TITLE, video.getTitle() != null ? video.getTitle() : "");
        if (video.getAuthor() != null) {
            metadata.putString(audio ? MediaMetadata.KEY_ARTIST : MediaMetadata.KEY_SUBTITLE, video.getAuthor());
        }
        String image = video.getCardImageUrl();
        if (image != null) {
            metadata.addImage(new WebImage(Uri.parse(image)));
        }

        MediaInfo.Builder builder = new MediaInfo.Builder(pick[0])
                .setContentType(pick[1])
                .setStreamType(info.isLive() ? MediaInfo.STREAM_TYPE_LIVE : MediaInfo.STREAM_TYPE_BUFFERED)
                .setMetadata(metadata);
        try {
            long seconds = Long.parseLong(info.getLengthSeconds());
            if (seconds > 0 && !info.isLive()) {
                builder.setStreamDuration(seconds * 1000);
            }
        } catch (Exception ignored) {
        }

        sLoadedPositionMs = host.positionMs();
        host.pauseLocal();
        remote.load(new MediaLoadRequestData.Builder()
                .setMediaInfo(builder.build())
                .setAutoplay(true)
                .setCurrentTime(info.isLive() ? 0 : sLoadedPositionMs)
                .build());
        Log.d(TAG, "Casting " + video.videoId + " as " + pick[1] + " from " + sLoadedPositionMs + " ms");
    }

    private static Context sAppContext;

    /** {url, mime} of the best stream the receiver can fetch itself, or null. Music prefers audio only. */
    static String[] pickSource(MediaItemFormatInfo info, boolean audioOnly) {
        if (audioOnly) {
            String bestUrl = null;
            int bestBitrate = -1;
            List<MediaFormat> adaptive = info.getAdaptiveFormats();
            if (adaptive != null) {
                for (MediaFormat format : adaptive) {
                    String mime = format.getMimeType();
                    String url = format.getUrl();
                    if (mime == null || url == null || url.isEmpty() || !mime.startsWith("audio/mp4")) {
                        continue;
                    }
                    int bitrate = 0;
                    try {
                        bitrate = Integer.parseInt(format.getBitrate());
                    } catch (Exception ignored) {
                    }
                    if (bitrate > bestBitrate) {
                        bestBitrate = bitrate;
                        bestUrl = url;
                    }
                }
            }
            if (bestUrl != null) {
                return new String[]{bestUrl, "audio/mp4"};
            }
        }

        if (info.containsUrlFormats()) {
            List<String> urls = info.createUrlList();
            if (urls != null && !urls.isEmpty() && urls.get(0) != null) {
                return new String[]{urls.get(0), "video/mp4"};
            }
        }

        if (info.containsHlsUrl() && info.getHlsManifestUrl() != null) {
            return new String[]{info.getHlsManifestUrl(), "application/x-mpegURL"};
        }

        return null;
    }

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
