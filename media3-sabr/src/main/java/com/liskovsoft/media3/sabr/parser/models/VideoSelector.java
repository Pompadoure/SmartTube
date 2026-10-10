package com.liskovsoft.media3.sabr.parser.models;

import androidx.media3.common.Format;
import com.liskovsoft.media3.sabr.protos.misc.FormatId;

public class VideoSelector extends FormatSelector {
    public VideoSelector(String displayName, boolean discardMedia) {
        super(displayName, discardMedia);
    }

    public VideoSelector(String displayName, boolean discardMedia, FormatId... formatIds) {
        super(displayName, discardMedia, formatIds);
    }

    public VideoSelector(String displayName, boolean discardMedia, Format... selectedFormats) {
        super(displayName, discardMedia, selectedFormats);
    }

    @Override
    public String getMimePrefix() {
        return "video";
    }
}
