package com.liskovsoft.media3.sabr.parser.adapter;

import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.mkv.MatroskaExtractor;
import androidx.media3.extractor.text.SubtitleParser;
import com.liskovsoft.media3.sabr.parser.SabrStream;
import com.liskovsoft.media3.sabr.parser.exceptions.SabrReloadPlayerError;
import com.liskovsoft.sharedutils.mylogger.Log;

import java.io.IOException;

public class SabrMatroskaAdapter extends SabrExtractorAdapter {
    private static final String TAG = SabrMatroskaAdapter.class.getSimpleName();
    public static final int FLAG_DISABLE_SEEK_FOR_CUES = MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES;

    public SabrMatroskaAdapter(int flags, SabrStream sabrStream) {
        super(new MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED, flags), sabrStream);
    }

    @Override
    public int read(ExtractorInput input, PositionHolder seekPosition) throws IOException {
        int result = RESULT_END_OF_INPUT;

        try {
            result = readDelegate(input, seekPosition);
        } catch (SabrReloadPlayerError e) {
            // Upstream (486c493b): a control signal from the server, not a broken read: let the player handle it
            Log.e(TAG, "Propagating SABR control signal: %s", e.getMessage());
            throw e;
        } catch (Exception e) {
            Log.e(TAG, "User doing seek? %s: %s", e.getClass().getSimpleName(), e.getMessage());
            e.printStackTrace();
        } finally {
            if (result != RESULT_CONTINUE) {
                Log.e(TAG, "MatroskaAdapter: disposing, result=%s", result);
                extractorInput.dispose();
            }
        }

        return result;
    }
}
