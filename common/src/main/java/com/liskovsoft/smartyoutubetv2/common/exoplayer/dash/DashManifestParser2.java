package com.liskovsoft.smartyoutubetv2.common.exoplayer.dash;

import android.text.TextUtils;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Metadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.exoplayer.dash.manifest.AdaptationSet;
import androidx.media3.exoplayer.dash.manifest.BaseUrl;
import androidx.media3.exoplayer.dash.manifest.DashManifest;
import androidx.media3.exoplayer.dash.manifest.Period;
import androidx.media3.exoplayer.dash.manifest.RangedUri;
import androidx.media3.exoplayer.dash.manifest.Representation;
import androidx.media3.exoplayer.dash.manifest.SegmentBase;
import androidx.media3.exoplayer.dash.manifest.SegmentBase.SegmentList;
import androidx.media3.exoplayer.dash.manifest.SegmentBase.SegmentTemplate;
import androidx.media3.exoplayer.dash.manifest.SegmentBase.SegmentTimelineElement;
import androidx.media3.exoplayer.dash.manifest.SegmentBase.SingleSegmentBase;
import androidx.media3.exoplayer.dash.manifest.UrlTemplate;
import androidx.media3.exoplayer.dash.manifest.UtcTimingElement;
import androidx.media3.common.util.Util;
import com.liskovsoft.media3.sabr.parser.models.SabrFormatMetadata;
import com.liskovsoft.mediaserviceinterfaces.data.MediaFormat;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.mediaserviceinterfaces.data.MediaSubtitle;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.youtubeapi.formatbuilders.mpdbuilder.MediaFormatComparator;
import com.liskovsoft.youtubeapi.formatbuilders.utils.ITagUtils;
import com.liskovsoft.youtubeapi.formatbuilders.utils.MediaFormatUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeSet;

/**
 * JoTube M3: Media3 port of the fork's {@code DashManifestParser2} (exoplayer-amzn-2.10.6 dash module). Builds a
 * {@link DashManifest} straight from {@link MediaItemFormatInfo} (no XML) for the non-SABR DASH path.
 * Changes against 2.10.6: Media3 manifest constructors (BaseUrl lists, SegmentTemplate/SegmentList extra args),
 * Format.Builder instead of the createXxxContainerFormat factories, isDrc/lastModified in {@link SabrFormatMetadata},
 * no DRM (YouTube formats never carry DRM here), the visitor cookie is no longer part of the manifest (see
 * {@link #getVisitorCookie()}; ExoMediaSourceFactory adds it to subtitle requests).
 */
public class DashManifestParser2 {
    private static final String TAG = DashManifestParser2.class.getSimpleName();
    private int mId;
    private static final String NULL_INDEX_RANGE = "0-0";
    private static final String NULL_CONTENT_LENGTH = "0";
    private static final int MAX_DURATION_SEC = 48 * 60 * 60;
    /** Ongoing live: how far behind the live edge Media3 aims to play (the official app buffer). */
    private static final long LIVE_PRESENTATION_DELAY_MS = 15_000;
    private boolean mIsDynamicLive;
    private MediaItemFormatInfo mFormatInfo;
    private Set<MediaFormat> mMP4Videos;
    private Set<MediaFormat> mWEBMVideos;
    private Map<String, Set<MediaFormat>> mMP4Audios;
    private Map<String, Set<MediaFormat>> mWEBMAudios;
    private List<MediaSubtitle> mSubs;

    public DashManifest parse(@NonNull MediaItemFormatInfo formatInfo) {
        mFormatInfo = formatInfo;
        MediaFormatComparator comp = new MediaFormatComparator();
        mMP4Videos = new TreeSet<>(comp);
        mWEBMVideos = new TreeSet<>(comp);
        mMP4Audios = new HashMap<>();
        mWEBMAudios = new HashMap<>();
        mSubs = new ArrayList<>();
        mIsDynamicLive = isDynamicLive(formatInfo);
        return parseDashManifest(formatInfo);
    }

