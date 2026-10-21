package com.liskovsoft.smartyoutubetv2.common.exoplayer;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;
import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.ResolvingDataSource;
import androidx.media3.datasource.cronet.CronetDataSource;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.dash.DashChunkSource;
import androidx.media3.exoplayer.dash.DashMediaSource;
import androidx.media3.exoplayer.dash.DefaultDashChunkSource;
import androidx.media3.exoplayer.dash.manifest.DashManifest;
import androidx.media3.exoplayer.dash.manifest.DashManifestParser;
import androidx.media3.exoplayer.dash.manifest.Location;
import androidx.media3.exoplayer.dash.manifest.Period;
import androidx.media3.exoplayer.dash.manifest.ProgramInformation;
import androidx.media3.exoplayer.dash.manifest.ServiceDescriptionElement;
import androidx.media3.exoplayer.dash.manifest.UtcTimingElement;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import com.liskovsoft.media3.sabr.DefaultSabrChunkSource;
import com.liskovsoft.media3.sabr.SabrChunkSource;
import com.liskovsoft.media3.sabr.SabrMediaSource;
import com.liskovsoft.media3.sabr.text.JoSubtitleParserFactory;
import com.liskovsoft.media3.sabr.manifest.SabrManifest;
import com.liskovsoft.media3.sabr.manifest.SabrManifestParser;
import androidx.media3.exoplayer.smoothstreaming.DefaultSsChunkSource;
import androidx.media3.exoplayer.smoothstreaming.SsMediaSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.common.util.Util;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.sharedutils.cronet.CronetManager;
import com.liskovsoft.sharedutils.helpers.FileHelpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.okhttp.OkHttpManager;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.dash.DashManifestParser2;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.datasource.OkHttp3DataSource;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.errors.DashDefaultLoadErrorHandlingPolicy;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.errors.SabrDefaultLoadErrorHandlingPolicy;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.errors.TrackErrorFixer;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.googlecommon.common.helpers.DefaultHeaders;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;

public class ExoMediaSourceFactory {
    private static final String TAG = ExoMediaSourceFactory.class.getSimpleName();
    @SuppressLint("StaticFieldLeak")
    //private static ExoMediaSourceFactory sInstance;
    private static final int MAX_SEGMENTS_PER_LOAD = 1; // default - 1 (1-5)
    private static final String USER_AGENT = DefaultHeaders.APP_USER_AGENT;
    // JoTube M3: Media3's DefaultBandwidthMeter needs a Context (Builder), so it's created lazily (app context)
    @SuppressLint("StaticFieldLeak")
    private static DefaultBandwidthMeter sBandwidthMeter;
    private final Context mContext;
    private static final Uri DASH_MANIFEST_URI = Uri.parse("https://example.com/test.mpd");
    // JoTube M3: one byte "manifest" for the generated dynamic live manifest (DefaultDataSource reads data: uris)
    private static final Uri LIVE_MANIFEST_DATA_URI = Uri.parse("data:application/dash+xml;base64,AA==");
    private static final String DASH_MANIFEST_EXTENSION = "mpd";
    private static final String HLS_PLAYLIST_EXTENSION = "m3u8";
    private static final boolean USE_BANDWIDTH_METER = true; // JoTube: needed by the adaptive (fast start) selection

    /**
     * JoTube: the same meter must be used by the player (track selection) and the data sources (measurements).
     */
    public static synchronized DefaultBandwidthMeter getBandwidthMeter(Context context) {
        if (sBandwidthMeter == null) {
            sBandwidthMeter = new DefaultBandwidthMeter.Builder(context.getApplicationContext()).build();
        }
        return sBandwidthMeter;
    }
    private TrackErrorFixer mTrackErrorFixer;
    private DataSource.Factory mMediaDataSourceFactory;

    public ExoMediaSourceFactory(Context context) {
        mContext = context;
    }

    public MediaSource fromSabrFormatInfo(MediaItemFormatInfo formatInfo) {
        return buildSabrMediaSource(formatInfo);
    }

    public MediaSource fromDashFormatInfo(MediaItemFormatInfo formatInfo) {
        return buildDashMediaSource(formatInfo);
    }

    public MediaSource fromDashManifest(InputStream dashManifest) {
        return buildMPDMediaSource(DASH_MANIFEST_URI, dashManifest);
    }

    public MediaSource fromDashManifestUrl(String dashManifestUrl) {
        return buildMediaSource(Uri.parse(dashManifestUrl), DASH_MANIFEST_EXTENSION);
    }

    public MediaSource fromHlsPlaylist(String hlsPlaylist) {
        return buildMediaSource(Uri.parse(hlsPlaylist), HLS_PLAYLIST_EXTENSION);
    }

