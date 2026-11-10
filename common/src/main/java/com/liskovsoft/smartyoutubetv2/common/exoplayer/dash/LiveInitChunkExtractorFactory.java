package com.liskovsoft.smartyoutubetv2.common.exoplayer.dash;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.source.chunk.BundledChunkExtractor;
import androidx.media3.exoplayer.source.chunk.ChunkExtractor;
import androidx.media3.extractor.ChunkIndex;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.text.SubtitleParser;

import java.io.IOException;
import java.util.List;

/**
 * JoTube: live streams. The "initialization segment" of a YouTube live format is the stream URL without a segment
 * number, and YouTube answers that with the newest whole segment. Media3 hands its sample queues to the initialization
 * load (ExoPlayer 2 dropped those samples), so every live start first played ~5 s from the live edge and then jumped
 * back to the real start position (device log 2026-11-10 22:09: audio sink "timestamp discontinuity", content time
 * 19984 ms backwards, the picture racing to catch up, then a player error and an engine restart).<br/>
 * Here the first load of a representation (the one that finds the sample formats) is read without track outputs:
 * only the formats are taken from it. Same fix as BUG-1 for SABR (DefaultSabrChunkSource.InitOnlyChunkExtractor).
 */
@UnstableApi
public final class LiveInitChunkExtractorFactory implements ChunkExtractor.Factory {
    private final ChunkExtractor.Factory mDelegate = new BundledChunkExtractor.Factory();

    @Override
    public ChunkExtractor.Factory setSubtitleParserFactory(SubtitleParser.Factory subtitleParserFactory) {
        mDelegate.setSubtitleParserFactory(subtitleParserFactory);
        return this;
    }

    @Override
    public Format getOutputTextFormat(Format sourceFormat) {
        return mDelegate.getOutputTextFormat(sourceFormat);
    }

    @Nullable
    @Override
    public ChunkExtractor createProgressiveMediaExtractor(@C.TrackType int primaryTrackType, Format representationFormat,
            boolean enableEventMessageTrack, List<Format> closedCaptionFormats, @Nullable TrackOutput playerEmsgTrackOutput,
            PlayerId playerId) {
        ChunkExtractor extractor = mDelegate.createProgressiveMediaExtractor(primaryTrackType, representationFormat,
                enableEventMessageTrack, closedCaptionFormats, playerEmsgTrackOutput, playerId);
        return extractor != null ? new InitOnlyFirst(extractor) : null;
    }

    private static final class InitOnlyFirst implements ChunkExtractor {
        private final ChunkExtractor mDelegate;

        InitOnlyFirst(ChunkExtractor delegate) {
            mDelegate = delegate;
        }

        @Override
        public void init(@Nullable TrackOutputProvider trackOutputProvider, long startTimeUs, long endTimeUs) {
            // no formats yet: this is the initialization load, its media is not part of the playback
            boolean initLoad = mDelegate.getSampleFormats() == null;
            mDelegate.init(initLoad ? null : trackOutputProvider, startTimeUs, endTimeUs);
        }

        @Nullable
        @Override
        public ChunkIndex getChunkIndex() {
            return mDelegate.getChunkIndex();
        }

        @Nullable
        @Override
        public Format[] getSampleFormats() {
            return mDelegate.getSampleFormats();
        }

        @Override
        public boolean read(ExtractorInput input) throws IOException {
            return mDelegate.read(input);
        }

        @Override
        public void release() {
            mDelegate.release();
        }
    }
}
