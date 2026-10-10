package com.liskovsoft.smartyoutubetv2.common.exoplayer.versions.renderer;

import android.content.Context;
import android.os.Handler;
import androidx.annotation.Nullable;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer;
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import com.liskovsoft.sharedutils.helpers.Helpers;

public class DelayMediaCodecAudioRenderer extends MediaCodecAudioRenderer {
    private static final String TAG = DelayMediaCodecAudioRenderer.class.getSimpleName();
    private int mDelayUs;
    private boolean mIsAudioSyncFixEnabled;
    private boolean mIsAudioSyncFixChanged;

    public DelayMediaCodecAudioRenderer(Context context, MediaCodecAdapter.Factory codecAdapterFactory, MediaCodecSelector mediaCodecSelector,
                                        boolean enableDecoderFallback, @Nullable Handler eventHandler,
                                        @Nullable AudioRendererEventListener eventListener, AudioSink audioSink) {
        super(context, codecAdapterFactory, mediaCodecSelector, enableDecoderFallback, eventHandler, eventListener, audioSink);
    }

    @Override
    public long getPositionUs() {
        long positionUs = super.getPositionUs();

        applyAudioSyncFix();

        return positionUs + mDelayUs;
    }

    public void setAudioDelayMs(int delayMs) {
        mDelayUs = delayMs * 1_000;
    }

    public int getAudioDelayMs() {
        return mDelayUs / 1_000;
    }

    /**
     * Disable the use of AudioTrack.getTimestamp and force ExoPlayer to go through the legacy path of using
     * AudioTrack.getPlaybackHeadPosition instead, which might help if the first one drifts but the second one doesn't.<br/>
     * M3: was done in processOutputBuffer(). That hook has a long, version dependent signature, so the fix is applied
     * from getPositionUs() (called by the player all the time) instead. Same fields are patched via reflection.
     */
    private void applyAudioSyncFix() {
        if (!mIsAudioSyncFixEnabled || !mIsAudioSyncFixChanged) {
            return;
        }

        Object audioSink = Helpers.getField(this, "audioSink");
        if (audioSink != null) {
            Object audioTrackPositionTracker = Helpers.getField(audioSink, "audioTrackPositionTracker");
            if (audioTrackPositionTracker != null) {
                Object audioTimestampPoller = Helpers.getField(audioTrackPositionTracker, "audioTimestampPoller");
                if (audioTimestampPoller != null) {
                    Helpers.setField(audioTimestampPoller, "audioTimestamp", null);
                    Helpers.setField(audioTimestampPoller, "state", 3);
                    mIsAudioSyncFixChanged = false;
                }
            }
        }
    }

    public void enableAudioSyncFix(boolean enable) {
        if (mIsAudioSyncFixEnabled == enable) {
            return;
        }

        mIsAudioSyncFixEnabled = enable;
        mIsAudioSyncFixChanged = true;
    }

    public boolean isAudioSyncFixEnabled() {
        return mIsAudioSyncFixEnabled;
    }
}