    /**
     * JoTube M3: an ongoing live stream with the dash info ("seeking support": the first segment of the rewind window, its
     * wall clock time and the segment duration) becomes a dynamic manifest. The static one ended at lengthSeconds at
     * fetch time: Media3 reached STATE_ENDED ~5 s after the start and autoplay moved on (device logs 2026-10-16 and
     * 2026-10-21). Dynamic: Media3 follows the live edge from the wall clock and the whole window stays seekable.
     */
    public static boolean isDynamicLive(MediaItemFormatInfo formatInfo) {
        return formatInfo.isLive() && formatInfo.getStartTimeMs() > 0 && formatInfo.getSegmentDurationUs() > 0
                && formatInfo.getStartSegmentNum() >= 0 && areAllLiveMedia(formatInfo.getAdaptiveFormats());
    }

    /**
     * Only live formats get a segment template. Any other one would get an explicit index and Media3 would stop moving
     * the live window (DashMediaSource.isIndexExplicit): then the static manifest is used, like before.
     */
    private static boolean areAllLiveMedia(List<MediaFormat> formats) {
        if (formats == null || formats.isEmpty()) {
            return false;
        }

        for (MediaFormat format : formats) {
            if (!MediaFormatUtils.isLiveMedia(format)) {
                return false;
            }
        }

        return true;
    }

    /** The visitor cookie of the last parsed format info (the fork kept it in DashManifest.visitorCookie). */
    public String getVisitorCookie() {
        return mFormatInfo != null ? mFormatInfo.getVisitorCookie() : null;
    }

    private DashManifest parseDashManifest(MediaItemFormatInfo formatInfo) {
        long availabilityStartTime = C.TIME_UNSET;
        long durationMs = getDurationMs(formatInfo);
        long minBufferTimeMs = 1500; // "PT1.500S"
        long timeShiftBufferDepthMs = C.TIME_UNSET;
        long suggestedPresentationDelayMs = C.TIME_UNSET;
        long publishTimeMs = C.TIME_UNSET;
        boolean dynamic = false;
        long minUpdateTimeMs = C.TIME_UNSET; // 3155690800000L, "P100Y" no refresh (there is no dash url)
        UtcTimingElement utcTiming = null;

        if (mIsDynamicLive) {
            // The segment startSegmentNum started at startTimeMs (device clock, see DashInfoBase). The device clock is
            // also the time source (direct UTC timing), so the live edge is lastSegmentNum like at fetch time.
            availabilityStartTime = formatInfo.getStartTimeMs();
            durationMs = C.TIME_UNSET;
            dynamic = true;
            suggestedPresentationDelayMs = LIVE_PRESENTATION_DELAY_MS;
            utcTiming = new UtcTimingElement("urn:mpeg:dash:utc:direct:2014", formatUtc(System.currentTimeMillis()));
        }

        List<Period> periods = new ArrayList<>();

        Pair<Period, Long> periodWithDurationMs = parsePeriod(formatInfo);
        if (periodWithDurationMs != null) {
            Period period = periodWithDurationMs.first;
            periods.add(period);
        }

        return new DashManifest(
                availabilityStartTime,
                durationMs,
                minBufferTimeMs,
                dynamic,
                minUpdateTimeMs,
                timeShiftBufferDepthMs,
                suggestedPresentationDelayMs,
                publishTimeMs,
                /* programInformation= */ null,
                utcTiming,
                /* serviceDescription= */ null,
                /* location= */ null,
                periods);
    }

