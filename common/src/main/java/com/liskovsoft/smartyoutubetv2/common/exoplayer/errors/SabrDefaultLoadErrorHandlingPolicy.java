package com.liskovsoft.smartyoutubetv2.common.exoplayer.errors;

import androidx.media3.common.C;
import com.liskovsoft.media3.sabr.parser.exceptions.SabrReloadPlayerError;
import com.liskovsoft.sharedutils.helpers.Helpers;

public class SabrDefaultLoadErrorHandlingPolicy extends DashDefaultLoadErrorHandlingPolicy {
    @Override
    public long getRetryDelayMsFor(LoadErrorInfo loadErrorInfo) {
        if (Helpers.contains(loadErrorInfo.exception.getMessage(), "Wait 5 sec")) {
            return 5_000;
        }

        // Upstream (17b09643/486c493b): the server asked for a fresh player response. Retrying the same request is
        // pointless. Fatal, so ErrorFixerController reloads the video with the token. (Media3 already treats the
        // UnexpectedLoaderException wrapper as fatal; explicit here so it never depends on that.)
        if (loadErrorInfo.exception.getCause() instanceof SabrReloadPlayerError) {
            return C.TIME_UNSET;
        }

        return super.getRetryDelayMsFor(loadErrorInfo);
    }
}