    public MediaSource fromUrlList(List<String> urlList) {
        MediaSource[] mediaSources = new MediaSource[urlList.size()];

        for (int i = 0; i < urlList.size(); i++) {
            mediaSources[i] = buildMediaSource(Uri.parse(urlList.get(i)), null);
        }

        //return mediaSources.length == 1 ? mediaSources[0] : new ConcatenatingMediaSource(mediaSources); // or playlist
        return mediaSources[0]; // item with max resolution
    }

    /**
     * Returns a new DataSource factory.
     *
     * @param useBandwidthMeter Whether to set the bandwidth meter as a listener to the new
     *                          DataSource factory.
     * @return A new DataSource factory.
     */
    private DataSource.Factory buildDataSourceFactory(boolean useBandwidthMeter) {
        // JoTube M3: the meter is set once on the http factory (Media3's DefaultDataSource forwards to it)
        return new DefaultDataSource.Factory(mContext, buildHttpDataSourceFactory(useBandwidthMeter));
    }

    /**
     * Returns a new HttpDataSource factory.
     *
     * @param useBandwidthMeter Whether to set the bandwidth meter as a listener to the new
     *                          DataSource factory.
     * @return A new HttpDataSource factory.
     */
    private HttpDataSource.Factory buildHttpDataSourceFactory(boolean useBandwidthMeter) {
        PlayerTweaksData tweaksData = PlayerTweaksData.instance(mContext);
        int source = tweaksData.getPlayerDataSource();
        DefaultBandwidthMeter bandwidthMeter = useBandwidthMeter ? getBandwidthMeter(mContext) : null;
        return source == PlayerTweaksData.PLAYER_DATA_SOURCE_OKHTTP ? buildOkHttpDataSourceFactory(bandwidthMeter) :
                        source == PlayerTweaksData.PLAYER_DATA_SOURCE_CRONET && CronetManager.getEngine(mContext) != null ? buildCronetDataSourceFactory(bandwidthMeter) :
                                buildDefaultHttpDataSourceFactory(bandwidthMeter);
    }

    private MediaSource buildMediaSource(Uri uri, String overrideExtension) {
        @C.ContentType int type = TextUtils.isEmpty(overrideExtension) ? Util.inferContentType(uri) : Util.inferContentTypeForExtension(overrideExtension);
        MediaItem mediaItem = MediaItem.fromUri(uri);
        MediaSource source;
        switch (type) {
            case C.CONTENT_TYPE_SS:
                source = new SsMediaSource.Factory(
                                getSsChunkSourceFactory(),
                                getMediaDataSourceFactory()
                        )
                                .createMediaSource(mediaItem);
                break;
            case C.CONTENT_TYPE_DASH:
                source = new DashMediaSource.Factory(
                                getDashChunkSourceFactory(),
                                getMediaDataSourceFactory()
                        )
                                .setManifestParser(new LiveDashManifestParser()) // Don't make static! Need state reset for each live source.
                                .setLoadErrorHandlingPolicy(new DashDefaultLoadErrorHandlingPolicy())
                                .setSubtitleParserFactory(new JoSubtitleParserFactory()) // JoTube: the fork's WebVTT fixes
                                .createMediaSource(mediaItem);
                break;
            case C.CONTENT_TYPE_HLS:
                source = new HlsMediaSource.Factory(getMediaDataSourceFactory())
                        .setSubtitleParserFactory(new JoSubtitleParserFactory()) // JoTube: the fork's WebVTT fixes
                        .createMediaSource(mediaItem);
                break;
            case C.CONTENT_TYPE_OTHER:
                source = new ProgressiveMediaSource.Factory(getMediaDataSourceFactory(), new DefaultExtractorsFactory())
                        .createMediaSource(mediaItem);
                break;
            default: {
                throw new IllegalStateException("Unsupported type: " + type);
            }
        }
        if (mTrackErrorFixer != null) {
            source.addEventListener(Utils.sHandler, mTrackErrorFixer);
        }
        return source;
    }

    private MediaSource buildSabrMediaSource(MediaItemFormatInfo formatInfo) {
        // Are you using FrameworkSampleSource or ExtractorSampleSource when you build your player?
        SabrMediaSource sabrSource = new SabrMediaSource.Factory(
                getSabrChunkSourceFactory(),
                null
        )
                .setLoadErrorHandlingPolicy(new SabrDefaultLoadErrorHandlingPolicy())
                .createMediaSource(getSabrManifest(formatInfo));
        if (mTrackErrorFixer != null) {
            sabrSource.addEventListener(Utils.sHandler, mTrackErrorFixer.forVideo(formatInfo.getVideoId()));
        }
        return sabrSource;
    }

