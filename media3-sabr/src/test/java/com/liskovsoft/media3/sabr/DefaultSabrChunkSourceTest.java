package com.liskovsoft.media3.sabr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.exoplayer.LoadingInfo;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;
import androidx.media3.exoplayer.source.LoadEventInfo;
import androidx.media3.exoplayer.source.MediaLoadData;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;
import androidx.media3.exoplayer.source.SampleQueue;
import androidx.media3.common.TrackGroup;
import androidx.media3.exoplayer.source.chunk.BaseMediaChunkOutput;
import androidx.media3.exoplayer.source.chunk.ChunkHolder;
import androidx.media3.exoplayer.source.chunk.ContainerMediaChunk;
import androidx.media3.exoplayer.source.chunk.InitializationChunk;
import androidx.media3.exoplayer.source.chunk.MediaChunk;
import com.liskovsoft.media3.sabr.manifest.AdaptationSet;
import com.liskovsoft.media3.sabr.manifest.Period;
import com.liskovsoft.media3.sabr.manifest.RangedUri;
import com.liskovsoft.media3.sabr.manifest.Representation;
import com.liskovsoft.media3.sabr.manifest.SabrManifest;
import com.liskovsoft.media3.sabr.manifest.SegmentBase.SingleSegmentBase;
import com.liskovsoft.media3.sabr.protos.videostreaming.StreamerContext.ClientInfo;
import androidx.media3.exoplayer.trackselection.FixedTrackSelection;
import androidx.media3.datasource.ByteArrayDataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.exoplayer.upstream.DefaultAllocator;
import androidx.media3.exoplayer.upstream.LoaderErrorThrower;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Util;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 28)
public class DefaultSabrChunkSourceTest {
    // JoTube M3: ported from the 2.10.6 fork. Text tracks are now one ContainerMediaChunk parsed into
    // application/x-media3-cues during extraction (was a raw ContainerMediaChunk).
    private static final String CAPTION_URL =
            "https://www.youtube.com/api/timedtext?v=test&lang=en&fmt=vtt";
    private static final String SABR_URL =
            "https://rr1---sn-primary.googlevideo.com/videoplayback?"
                    + "mn=sn-primary%2Csn-secondary";
    private static final long DURATION_MS = 60_000;
    private static final byte[] CAPTIONS =
            Util.getUtf8Bytes("WEBVTT\n\n00:00:01.000 --> 00:00:03.000\nTest captions\n");

    @Test
    public void webvttUsesCaptionGetInsteadOfSabrContainer() {
        assertRawTextRequest(MimeTypes.TEXT_VTT);
    }

    @Test
    public void ttmlUsesCaptionGetInsteadOfSabrContainer() {
        assertRawTextRequest(MimeTypes.APPLICATION_TTML);
    }

    @Test
    public void textWithoutContainerMimeTypeUsesCaptionGet() {
        assertRawTextRequest(null);
    }

    @Test
    public void enablingSubtitlesMidPlaybackKeepsOriginalCueTimestamps() {
        SabrManifest manifest = createManifest(createTextFormat(MimeTypes.TEXT_VTT), DURATION_MS);
        DefaultSabrChunkSource source = createSource(manifest);
        ChunkHolder out = new ChunkHolder();

        source.getNextChunk(loadingInfo(30_000_000), 30_000_000, Collections.emptyList(), out);

        assertTrue(out.chunk instanceof ContainerMediaChunk);
        assertEquals(0, out.chunk.startTimeUs);
        assertEquals(DURATION_MS * 1_000, out.chunk.endTimeUs);
    }

    @Test
    public void loadedCaptionFileEndsStreamEvenWhenDurationIsUnknown() {
        SabrManifest manifest = createManifest(createTextFormat(MimeTypes.TEXT_VTT), C.TIME_UNSET);
        DefaultSabrChunkSource source = createSource(manifest);
        ChunkHolder out = new ChunkHolder();
        source.getNextChunk(loadingInfo(0), 0, Collections.emptyList(), out);
        MediaChunk captionChunk = (MediaChunk) out.chunk;
        out.clear();

        source.getNextChunk(loadingInfo(0), C.TIME_UNSET, Collections.singletonList(captionChunk), out);

        assertTrue(out.endOfStream);
        assertNull(out.chunk);
    }

