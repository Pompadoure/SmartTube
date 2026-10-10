package com.liskovsoft.smartyoutubetv2.common.exoplayer.errors;

import androidx.media3.common.C;
import androidx.media3.common.ParserException;
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import androidx.media3.exoplayer.upstream.Loader.UnexpectedLoaderException;

import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * JoTube M3: Media3 port of the 2.10 policy. Track exclusion ("blacklisting" in 2.10) only on HTTP 404/410 like
 * before (Media3's default would also exclude on 403/416/500/503), retry delay unchanged.
 */
public class DashDefaultLoadErrorHandlingPolicy extends DefaultLoadErrorHandlingPolicy {
    @Override
    protected boolean isEligibleForFallback(IOException exception) {
        if (exception instanceof InvalidResponseCodeException) {
            int responseCode = ((InvalidResponseCodeException) exception).responseCode;
            return responseCode == 404 // HTTP 404 Not Found.
                    || responseCode == 410; // HTTP 410 Gone.
        }
        return false;
    }

    @Override
    public long getRetryDelayMsFor(LoadErrorInfo loadErrorInfo) {
        IOException exception = loadErrorInfo.exception;
        return exception instanceof ParserException
                || exception instanceof FileNotFoundException
                || exception instanceof UnexpectedLoaderException
                ? C.TIME_UNSET
                : Math.min((loadErrorInfo.errorCount - 1) * 1000, 5000);
    }
}