    private MediaSource buildDashMediaSource(MediaItemFormatInfo formatInfo) {
        // Are you using FrameworkSampleSource or ExtractorSampleSource when you build your player?
        // JoTube M3: the fork added the visitor cookie to subtitle requests (DashManifest.visitorCookie, subtitles bot
        // check). Media3 has no such field: a resolving data source adds it to the timedtext requests of this source.
        String visitorCookie = formatInfo.getVisitorCookie();
        DashChunkSource.Factory chunkSourceFactory = TextUtils.isEmpty(visitorCookie) ? getDashChunkSourceFactory() :
                new DefaultDashChunkSource.Factory(new ResolvingDataSource.Factory(getMediaDataSourceFactory(),
                        dataSpec -> isSubtitleRequest(dataSpec) ?
                                dataSpec.withAdditionalHeaders(Collections.singletonMap("Cookie", visitorCookie)) : dataSpec),
                        MAX_SEGMENTS_PER_LOAD);
        String mediaId = formatInfo.getVideoId() != null ? formatInfo.getVideoId() : MediaItem.DEFAULT_MEDIA_ID;
        DashMediaSource dashSource;

        if (DashManifestParser2.isDynamicLive(formatInfo)) {
            // JoTube M3: an ongoing live stream (DashManifestParser2.isDynamicLive). Media3 refuses a sideloaded dynamic
            // manifest (and wouldn't move the live window), so the generated manifest is "loaded" once from a data:
            // uri; the parser ignores the bytes and builds it then (its direct UTC timing is the device time at load end).
            // No refresh is needed: without a timeline Media3 moves the window itself (simulated refresh), the segments
            // are computed from the wall clock.
            dashSource = new DashMediaSource.Factory(
                    chunkSourceFactory,
                    getMediaDataSourceFactory()
            )
                    .setManifestParser((uri, inputStream) -> getManifest(formatInfo))
                    .setLoadErrorHandlingPolicy(new DashDefaultLoadErrorHandlingPolicy())
                    .setSubtitleParserFactory(new JoSubtitleParserFactory()) // JoTube: the fork's WebVTT fixes
                    .createMediaSource(new MediaItem.Builder()
                            .setUri(LIVE_MANIFEST_DATA_URI)
                            .setMimeType(MimeTypes.APPLICATION_MPD)
                            .setMediaId(mediaId)
                            .build());
        } else {
            dashSource = new DashMediaSource.Factory(
                    chunkSourceFactory,
                    null
            )
                    .setLoadErrorHandlingPolicy(new DashDefaultLoadErrorHandlingPolicy())
                    .setSubtitleParserFactory(new JoSubtitleParserFactory()) // JoTube: the fork's WebVTT fixes
                    .createMediaSource(getManifest(formatInfo), new MediaItem.Builder().setMediaId(mediaId).build()); // JoTube M3: the id names the source in the Shorts queue
        }
        if (mTrackErrorFixer != null) {
            dashSource.addEventListener(Utils.sHandler, mTrackErrorFixer.forVideo(formatInfo.getVideoId()));
        }
        return dashSource;
    }

    private MediaSource buildMPDMediaSource(Uri uri, InputStream mpdContent) {
        // Are you using FrameworkSampleSource or ExtractorSampleSource when you build your player?
        DashMediaSource dashSource = new DashMediaSource.Factory(
                getDashChunkSourceFactory(),
                null
        )
                .setLoadErrorHandlingPolicy(new DashDefaultLoadErrorHandlingPolicy())
                .setSubtitleParserFactory(new JoSubtitleParserFactory()) // JoTube: the fork's WebVTT fixes
                .createMediaSource(getManifest(uri, mpdContent));
        if (mTrackErrorFixer != null) {
            dashSource.addEventListener(Utils.sHandler, mTrackErrorFixer);
        }
        return dashSource;
    }

    private MediaSource buildMPDMediaSource(Uri uri, String mpdContent) {
        if (mpdContent == null || mpdContent.isEmpty()) {
            Log.e(TAG, "Can't build media source. MpdContent is null or empty. " + mpdContent);
            return null;
        }

        // Are you using FrameworkSampleSource or ExtractorSampleSource when you build your player?
        DashMediaSource dashSource = new DashMediaSource.Factory(
                new DefaultDashChunkSource.Factory(getMediaDataSourceFactory()),
                null
        )
                .setSubtitleParserFactory(new JoSubtitleParserFactory()) // JoTube: the fork's WebVTT fixes
                .createMediaSource(getManifest(uri, mpdContent));
        if (mTrackErrorFixer != null) {
            dashSource.addEventListener(Utils.sHandler, mTrackErrorFixer);
        }
        return dashSource;
    }

