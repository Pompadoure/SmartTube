package com.liskovsoft.media3.sabr.parser.adapter;

import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.PositionHolder;
import com.liskovsoft.media3.sabr.parser.SabrStream;
import com.liskovsoft.media3.sabr.parser.misc.SabrExtractorInput;

import java.io.IOException;

/**
 * JoTube M3: wraps a Media3 container extractor and feeds it only the media bytes of the SABR (UMP) response.
 * The 2.10 fork subclassed the extractors and overrode {@code read}; Media3's {@code read} methods are final, so this
 * delegates instead (same behaviour).
 */
public abstract class SabrExtractorAdapter implements Extractor {
    protected final Extractor delegate;
    protected final SabrExtractorInput extractorInput;

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
        delegate.init(output);
    }

    @Override
    public void seek(long position, long timeUs) {
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
}
