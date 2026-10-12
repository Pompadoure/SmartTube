package com.liskovsoft.smartyoutubetv2.common.exoplayer.versions.selector;

import android.util.Pair;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection.Definition;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.track.MediaTrack;

public class RestoreTrackSelector extends DefaultTrackSelector {
    private static final String TAG = RestoreTrackSelector.class.getSimpleName();
    private static final int FORMAT_NOT_SUPPORTED = 19;
    private static final int FORMAT_FORCE_SUPPORT = 52;
    private TrackSelectorCallback mCallback;

    public interface TrackSelectorCallback {
        Pair<Definition, MediaTrack> onSelectVideoTrack(TrackGroupArray groups, Parameters params);
        Pair<Definition, MediaTrack> onSelectAudioTrack(TrackGroupArray groups, Parameters params);
        Pair<Definition, MediaTrack> onSelectSubtitleTrack(TrackGroupArray groups, Parameters params);
        void updateVideoTrackSelection(TrackGroupArray groups, Parameters params, Definition definition);
        void updateAudioTrackSelection(TrackGroupArray groups, Parameters params, Definition definition);
        void updateSubtitleTrackSelection(TrackGroupArray groups, Parameters params, Definition definition);
    }

    public RestoreTrackSelector(ExoTrackSelection.Factory trackSelectionFactory) {
        // JoTube M3: no Context, like 2.10 (Parameters.DEFAULT, no viewport/display based defaults).
        // Media3 would also drop multichannel (> 2 ch, non-Dolby) audio from the automatic selection when it can't tell
        // the device is a TV (no Context); 2.10 had no such constraint.
        super(Parameters.DEFAULT.buildUpon().setConstrainAudioChannelCountToDeviceCapabilities(false).build(),
                trackSelectionFactory);
        // Could help with Shield resolution bug?
        //setParameters(buildUponParameters().setForceHighestSupportedBitrate(true));
    }

    /**
     * JoTube: re-run track selection (e.g. the playing video of the Shorts playlist changed).
     */
    public void reselect() {
        invalidate(/* parameters= */ null); // M3: null = re-run the selection with the current parameters
    }

    public void setOnTrackSelectCallback(TrackSelectorCallback callback) {
        mCallback = callback;
    }

    // JoTube M3: Media3 selects per track type over all renderers (MappedTrackInfo) and returns the definition with
    // its renderer index. The app callback keeps its per-renderer shape (the groups of the renderer the tracks are
    // mapped to; JoTube's renderer order: video 0, audio 1, text 2, see TrackSelectorManager.RENDERER_INDEX_*).

    @Nullable
    @Override
    protected Pair<Definition, Integer> selectVideoTrack(MappedTrackInfo mappedTrackInfo, int[][][] rendererFormatSupports,
                                                         int[] mixedMimeTypeSupports, Parameters params,
                                                         @Nullable String selectedAudioLanguage) throws ExoPlaybackException {
        if (mCallback != null) {
            int rendererIndex = findRenderer(mappedTrackInfo, C.TRACK_TYPE_VIDEO);
            if (rendererIndex == C.INDEX_UNSET) {
                return null;
            }
            Pair<Definition, MediaTrack> resultPair = mCallback.onSelectVideoTrack(mappedTrackInfo.getTrackGroups(rendererIndex), params);

            if (resultPair != null) {
                Log.d(TAG, "selectVideoTrack: choose custom video processing");
                return new Pair<>(resultPair.first, rendererIndex);
            } else {
                return null; // video disabled
            }
        }

        Log.d(TAG, "selectVideoTrack: choose default video processing");

        return super.selectVideoTrack(mappedTrackInfo, rendererFormatSupports, mixedMimeTypeSupports, params, selectedAudioLanguage);
    }

    @Nullable
    @Override
    protected Pair<Definition, Integer> selectAudioTrack(MappedTrackInfo mappedTrackInfo, int[][][] rendererFormatSupports,
                                                         int[] rendererMixedMimeTypeAdaptationSupports, Parameters params) throws ExoPlaybackException {
        if (mCallback != null) {
            int rendererIndex = findRenderer(mappedTrackInfo, C.TRACK_TYPE_AUDIO);
            if (rendererIndex == C.INDEX_UNSET) {
                return null;
            }
            Pair<Definition, MediaTrack> resultPair = mCallback.onSelectAudioTrack(mappedTrackInfo.getTrackGroups(rendererIndex), params);
            if (resultPair != null) {
                Log.d(TAG, "selectAudioTrack: choose custom audio processing");
                return new Pair<>(resultPair.first, rendererIndex);
            } else {
                return null; // audio disabled
            }
        }

        Log.d(TAG, "selectAudioTrack: choose default audio processing");

        return super.selectAudioTrack(mappedTrackInfo, rendererFormatSupports, rendererMixedMimeTypeAdaptationSupports, params);
    }

    @Nullable
    @Override
    protected Pair<Definition, Integer> selectTextTrack(MappedTrackInfo mappedTrackInfo, int[][][] rendererFormatSupports,
                                                        Parameters params, @Nullable String selectedAudioLanguage) throws ExoPlaybackException {
        int rendererIndex = mCallback != null ? findRenderer(mappedTrackInfo, C.TRACK_TYPE_TEXT) : C.INDEX_UNSET;

        if (rendererIndex != C.INDEX_UNSET) {
            Pair<Definition, MediaTrack> resultPair = mCallback.onSelectSubtitleTrack(mappedTrackInfo.getTrackGroups(rendererIndex), params);
            if (resultPair != null) {
                Log.d(TAG, "selectTextTrack: choose custom text processing");
                return new Pair<>(resultPair.first, rendererIndex);
            }
        }

        Log.d(TAG, "selectTextTrack: choose default text processing");

        Pair<Definition, Integer> definitionPair = super.selectTextTrack(mappedTrackInfo, rendererFormatSupports, params, selectedAudioLanguage);

        // Don't invoke if track already has been selected by the app
        if (mCallback != null && definitionPair != null) {
            mCallback.updateSubtitleTrackSelection(mappedTrackInfo.getTrackGroups(definitionPair.second), params, definitionPair.first);
        }

        return definitionPair;
    }

    /**
     * The first renderer of the type that has tracks mapped to it (JoTube has one renderer per type).
     */
    private static int findRenderer(MappedTrackInfo mappedTrackInfo, int trackType) {
        for (int i = 0; i < mappedTrackInfo.getRendererCount(); i++) {
            if (mappedTrackInfo.getRendererType(i) == trackType && mappedTrackInfo.getTrackGroups(i).length > 0) {
                return i;
            }
        }
        return C.INDEX_UNSET;
    }

    private void unlockAllVideoFormats(int[][] formatSupports) {
        final int videoTrackIndex = 0;

        for (int j = 0; j < formatSupports[videoTrackIndex].length; j++) {
            if (formatSupports[videoTrackIndex][j] == FORMAT_NOT_SUPPORTED) { // video format not supported by system decoders
                formatSupports[videoTrackIndex][j] = FORMAT_FORCE_SUPPORT; // force support of video format
            }
        }
    }
}