    @Test
    public void seekingWithEmptyQueueReloadsTheWholeCaptionFile() {
        SabrManifest manifest = createManifest(createTextFormat(MimeTypes.TEXT_VTT), DURATION_MS);
        DefaultSabrChunkSource source = createSource(manifest);
        ChunkHolder out = new ChunkHolder();
        source.getNextChunk(loadingInfo(30_000_000), 30_000_000, Collections.emptyList(), out);
        out.clear();

        source.getNextChunk(loadingInfo(5_000_000), 5_000_000, Collections.emptyList(), out);

        assertTrue(out.chunk instanceof ContainerMediaChunk);
        assertEquals(CAPTION_URL, out.chunk.dataSpec.uri.toString());
        assertEquals(0, out.chunk.startTimeUs);
        assertFalse(out.endOfStream);
    }

    @Test
    public void loadsCaptionBytesAsOneTextSample() throws Exception {
        Format format = createTextFormat(MimeTypes.TEXT_VTT);
        DefaultSabrChunkSource source = createSource(createManifest(format, DURATION_MS));
        ChunkHolder out = new ChunkHolder();
        source.getNextChunk(loadingInfo(0), 0, Collections.emptyList(), out);
        assertTrue(out.chunk instanceof ContainerMediaChunk);
        ContainerMediaChunk chunk = (ContainerMediaChunk) out.chunk;
        SampleQueue sampleQueue = SampleQueue.createWithoutDrm(new DefaultAllocator(true, 1024));
        chunk.init(new BaseMediaChunkOutput(
                new int[] {C.TRACK_TYPE_TEXT}, new SampleQueue[] {sampleQueue}));

        chunk.load();
        source.onChunkLoadCompleted(chunk);

        assertTrue(chunk.isLoadCompleted());
        // The WebVTT file is parsed during extraction: one cue sample in the media3 cues format.
        assertEquals(1, sampleQueue.getWriteIndex());
        assertEquals(MimeTypes.APPLICATION_MEDIA3_CUES, sampleQueue.getUpstreamFormat().sampleMimeType);
        assertEquals(1_000_000, sampleQueue.getLargestQueuedTimestampUs());
        out.clear();
        source.getNextChunk(loadingInfo(0), chunk.endTimeUs, Collections.singletonList(chunk), out);
        assertTrue(out.endOfStream);
        assertNull(out.chunk);
        sampleQueue.reset();
    }

    @Test
    public void captionRequestIncludesVisitorCookieWithoutSabrHeaders() {
        SabrManifest manifest = createManifest(
                DURATION_MS, "VISITOR_INFO1_LIVE=test",
                createAdaptationSet(createTextFormat(MimeTypes.TEXT_VTT), C.TRACK_TYPE_TEXT));
        ChunkHolder out = new ChunkHolder();

        createSource(manifest).getNextChunk(loadingInfo(0), 0, Collections.emptyList(), out);

        assertEquals(Collections.singletonMap("Cookie", "VISITOR_INFO1_LIVE=test"),
                out.chunk.dataSpec.httpRequestHeaders);
        assertEquals(CAPTION_URL, out.chunk.dataSpec.uri.toString());
    }

    @Test
    public void enablingCaptionsDoesNotAdvertiseThemAsSabrFormats() {
        Format video = createVideoFormat();
        SabrManifest manifest = createManifest(
                DURATION_MS, null,
                createAdaptationSet(video, C.TRACK_TYPE_VIDEO),
                createAdaptationSet(createTextFormat(MimeTypes.TEXT_VTT), C.TRACK_TYPE_TEXT));
        DefaultSabrChunkSource videoSource = createSource(manifest, 0);
        createSource(manifest, 1);
        ChunkHolder out = new ChunkHolder();

        videoSource.getNextChunk(loadingInfo(0), 0, Collections.emptyList(), out);

        assertTrue(out.chunk instanceof ContainerMediaChunk);
        assertEquals(0, manifest.createVideoPlaybackAbrRequest(C.TRACK_TYPE_VIDEO, true)
                .getPreferredSubtitleFormatIdsCount());
        assertEquals(1, manifest.createVideoPlaybackAbrRequest(C.TRACK_TYPE_VIDEO, true)
                .getPreferredVideoFormatIdsCount());
    }

