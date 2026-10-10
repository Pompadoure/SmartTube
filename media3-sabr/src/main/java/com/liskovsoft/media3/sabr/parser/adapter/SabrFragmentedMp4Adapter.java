package com.liskovsoft.media3.sabr.parser.adapter;

import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.mp4.FragmentedMp4Extractor;
import androidx.media3.extractor.text.SubtitleParser;
import com.liskovsoft.media3.sabr.parser.SabrStream;
import com.liskovsoft.sharedutils.mylogger.Log;

import java.io.IOException;
import java.util.List;

public class SabrFragmentedMp4Adapter extends SabrExtractorAdapter {
    private static final String TAG = SabrFragmentedMp4Adapter.class.getSimpleName();
    public static final int FLAG_ENABLE_EMSG_TRACK = FragmentedMp4Extractor.FLAG_ENABLE_EMSG_TRACK;

    public SabrFragmentedMp4Adapter(
            int flags,
            List<Format> closedCaptionFormats,
            @Nullable TrackOutput additionalEmsgTrackOutput,
            SabrStream sabrStream) {
        // SABR media has no in-band subtitles: no subtitle parsing during extraction
        super(new FragmentedMp4Extractor(SubtitleParser.Factory.UNSUPPORTED, flags, /* timestampAdjuster= */ null,
                /* sideloadedTrack= */ null, closedCaptionFormats, additionalEmsgTrackOutput), sabrStream);
    }

    @Override
    public int read(ExtractorInput input, PositionHolder seekPosition) throws IOException {
        int result = RESULT_END_OF_INPUT;

        try {
            result = readDelegate(input, seekPosition);
        } finally {
            if (result != RESULT_CONTINUE) {
                Log.e(TAG, "Mp4Adapter: disposing, result=%s", result);
                extractorInput.dispose();
            }
        }

        return result;
    }
}
