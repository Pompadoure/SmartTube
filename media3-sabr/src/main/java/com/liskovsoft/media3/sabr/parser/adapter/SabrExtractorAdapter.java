package com.liskovsoft.media3.sabr.parser.adapter;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.Log;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.ForwardingExtractorOutput;
import androidx.media3.extractor.ForwardingTrackOutput;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.TrackOutput;
import com.liskovsoft.media3.sabr.parser.SabrStream;
import com.liskovsoft.media3.sabr.parser.misc.SabrExtractorInput;

import java.io.IOException;

/**
 * JoTube M3: wraps a Media3 container extractor and feeds it only the media bytes of the SABR (UMP) response.
 * The 2.10 fork subclassed the extractors and overrode {@code read}; Media3's {@code read} methods are final, so this
 * delegates instead (same behaviour).
 */
public abstract class SabrExtractorAdapter implements Extractor {
    private static final String DIAG_TAG = "JoTubeSabr";

    protected final Extractor delegate;
    protected final SabrExtractorInput extractorInput;
    // BUG-1 diagnostics: sample times after the last seek
    private volatile int seekCount;

    protected SabrExtractorAdapter(Extractor delegate, SabrStream sabrStream) {
        this.delegate = delegate;
        this.extractorInput = new SabrExtractorInput(sabrStream);
    }

    @Override
    public boolean sniff(ExtractorInput input) throws IOException {
        return delegate.sniff(input);
    }

    @Override
    public void init(ExtractorOutput output) {
        delegate.init(new ForwardingExtractorOutput(output) {
            @Override
            public TrackOutput track(int id, int type) {
                return new SampleTimeLogger(super.track(id, type), type);
            }
        });
    }

    @Override
    public void seek(long position, long timeUs) {
        seekCount++;
        Log.e(DIAG_TAG, "extractor seek: " + getClass().getSimpleName() + "@" + System.identityHashCode(this)
                + " position=" + position + " timeUs=" + timeUs + " seekCount=" + seekCount);
        delegate.seek(position, timeUs);
    }

    @Override
    public void release() {
        delegate.release();
    }

    @Override
    public Extractor getUnderlyingImplementation() {
        return delegate;
    }

    protected int readDelegate(ExtractorInput input, PositionHolder seekPosition) throws IOException {
        extractorInput.init(input);
        return delegate.read(extractorInput, seekPosition);
    }

    /** BUG-1 diagnostics: logs the first samples after each seek and every backward timestamp jump. */
    private final class SampleTimeLogger extends ForwardingTrackOutput {
        private final int trackType;
        private long lastTimeUs = C.TIME_UNSET;
        private int seenSeekCount = -1;
        private int sinceSeek;

        SampleTimeLogger(TrackOutput output, int trackType) {
            super(output);
            this.trackType = trackType;
        }

        @Override
        public void sampleMetadata(long timeUs, int flags, int size, int offset,
                                   @Nullable CryptoData cryptoData) {
            if (seenSeekCount != seekCount) {
                seenSeekCount = seekCount;
                sinceSeek = 0;
            }
            boolean backward = lastTimeUs != C.TIME_UNSET && timeUs < lastTimeUs;
            if (sinceSeek < 3 || backward) {
                Log.e(DIAG_TAG, "sample: ext=" + System.identityHashCode(SabrExtractorAdapter.this)
                        + " trackType=" + trackType + " timeUs=" + timeUs + " prevUs=" + lastTimeUs
                        + (backward ? " BACKWARD" : "") + " sinceSeek=" + sinceSeek + " seekCount=" + seekCount);
            }
            sinceSeek++;
            lastTimeUs = timeUs;
            super.sampleMetadata(timeUs, flags, size, offset, cryptoData);
        }
    }
}
