package com.liskovsoft.smartyoutubetv2.common.exoplayer.versions.renderer;

import android.content.Context;
import android.os.Handler;
import androidx.annotation.Nullable;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer;
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;

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
     * M3: the 2.10 fix patched private fields (DefaultAudioSink.audioTrackPositionTracker -> audioTimestampPoller:
     * audioTimestamp = null, state = 3) via reflection. In Media3 1.11.1 DefaultAudioSink has no such field and
     * AudioTimestampPoller.audioTimestamp is a final wrapper that must not be null (NPE), so the fix is a no-op for now.
     * TODO stage 3: re-check whether the fix is still needed with Media3 and find a safe hook.
     */
    private void applyAudioSyncFix() {
        mIsAudioSyncFixChanged = false;
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
