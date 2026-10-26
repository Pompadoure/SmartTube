package com.liskovsoft.media3.sabr.parser.parts;

import com.liskovsoft.media3.sabr.parser.models.FormatSelector;
import com.liskovsoft.media3.sabr.protos.misc.FormatId;

public class FormatInitializedSabrPart implements SabrPart {
    public final FormatId formatId;
    public final FormatSelector formatSelector;
    public final long endTimeMs;

    public FormatInitializedSabrPart(FormatId formatId, FormatSelector formatSelector, long endTimeMs) {
        this.formatId = formatId;
        this.formatSelector = formatSelector;
        this.endTimeMs = endTimeMs;
    }
}