    @Test
    public void captionNetworkErrorsDoNotSwitchSabrCdn() {
        SabrManifest manifest = spy(createManifest(createTextFormat(MimeTypes.TEXT_VTT), DURATION_MS));
        DefaultSabrChunkSource source = createSource(manifest);
        ChunkHolder out = new ChunkHolder();
        source.getNextChunk(loadingInfo(0), 0, Collections.emptyList(), out);

        IOException error = new IOException("caption connection failed");
        LoadErrorHandlingPolicy.LoadErrorInfo errorInfo = new LoadErrorHandlingPolicy.LoadErrorInfo(
                new LoadEventInfo(0, out.chunk.dataSpec, 0),
                new MediaLoadData(C.DATA_TYPE_MEDIA),
                error,
                /* errorCount= */ 1);
        boolean handled = source.onChunkLoadError(
                out.chunk, true, errorInfo, new DefaultLoadErrorHandlingPolicy());

        assertFalse(handled);
        verify(manifest, never()).maybeUseNextCdn(anyString());
    }

    @Test
    public void videoStillUsesSabrContainerPost() {
        assertSabrRequest(createVideoFormat(), C.TRACK_TYPE_VIDEO);
    }

    @Test
    public void audioStillUsesSabrContainerPost() {
        Format audio = new Format.Builder()
                .setId("140").setContainerMimeType(MimeTypes.AUDIO_MP4)
                .setSampleMimeType(MimeTypes.AUDIO_AAC).setAverageBitrate(128_000)
                .setChannelCount(2).setSampleRate(44_100).setLanguage("en").build();
        assertSabrRequest(audio, C.TRACK_TYPE_AUDIO);
    }

    @Test
    public void startFromTheBeginningLoadsInitAndFirstMediaAsOneMediaChunk() {
        SabrManifest manifest = createManifestWithInitRange();
        ChunkHolder out = new ChunkHolder();

        createSource(manifest).getNextChunk(loadingInfo(0), 0, Collections.emptyList(), out);

        // JoTube: the init response is loaded as the first media chunk (samples kept, sample format at once)
        assertTrue(out.chunk instanceof ContainerMediaChunk);
        assertEquals(DataSpec.HTTP_METHOD_POST, out.chunk.dataSpec.httpMethod);
        assertEquals(SABR_URL + "&rn=0", out.chunk.dataSpec.uri.toString());
        assertTrue(out.chunk.dataSpec.httpBody.length > 0);
        assertEquals(0, out.chunk.startTimeUs);
    }

    @Test
    public void startInTheMiddleLoadsInitAndTheSegmentAtThePositionAsOneChunk() {
        SabrManifest manifest = createManifestWithInitRange();
        ChunkHolder out = new ChunkHolder();

        createSource(manifest).getNextChunk(loadingInfo(30_000_000), 30_000_000, Collections.emptyList(), out);

        assertTrue(out.chunk instanceof ContainerMediaChunk);
        assertEquals(DataSpec.HTTP_METHOD_POST, out.chunk.dataSpec.httpMethod);
        assertEquals(SABR_URL + "&rn=0", out.chunk.dataSpec.uri.toString());
    }

    @Test
    public void startChunkThatDidNotYieldASampleFormatIsFollowedByTheInitializationChunk() {
        SabrManifest manifest = createManifestWithInitRange();
        DefaultSabrChunkSource source = createSource(manifest);
        ChunkHolder out = new ChunkHolder();
        source.getNextChunk(loadingInfo(0), 0, Collections.emptyList(), out);
        MediaChunk startChunk = (MediaChunk) out.chunk;
        out.clear();

        // Nothing was parsed from the start chunk (no sample format): recover with the old init request
        source.getNextChunk(loadingInfo(0), startChunk.endTimeUs, Collections.singletonList(startChunk), out);

        assertTrue(out.chunk instanceof InitializationChunk);
        assertFalse(out.endOfStream);
        assertEquals(SABR_URL + "&rn=1", out.chunk.dataSpec.uri.toString());
    }

    private static SabrManifest createManifestWithInitRange() {
        Representation representation = Representation.newInstance(
                createVideoFormat(), SABR_URL,
                new SingleSegmentBase(new RangedUri(null, 0, 100), 1, 0, 0, 0));
        return createManifest(
                DURATION_MS, null,
                new AdaptationSet(0, C.TRACK_TYPE_VIDEO, Collections.singletonList(representation)));
    }