    private static boolean isSubtitleRequest(DataSpec dataSpec) {
        String path = dataSpec.uri.getPath();
        return path != null && path.contains("timedtext");
    }

    private SabrManifest getSabrManifest(MediaItemFormatInfo formatInfo) {
        SabrManifestParser parser = new SabrManifestParser();
        return parser.parse(formatInfo);
    }

    private DashManifest getManifest(MediaItemFormatInfo formatInfo) {
        DashManifestParser2 parser = new DashManifestParser2();
        return parser.parse(formatInfo);
    }

    private DashManifest getManifest(Uri uri, InputStream mpdContent) {
        DashManifestParser parser = new StaticDashManifestParser();
        DashManifest result;
        try {
            result = parser.parse(uri, mpdContent);
        } catch (IOException e) {
            throw new IllegalStateException("Malformed mpd file:\n" + mpdContent, e);
        }
        return result;
    }

    private DashManifest getManifest(Uri uri, String mpdContent) {
        DashManifestParser parser = new StaticDashManifestParser();
        DashManifest result;
        try {
            result = parser.parse(uri, FileHelpers.toStream(mpdContent));
        } catch (IOException e) {
            throw new IllegalStateException("Malformed mpd file:\n" + mpdContent, e);
        }
        return result;
    }

    /**
     * Use OkHttp for networking
     */
    private HttpDataSource.Factory buildOkHttpDataSourceFactory(DefaultBandwidthMeter bandwidthMeter) {
        OkHttp3DataSource.Factory dataSourceFactory = new OkHttp3DataSource.Factory(OkHttpManager.instance().getClient())
                .setUserAgent(USER_AGENT)
                .setTransferListener(bandwidthMeter);
        addCommonHeaders(dataSourceFactory);
        return dataSourceFactory;
    }

    private HttpDataSource.Factory buildCronetDataSourceFactory(DefaultBandwidthMeter bandwidthMeter) {
        // JoTube M3: Media3 CronetDataSource on the app's own engine (CronetManager), same timeouts as before.
        // 2.10 had no fallback factory here either (null).
        CronetDataSource.Factory dataSourceFactory =
                new CronetDataSource.Factory(CronetManager.getEngine(mContext), Executors.newSingleThreadExecutor())
                        .setUserAgent(USER_AGENT)
                        .setConnectionTimeoutMs((int) OkHttpManager.getConnectTimeoutMs())
                        .setReadTimeoutMs((int) OkHttpManager.getReadTimeoutMs())
                        .setResetTimeoutOnRedirects(true)
                        .setTransferListener(bandwidthMeter);
        addCommonHeaders(dataSourceFactory);
        return dataSourceFactory;
    }

    /**
     * Use built-in component for networking
     */
    private HttpDataSource.Factory buildDefaultHttpDataSourceFactory(DefaultBandwidthMeter bandwidthMeter) {
        DefaultHttpDataSource.Factory dataSourceFactory = new DefaultHttpDataSource.Factory()
                .setUserAgent(USER_AGENT)
                .setConnectTimeoutMs((int) OkHttpManager.getConnectTimeoutMs())
                .setReadTimeoutMs((int) OkHttpManager.getReadTimeoutMs())
                .setAllowCrossProtocolRedirects(true)
                .setTransferListener(bandwidthMeter);

        addCommonHeaders(dataSourceFactory); // cause troubles for some users
        return dataSourceFactory;
    }