    private static String formatUtc(long timeMs) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(timeMs));
    }

    private static long getDurationMs(MediaItemFormatInfo formatInfo) {
        long lenSeconds = Helpers.parseLong(formatInfo.getLengthSeconds());
        return lenSeconds > 0 ? lenSeconds * 1_000 : C.TIME_UNSET;
    }

    private Pair<Period, Long> parsePeriod(MediaItemFormatInfo formatInfo) {
        String id = formatInfo.getVideoId();
        long startMs = 0; // Should add real start time or make it unset?
        long durationMs = getDurationMs(formatInfo);
        List<AdaptationSet> adaptationSets = new ArrayList<>();

        for (MediaFormat format : formatInfo.getAdaptiveFormats()) {
            append(format);
        }

        // JoTube M3: not on a dynamic live: a subtitle file is a single (explicit) segment and Media3 stops moving the live
        // window when any adaptation set has an explicit index (DashMediaSource.isIndexExplicit)
        if (formatInfo.getSubtitles() != null && !mIsDynamicLive) {
            append(formatInfo.getSubtitles());
        }

        // MXPlayer fix: write high quality formats first
        if (!mMP4Videos.isEmpty()) {
            adaptationSets.add(parseAdaptationSet(mMP4Videos));
        }
        if (!mWEBMVideos.isEmpty()) {
            adaptationSets.add(parseAdaptationSet(mWEBMVideos));
        }

        for (Set<MediaFormat> formats : mMP4Audios.values()) {
            adaptationSets.add(parseAdaptationSet(formats));
        }

        for (Set<MediaFormat> formats : mWEBMAudios.values()) {
            adaptationSets.add(parseAdaptationSet(formats));
        }

        for (MediaSubtitle subtitle : mSubs) {
            adaptationSets.add(parseAdaptationSet(Collections.singletonList(subtitle)));
        }

        return Pair.create(new Period(id, startMs, adaptationSets), durationMs);
    }

    private AdaptationSet parseAdaptationSet(Set<MediaFormat> formats) {
        int id = mId++;
        int contentType = C.TRACK_TYPE_UNKNOWN;
        String label = null;
        List<RepresentationInfo> representationInfos = new ArrayList<>();

        for (MediaFormat format : formats) {
            RepresentationInfo representationInfo = parseRepresentation(format);
            if (contentType == C.TRACK_TYPE_UNKNOWN) {
                contentType = getContentType(representationInfo.format);
            }
            representationInfos.add(representationInfo);
        }

        // Build the representations.
        List<Representation> representations = new ArrayList<>(representationInfos.size());
        for (int i = 0; i < representationInfos.size(); i++) {
            representations.add(
                    buildRepresentation(
                            representationInfos.get(i),
                            label));
        }

        return new AdaptationSet(id, contentType, representations, new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
    }

    private AdaptationSet parseAdaptationSet(List<MediaSubtitle> formats) {
        int id = mId++;
        int contentType = C.TRACK_TYPE_UNKNOWN;
        String label = null;
        List<RepresentationInfo> representationInfos = new ArrayList<>();

        for (MediaSubtitle format : formats) {
            RepresentationInfo representationInfo = parseRepresentation(format);
            if (contentType == C.TRACK_TYPE_UNKNOWN) {
                contentType = getContentType(representationInfo.format);
            }
            representationInfos.add(representationInfo);
        }

        // Build the representations.
        List<Representation> representations = new ArrayList<>(representationInfos.size());
        for (int i = 0; i < representationInfos.size(); i++) {
            representations.add(
                    buildRepresentation(
                            representationInfos.get(i),
                            label));
        }

        return new AdaptationSet(id, contentType, representations, new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
    }

    private SegmentTemplate parseSegmentTemplate(MediaFormat format) {
        int unitsPerSecond = 1_000_000;

        // Present on live streams only.
        int segmentDurationUs = mFormatInfo.getSegmentDurationUs();

        if (segmentDurationUs <= 0) {
            // Inaccurate. Present on past (!) live streams.
            segmentDurationUs = format.getTargetDurationSec() * 1_000_000;
        }

        int lengthSeconds = Integer.parseInt(mFormatInfo.getLengthSeconds());

        if (mFormatInfo.isLive() || lengthSeconds <= 0) {
            // For premiere streams (length > 0) or regular streams (length == 0) set window that exceeds normal limits - 48hrs
            lengthSeconds = MAX_DURATION_SEC;
        }

        // To make long streams (12hrs) seekable we should decrease size of the segment a bit
        //long segmentDurationUnits = (long) targetDurationSec * unitsPerSecond * 9999 / 10000;
        int segmentDurationUnits = (int)(segmentDurationUs * (long) unitsPerSecond / 1_000_000);
        // Increase count a bit to compensate previous tweak
        //long segmentCount = (long) lengthSeconds / targetDurationSec * 10000 / 9999;
        //int segmentCount = (int)(lengthSeconds * (long) unitsPerSecond / segmentDurationUnits);
        int segmentCount = (int) Math.ceil(lengthSeconds * (double) unitsPerSecond / segmentDurationUnits);
        // Increase offset a bit to compensate previous tweaks
        // Streams to check:
        // https://www.youtube.com/watch?v=drdemkJpgao
        long offsetUnits = (long) segmentDurationUnits * mFormatInfo.getStartSegmentNum();

        long timescale = unitsPerSecond;
        long presentationTimeOffset = offsetUnits;
        long duration = segmentDurationUnits;
        long startNumber = mFormatInfo.getStartSegmentNum();
        long endNumber = C.INDEX_UNSET;
        UrlTemplate mediaTemplate = UrlTemplate.compile(format.getUrl() + "&sq=$Number$");
        //UrlTemplate initializationTemplate = UrlTemplate.compile(format.getOtfInitUrl()); // ?
        UrlTemplate initializationTemplate = null; // ?

        RangedUri initialization = parseRangedUrl(format.getSourceUrl(), format.getInit());

        if (mIsDynamicLive) {
            // No timeline, no end: segment n starts at (n - startNumber) * duration after availabilityStartTime and is
            // available once it is complete (Media3 MultiSegmentBase.getAvailableSegmentCount). Same time mapping as the
            // static timeline below (presentationTimeOffset = startNumber * duration).
            return new SegmentTemplate(
                    initialization,
                    timescale,
                    presentationTimeOffset,
                    startNumber,
                    endNumber,
                    duration,
                    /* segmentTimeline= */ null,
                    /* availabilityTimeOffsetUs= */ 0,
                    initializationTemplate,
                    mediaTemplate,
                    /* timeShiftBufferDepthUs= */ C.TIME_UNSET,
                    /* periodStartUnixTimeUs= */ Util.msToUs(mFormatInfo.getStartTimeMs()));
        }

        List<SegmentTimelineElement> timeline = parseSegmentTimeline(offsetUnits, segmentDurationUnits, segmentCount);

        return new SegmentTemplate(
                initialization,
                timescale,
                presentationTimeOffset,
                startNumber,
                endNumber,
                duration,
                timeline,
                /* availabilityTimeOffsetUs= */ C.TIME_UNSET,
                initializationTemplate,
                mediaTemplate,
                /* timeShiftBufferDepthUs= */ C.TIME_UNSET,
                /* periodStartUnixTimeUs= */ C.TIME_UNSET);
    }

    private SegmentList parseSegmentList(MediaFormat format) {
        long timescale = 1;
        long presentationTimeOffset = 0;
        long duration = C.TIME_UNSET;
        long startNumber = 1;

        RangedUri initialization = parseRangedUrl(format.getSourceUrl(), format.getInit());

        List<SegmentTimelineElement> timeline = parseSegmentTimeline(format);

        List<RangedUri> segments = parseSegmentUrl(format);

        return new SegmentList(initialization, timescale, presentationTimeOffset,
                startNumber, duration, timeline, /* availabilityTimeOffsetUs= */ C.TIME_UNSET, segments,
                /* timeShiftBufferDepthUs= */ C.TIME_UNSET, /* periodStartUnixTimeUs= */ C.TIME_UNSET);
    }

    private RangedUri parseRangedUrl(String urlText, String rangeText) {
        long rangeStart = 0;
        long rangeLength = C.LENGTH_UNSET;
        if (rangeText != null) {
            String[] rangeTextArray = rangeText.split("-");
            rangeStart = Long.parseLong(rangeTextArray[0]);
            if (rangeTextArray.length == 2) {
                rangeLength = Long.parseLong(rangeTextArray[1]) - rangeStart + 1;
            }
        }

        return new RangedUri(urlText, rangeStart, rangeLength);
    }

    private List<SegmentTimelineElement> parseSegmentTimeline(MediaFormat format) {
        List<SegmentTimelineElement> timeline = new ArrayList<>();

        if (format.getGlobalSegmentList() == null) {
            return timeline;
        }

        // From writeGlobalSegmentList
        long elapsedTime = 0;

        // SegmentURL tag
        for (String segment : format.getGlobalSegmentList()) {
            long duration = Helpers.parseLong(segment, C.TIME_UNSET);
            int count = 1;
            for (int i = 0; i < count; i++) {
                timeline.add(new SegmentTimelineElement(elapsedTime, duration));
                elapsedTime += duration;
            }
        }

        return timeline;
    }

    private List<SegmentTimelineElement> parseSegmentTimeline(long elapsedTime, long duration, int segmentCount) {
        List<SegmentTimelineElement> timeline = new ArrayList<>();

        // From writeLiveMediaSegmentList
        int count = 1 + segmentCount;
        for (int i = 0; i < count; i++) {
            timeline.add(new SegmentTimelineElement(elapsedTime, duration));
            elapsedTime += duration;
        }

        return timeline;
    }

    private List<RangedUri> parseSegmentUrl(MediaFormat format) {
        List<RangedUri> segments = new ArrayList<>();

        if (format.getSegmentUrlList() == null) {
            return segments;
        }

        // SegmentURL tag
        for (String url : format.getSegmentUrlList()) {
            segments.add(parseRangedUrl(url, null));
        }

        return segments;
    }

    private SingleSegmentBase parseSegmentBase(MediaFormat format) {
        long timescale = 1000;
        long presentationTimeOffset = 0;

        long indexStart = 0;
        long indexLength = 0;
        String indexRangeText = format.getIndex();
        if (indexRangeText != null) {
            String[] indexRange = indexRangeText.split("-");
            indexStart = Long.parseLong(indexRange[0]);
            indexLength = Long.parseLong(indexRange[1]) - indexStart + 1;
        }

        RangedUri initialization = parseRangedUrl(format.getSourceUrl(), format.getInit());


        return new SingleSegmentBase(initialization, timescale, presentationTimeOffset, indexStart,
                indexLength);
    }

    private RepresentationInfo parseRepresentation(MediaFormat mediaFormat) {
        int roleFlags = C.ROLE_FLAG_MAIN;
        int selectionFlags = C.SELECTION_FLAG_DEFAULT;
        String id = mediaFormat.getITag();
        int bandwidth = Helpers.parseInt(mediaFormat.getBitrate(), Format.NO_VALUE);
        String mimeType = MediaFormatUtils.extractMimeType(mediaFormat);
        String codecs = MediaFormatUtils.extractCodecs(mediaFormat);
        int width = mediaFormat.getWidth();
        int height = mediaFormat.getHeight();
        float frameRate = Helpers.parseFloat(mediaFormat.getFps(), Format.NO_VALUE);
        int audioChannels = Format.NO_VALUE;
        int audioSamplingRate = Helpers.parseInt(ITagUtils.getAudioRateByTag(mediaFormat.getITag()), Format.NO_VALUE);
        String language = mediaFormat.getLanguage();
        String baseUrl = mediaFormat.getUrl();
        String label = mediaFormat.getQualityLabel();
        boolean isDrc = mediaFormat.isDrc();
        long lastModified = Helpers.parseLong(mediaFormat.getLmt());
        Format format =
                buildFormat(
                        id,
                        label,
                        mimeType,
                        width,
                        height,
                        frameRate,
                        audioChannels,
                        audioSamplingRate,
                        bandwidth,
                        language,
                        roleFlags,
                        selectionFlags,
                        codecs,
                        isDrc,
                        lastModified);

        SegmentBase segmentBase = null;

        if (MediaFormatUtils.isLiveMedia(mediaFormat)) {
            segmentBase = parseSegmentTemplate(mediaFormat);
        } else if (mediaFormat.getSegmentUrlList() != null) {
            segmentBase = parseSegmentList(mediaFormat);
        } else if (mediaFormat.getIndex() != null &&
                !mediaFormat.getIndex().equals(NULL_INDEX_RANGE)) { // json mediaFormat fix: index is null
            segmentBase = parseSegmentBase(mediaFormat);
        }

        segmentBase = segmentBase != null ? segmentBase : new SingleSegmentBase();

        return new RepresentationInfo(format, baseUrl, segmentBase, Representation.REVISION_ID_DEFAULT);
    }

    private RepresentationInfo parseRepresentation(MediaSubtitle sub) {
        int roleFlags = C.ROLE_FLAG_SUBTITLE;
        int selectionFlags = 0;
        //String id = String.valueOf(mId++);
        // keep mId incrementing, but use vssId as id to make it unique and more meaningful
        mId++;
        int bandwidth = 268;
        String mimeType = sub.getMimeType();
        String codecs = sub.getCodecs();
        int width = Format.NO_VALUE;
        int height = Format.NO_VALUE;
        float frameRate = Format.NO_VALUE;
        int audioChannels = Format.NO_VALUE;
        int audioSamplingRate = Format.NO_VALUE;
        String language = sub.getName() == null ? sub.getLanguageCode() : sub.getName();
        String baseUrl = sub.getBaseUrl();
        String label = null;
        boolean isDrc = false;
        long lastModified = Format.NO_VALUE;
        Format format =
                buildFormat(
                        //id,
                        // use vssId as id to make it unique
                        sub.getVssId(),
                        label,
                        mimeType,
                        width,
                        height,
                        frameRate,
                        audioChannels,
                        audioSamplingRate,
                        bandwidth,
                        language,
                        roleFlags,
                        selectionFlags,
                        codecs,
                        isDrc,
                        lastModified);

        SegmentBase segmentBase = new SingleSegmentBase();

        return new RepresentationInfo(format, baseUrl, segmentBase, Representation.REVISION_ID_DEFAULT);
    }

    protected Representation buildRepresentation(
            RepresentationInfo representationInfo,
            String label) {
        Format format = representationInfo.format;
        if (label != null) {
            format = format.buildUpon().setLabel(label).build();
        }
        List<BaseUrl> baseUrls = new ArrayList<>();
        baseUrls.add(new BaseUrl(representationInfo.baseUrl));
        return Representation.newInstance(
                representationInfo.revisionId,
                format,
                baseUrls,
                representationInfo.segmentBase);
    }

    protected Format buildFormat(
            String id,
            String label,
            String containerMimeType,
            int width,
            int height,
            float frameRate,
            int audioChannels,
            int audioSamplingRate,
            int bitrate,
            String language,
            @C.RoleFlags int roleFlags,
            @C.SelectionFlags int selectionFlags,
            String codecs,
            boolean isDrc,
            long lastModified) {
        String sampleMimeType = getSampleMimeType(containerMimeType, codecs);
        Format.Builder builder = new Format.Builder()
                .setId(id)
                .setLabel(label)
                .setContainerMimeType(containerMimeType)
                .setSampleMimeType(sampleMimeType)
                .setCodecs(codecs)
                .setAverageBitrate(bitrate)
                .setPeakBitrate(bitrate)
                .setSelectionFlags(selectionFlags)
                .setRoleFlags(roleFlags);
        if (sampleMimeType != null && MimeTypes.isVideo(sampleMimeType)) {
            builder.setWidth(width).setHeight(height).setFrameRate(frameRate);
        } else if (sampleMimeType != null && MimeTypes.isAudio(sampleMimeType)) {
            builder.setChannelCount(audioChannels).setSampleRate(audioSamplingRate).setLanguage(language);
        } else {
            builder.setLanguage(language);
        }
        if (isDrc || lastModified > 0) {
            builder.setMetadata(new Metadata(new SabrFormatMetadata(null, null, isDrc, lastModified)));
        }
        return builder.build();
    }

    /**
     * Derives a sample mimeType from a container mimeType and codecs attribute.
     *
     * @param containerMimeType The mimeType of the container.
     * @param codecs The codecs attribute.
     * @return The derived sample mimeType, or null if it could not be derived.
     */
    private static String getSampleMimeType(String containerMimeType, String codecs) {
        if (MimeTypes.isAudio(containerMimeType)) {
            return MimeTypes.getAudioMediaMimeType(codecs);
        } else if (MimeTypes.isVideo(containerMimeType)) {
            return MimeTypes.getVideoMediaMimeType(codecs);
        } else if (mimeTypeIsRawText(containerMimeType)) {
            return containerMimeType;
        } else if (MimeTypes.APPLICATION_MP4.equals(containerMimeType)) {
            if (codecs != null) {
                if (codecs.startsWith("stpp")) {
                    return MimeTypes.APPLICATION_TTML;
                } else if (codecs.startsWith("wvtt")) {
                    return MimeTypes.APPLICATION_MP4VTT;
                }
            }
        } else if (MimeTypes.APPLICATION_RAWCC.equals(containerMimeType)) {
            if (codecs != null) {
                if (codecs.contains("cea708")) {
                    return MimeTypes.APPLICATION_CEA708;
                } else if (codecs.contains("eia608") || codecs.contains("cea608")) {
                    return MimeTypes.APPLICATION_CEA608;
                }
            }
            return null;
        }
        return null;
    }

    /**
     * Returns whether a mimeType is a text sample mimeType.
     *
     * @param mimeType The mimeType.
     * @return Whether the mimeType is a text sample mimeType.
     */
    private static boolean mimeTypeIsRawText(String mimeType) {
        return MimeTypes.isText(mimeType)
                || MimeTypes.APPLICATION_TTML.equals(mimeType)
                || MimeTypes.APPLICATION_MP4VTT.equals(mimeType)
                || MimeTypes.APPLICATION_CEA708.equals(mimeType)
                || MimeTypes.APPLICATION_CEA608.equals(mimeType);
    }

    private void append(List<MediaSubtitle> subs) {
        mSubs.addAll(subs);
    }

    private void append(MediaSubtitle sub) {
        mSubs.add(sub);
    }

    private void append(MediaFormat mediaItem) {
        if (!MediaFormatUtils.checkMediaUrl(mediaItem)) {
            Log.e(TAG, "Media item doesn't contain required url field!");
            return;
        }

        // NOTE: FORMAT_STREAM_TYPE_OTF not supported
        if (!MediaFormatUtils.isDash(mediaItem)) {
            return;
        }

        //fixOTF(mediaItem);

        Set<MediaFormat> placeholder = null;
        String mimeType = MediaFormatUtils.extractMimeType(mediaItem);
        if (mimeType != null) {
            switch (mimeType) {
                case MediaFormatUtils.MIME_MP4_VIDEO:
                    placeholder = mMP4Videos;
                    break;
                case MediaFormatUtils.MIME_WEBM_VIDEO:
                    placeholder = mWEBMVideos;
                    break;
                case MediaFormatUtils.MIME_MP4_AUDIO:
                    placeholder = getMP4Audios(mediaItem.getLanguage());
                    break;
                case MediaFormatUtils.MIME_WEBM_AUDIO:
                    placeholder = getWEBMAudios(mediaItem.getLanguage());
                    break;
            }
        }

        if (placeholder != null) {
            placeholder.add(mediaItem); // NOTE: reverse order
        }
    }

    private Set<MediaFormat> getMP4Audios(String language) {
        return getFormats(mMP4Audios, language);
    }

    private Set<MediaFormat> getWEBMAudios(String language) {
        return getFormats(mWEBMAudios, language);
    }

    private static Set<MediaFormat> getFormats(Map<String, Set<MediaFormat>> formatMap, String language) {
        if (language == null) {
            language = "default";
        }

        Set<MediaFormat> mediaFormats = formatMap.get(language);

        if (mediaFormats == null) {
            mediaFormats = new TreeSet<>(new MediaFormatComparator());
            formatMap.put(language, mediaFormats);
        }

        return mediaFormats;
    }

    protected int getContentType(Format format) {
        String sampleMimeType = format.sampleMimeType;
        if (TextUtils.isEmpty(sampleMimeType)) {
            return C.TRACK_TYPE_UNKNOWN;
        } else if (MimeTypes.isVideo(sampleMimeType)) {
            return C.TRACK_TYPE_VIDEO;
        } else if (MimeTypes.isAudio(sampleMimeType)) {
            return C.TRACK_TYPE_AUDIO;
        } else if (mimeTypeIsRawText(sampleMimeType)) {
            return C.TRACK_TYPE_TEXT;
        }
        return C.TRACK_TYPE_UNKNOWN;
    }

    /** A parsed Representation element. */
    protected static final class RepresentationInfo {

        public final Format format;
        public final String baseUrl;
        public final SegmentBase segmentBase;
        public final long revisionId;

        public RepresentationInfo(Format format, String baseUrl, SegmentBase segmentBase, long revisionId) {
            this.format = format;
            this.baseUrl = baseUrl;
            this.segmentBase = segmentBase;
            this.revisionId = revisionId;
        }

    }
}