    private static void assertSabrRequest(Format format, int trackType) {
        SabrManifest manifest = createManifest(
                DURATION_MS, "VISITOR_INFO1_LIVE=test", createAdaptationSet(format, trackType));
        ChunkHolder out = new ChunkHolder();

        createSource(manifest).getNextChunk(loadingInfo(0), 0, Collections.emptyList(), out);

        assertTrue(out.chunk instanceof ContainerMediaChunk);
        assertEquals(DataSpec.HTTP_METHOD_POST, out.chunk.dataSpec.httpMethod);
        assertEquals(SABR_URL + "&rn=0", out.chunk.dataSpec.uri.toString());
        assertEquals("application/x-protobuf", out.chunk.dataSpec.httpRequestHeaders.get("Content-Type"));
        assertFalse(out.chunk.dataSpec.httpRequestHeaders.containsKey("Cookie"));
        assertTrue(out.chunk.dataSpec.httpBody.length > 0);
    }

    private static void assertRawTextRequest(String containerMimeType) {
        SabrManifest manifest = createManifest(createTextFormat(containerMimeType), DURATION_MS);
        DefaultSabrChunkSource source = createSource(manifest);
        ChunkHolder out = new ChunkHolder();

        source.getNextChunk(loadingInfo(0), 0, Collections.emptyList(), out);

        assertTrue(out.chunk instanceof ContainerMediaChunk);
        assertEquals(CAPTION_URL, out.chunk.dataSpec.uri.toString());
        assertEquals(DataSpec.HTTP_METHOD_GET, out.chunk.dataSpec.httpMethod);
        assertNull(out.chunk.dataSpec.httpBody);
        assertTrue(out.chunk.dataSpec.httpRequestHeaders.isEmpty());
        assertEquals(-1, manifest.getSabrRequestNumber());
    }

    private static LoadingInfo loadingInfo(long playbackPositionUs) {
        return new LoadingInfo.Builder().setPlaybackPositionUs(playbackPositionUs).build();
    }

    private static Format createTextFormat(String containerMimeType) {
        return new Format.Builder()
                .setId("en").setContainerMimeType(containerMimeType)
                .setSampleMimeType(containerMimeType == null ? MimeTypes.TEXT_VTT : containerMimeType)
                .setRoleFlags(C.ROLE_FLAG_SUBTITLE).setLanguage("en").build();
    }

    private static SabrManifest createManifest(Format format, long durationMs) {
        return createManifest(durationMs, null, createAdaptationSet(format, C.TRACK_TYPE_TEXT));
    }

    private static Format createVideoFormat() {
        return new Format.Builder()
                .setId("137").setContainerMimeType(MimeTypes.VIDEO_MP4)
                .setSampleMimeType(MimeTypes.VIDEO_H264).setAverageBitrate(1_000_000)
                .setWidth(1920).setHeight(1080).setFrameRate(30).build();
    }

    private static AdaptationSet createAdaptationSet(Format format, int trackType) {
        Representation representation =
                Representation.newInstance(format,
                        trackType == C.TRACK_TYPE_TEXT ? CAPTION_URL : SABR_URL,
                        new SingleSegmentBase());
        return new AdaptationSet(trackType, trackType, Collections.singletonList(representation));
    }

    private static SabrManifest createManifest(
            long durationMs, String visitorCookie, AdaptationSet... adaptationSets) {
        Period period = new Period("test", 0, Arrays.asList(adaptationSets));
        return new SabrManifest(
                C.TIME_UNSET, durationMs, 1_500, false,
                C.TIME_UNSET, C.TIME_UNSET, C.TIME_UNSET, C.TIME_UNSET,
                Collections.singletonList(period), SABR_URL, "", null, "test",
                ClientInfo.getDefaultInstance(), visitorCookie);
    }

    private static DefaultSabrChunkSource createSource(SabrManifest manifest) {
        return createSource(manifest, 0);
    }

    private static DefaultSabrChunkSource createSource(SabrManifest manifest, int adaptationSetIndex) {
        AdaptationSet adaptationSet = manifest.getPeriod(0).adaptationSets.get(adaptationSetIndex);
        Format format = adaptationSet.representations.get(0).format;
        return new DefaultSabrChunkSource(
                new LoaderErrorThrower.Placeholder(), manifest, 0, new int[] {adaptationSetIndex},
                new FixedTrackSelection(new TrackGroup(format), 0), adaptationSet.type,
                new ByteArrayDataSource(CAPTIONS), 0, 1, Collections.emptyList(),
                new DefaultSubtitleParserFactory());
    }
}