    private static void addCommonHeaders(HttpDataSource.Factory dataSourceFactory) {
        // Doesn't work
        // Trying to fix 429 error (too many requests)
        //String authorization = RetrofitOkHttpHelper.getAuthHeaders().get("Authorization");
        //
        //if (authorization != null) {
        //    dataSourceFactory.getDefaultRequestProperties().set("Authorization", authorization);
        //}

        //HeaderManager headerManager = new HeaderManager(context);
        //HashMap<String, String> headers = headerManager.getHeaders();

        // NOTE: "Accept-Encoding" should not be set manually (gzip is added by default).

        //for (String header : headers.keySet()) {
        //    if (EXO_HEADERS.contains(header)) {
        //        dataSourceFactory.getDefaultRequestProperties().set(header, headers.get(header));
        //    }
        //}

        // Emulate browser request
        //dataSourceFactory.getDefaultRequestProperties().set("accept", "*/*");
        //dataSourceFactory.getDefaultRequestProperties().set("accept-encoding", "identity"); // Next won't work: gzip, deflate, br
        //dataSourceFactory.getDefaultRequestProperties().set("accept-language", "en-US,en;q=0.9");
        //dataSourceFactory.getDefaultRequestProperties().set("dnt", "1");
        //dataSourceFactory.getDefaultRequestProperties().set("origin", "https://www.youtube.com");
        //dataSourceFactory.getDefaultRequestProperties().set("referer", "https://www.youtube.com/");
        //dataSourceFactory.getDefaultRequestProperties().set("sec-fetch-dest", "empty");
        //dataSourceFactory.getDefaultRequestProperties().set("sec-fetch-mode", "cors");
        //dataSourceFactory.getDefaultRequestProperties().set("sec-fetch-site", "cross-site");

        // WARN: Compression won't work with legacy streams.
        // "Accept-Encoding" should not be set manually (gzip is added by default).
        // Otherwise you should do decompression yourself.
        // Source: https://stackoverflow.com/questions/18898959/httpurlconnection-not-decompressing-gzip/42346308#42346308
        //dataSourceFactory.getDefaultRequestProperties().set("Accept-Encoding", AppConstants.ACCEPT_ENCODING_DEFAULT);
    }

    public void setTrackErrorFixer(TrackErrorFixer trackErrorFixer) {
        mTrackErrorFixer = trackErrorFixer;
    }

    public void release() {
        mMediaDataSourceFactory = null;
    }

    @NonNull
    private DefaultSsChunkSource.Factory getSsChunkSourceFactory() {
        return new DefaultSsChunkSource.Factory(getMediaDataSourceFactory());
    }

    @NonNull
    private SabrChunkSource.Factory getSabrChunkSourceFactory() {
        return new DefaultSabrChunkSource.Factory(getMediaDataSourceFactory(), MAX_SEGMENTS_PER_LOAD);
    }

    @NonNull
    private DashChunkSource.Factory getDashChunkSourceFactory() {
        return new DefaultDashChunkSource.Factory(getMediaDataSourceFactory(), MAX_SEGMENTS_PER_LOAD);
    }

    private DataSource.Factory getMediaDataSourceFactory() {
        if (mMediaDataSourceFactory == null) {
            mMediaDataSourceFactory = buildDataSourceFactory(USE_BANDWIDTH_METER);
        }

        return mMediaDataSourceFactory;
    }

    // JoTube M3: Media3 1.11 calls the overload with a Location list (the Uri one is deprecated and unused)
    private static class StaticDashManifestParser extends DashManifestParser {
        @Override
        protected DashManifest buildMediaPresentationDescription(
                long availabilityStartTime,
                long durationMs,
                long minBufferTimeMs,
                boolean dynamic,
                long minUpdateTimeMs,
                long timeShiftBufferDepthMs,
                long suggestedPresentationDelayMs,
                long publishTimeMs,
                ProgramInformation programInformation,
                UtcTimingElement utcTiming,
                ServiceDescriptionElement serviceDescription,
                List<Period> periods,
                List<Location> locations) {
            return new DashManifest(
                    availabilityStartTime,
                    durationMs,
                    minBufferTimeMs,
                    false,
                    minUpdateTimeMs,
                    timeShiftBufferDepthMs,
                    suggestedPresentationDelayMs,
                    publishTimeMs,
                    programInformation,
                    utcTiming,
                    serviceDescription,
                    periods,
                    locations);
        }
    }

    // EXO: 2.13
    //private static class StaticDashManifestParser extends DashManifestParser {
    //    @Override
    //    protected DashManifest buildMediaPresentationDescription(
    //            long availabilityStartTime,
    //            long durationMs,
    //            long minBufferTimeMs,
    //            boolean dynamic,
    //            long minUpdateTimeMs,
    //            long timeShiftBufferDepthMs,
    //            long suggestedPresentationDelayMs,
    //            long publishTimeMs,
    //            @Nullable ProgramInformation programInformation,
    //            @Nullable UtcTimingElement utcTiming,
    //            @Nullable ServiceDescriptionElement serviceDescription,
    //            @Nullable Uri location,
    //            List<Period> periods) {
    //        return new DashManifest(
    //                availabilityStartTime,
    //                durationMs,
    //                minBufferTimeMs,
    //                false,
    //                minUpdateTimeMs,
    //                timeShiftBufferDepthMs,
    //                suggestedPresentationDelayMs,
    //                publishTimeMs,
    //                programInformation,
    //                utcTiming,
    //                serviceDescription,
    //                location,
    //                periods);
    //    }
    //}
}
