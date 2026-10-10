package com.liskovsoft.smartyoutubetv2.common.exoplayer;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.exoplayer.dash.DashSegmentIndex;
import androidx.media3.exoplayer.dash.manifest.DashManifest;
import androidx.media3.exoplayer.dash.manifest.DashManifestParser;
import androidx.media3.exoplayer.dash.manifest.Period;
import androidx.media3.exoplayer.dash.manifest.RangedUri;
import androidx.media3.exoplayer.dash.manifest.Representation;
import androidx.media3.exoplayer.dash.manifest.Representation.MultiSegmentRepresentation;
import androidx.media3.exoplayer.dash.manifest.SegmentBase.SegmentList;
import androidx.media3.exoplayer.dash.manifest.SegmentBase.SegmentTimelineElement;
import com.liskovsoft.sharedutils.helpers.Helpers;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * JoTube M3: ported to Media3 1.11.1 (field names checked against Media3: Period.startMs,
 * MultiSegmentRepresentation.segmentBase, SegmentList.mediaSegments/segmentTimeline, SegmentTimelineElement.startTime/duration).
 * The app is not minified, so the reflection keeps working.
 */
@SuppressWarnings("unchecked")
public class LiveDashManifestParser extends DashManifestParser {
    private static final String TAG = LiveDashManifestParser.class.getSimpleName();
    // Should be close to zero but not zero to increase buffer size to 30 sec (Radio Record).
    // Higher values may produce 'url not working' error.
    private static final long MAX_LIVE_STREAM_LENGTH_MS = 30 * 1_000;
    // Usually gaming streams. 10 hrs max.
    private static final long MAX_PAST_STREAM_LENGTH_MS = 12 * 60 * 60 * 1_000;
    private static final long MAX_NEW_STREAM_LENGTH_MS = 30 * 1_000;
    private DashManifest mOldManifest;
    private long mOldSegmentNum;

    @Override
    public DashManifest parse(Uri uri, InputStream inputStream) throws IOException {
        DashManifest manifest = super.parse(uri, inputStream);

        //Log.d(TAG, "Parse start: " + System.currentTimeMillis());

        appendManifest(manifest);

        //Log.d(TAG, "Parse end: " + System.currentTimeMillis());

        return mOldManifest;
    }

    private void appendManifest(DashManifest newManifest) {
        if (newManifest == null) {
            return;
        }

        // Optimize ram usage on short streams (< 2 hours)
        if (getFirstSegmentNum(newManifest) == 0) { // Short stream. No need to do something special.
            mOldManifest = newManifest;
            // Below line will be needed later (> 2 hours), when the stream no longer starts from 0 segment
            mOldSegmentNum = getLastSegmentNum(newManifest);
            return;
        }

        // Even 4+ hours streams could have different length.
        // So, we should take into account last segment num instead of first one.
        long newSegmentNum = getLastSegmentNum(newManifest);

        if (mOldManifest == null) {
            //recreateMissingSegments(newManifest);

            //newManifest.availabilityStartTimeMs = -1;
            Period newPeriod = newManifest.getPeriod(0);
            // TODO: modified
            //newPeriod.startMs = 0;
            Helpers.setField(newPeriod, "startMs", 0);
            mOldSegmentNum = newSegmentNum;

            for (int i = 0; i < newPeriod.adaptationSets.size(); i++) {
                for (int j = 0; j < newPeriod.adaptationSets.get(i).representations.size(); j++) {
                    MultiSegmentRepresentation representation = (MultiSegmentRepresentation) newPeriod.adaptationSets.get(i).representations.get(j);
                    //representation.presentationTimeOffsetUs = 0;

                    // TODO: modified
                    //SegmentList newSegmentList = (SegmentList) representation.segmentBase;
                    SegmentList newSegmentList = (SegmentList) Helpers.getField(representation, "segmentBase");
                    // TODO: modified
                    //newSegmentList.presentationTimeOffset = 0;
                    Helpers.setField(newSegmentList, "presentationTimeOffset", 0);
                    // TODO: modified
                    //newSegmentList.startNumber = 0;
                    Helpers.setField(newSegmentList, "startNumber", 0);
                }
            }

            mOldManifest = newManifest;

            return;
        }

        //long oldSegmentNum = getFirstSegmentNum(mManifest);

        Period oldPeriod = mOldManifest.getPeriod(0);
        Period newPeriod = newManifest.getPeriod(0);

        for (int i = 0; i < oldPeriod.adaptationSets.size(); i++) {
            for (int j = 0; j < oldPeriod.adaptationSets.get(i).representations.size(); j++) {
                appendRepresentation(
                        oldPeriod.adaptationSets.get(i).representations.get(j),
                        newPeriod.adaptationSets.get(i).representations.get(j),
                        newSegmentNum - mOldSegmentNum
                );
            }
        }

        mOldSegmentNum = newSegmentNum;

        //mManifest.timeShiftBufferDepthMs += (newSegmentNum - oldSegmentNum) * 5_000;
    }

    private static void appendRepresentation(Representation oldRepresentation, Representation newRepresentation, long segmentNumShift) {
        if (segmentNumShift <= 0) {
            return;
        }

        MultiSegmentRepresentation oldMultiRepresentation = (MultiSegmentRepresentation) oldRepresentation;
        MultiSegmentRepresentation newMultiRepresentation = (MultiSegmentRepresentation) newRepresentation;

        // TODO: modified
        //SegmentList oldSegmentList = (SegmentList) oldRepresentation.segmentBase;
        SegmentList oldSegmentList = (SegmentList) Helpers.getField(oldMultiRepresentation, "segmentBase");
        // TODO: modified
        //SegmentList newSegmentList = (SegmentList) newRepresentation.segmentBase;
        SegmentList newSegmentList = (SegmentList) Helpers.getField(newMultiRepresentation, "segmentBase");

        // TODO: modified
        //List<RangedUri> oldMediaSegments = oldSegmentList.mediaSegments;
        List<RangedUri> oldMediaSegments = (List<RangedUri>) Helpers.getField(oldSegmentList, "mediaSegments");
        // TODO: modified
        //List<RangedUri> newMediaSegments = newSegmentList.mediaSegments;
        List<RangedUri> newMediaSegments = (List<RangedUri>) Helpers.getField(newSegmentList, "mediaSegments");

        oldMediaSegments.addAll(
                newMediaSegments.subList(newMediaSegments.size() - (int) segmentNumShift, newMediaSegments.size()));

        // TODO: modified
        //List<SegmentTimelineElement> oldSegmentTimeline = oldSegmentList.segmentTimeline;
        List<SegmentTimelineElement> oldSegmentTimeline = (List<SegmentTimelineElement>) Helpers.getField(oldSegmentList, "segmentTimeline");

        // segmentTimeline is the same for all segments
        if (oldMediaSegments.size() != oldSegmentTimeline.size()) {
            SegmentTimelineElement lastTimeline = oldSegmentTimeline.get(oldSegmentTimeline.size() - 1);
            // TODO: modified
            //long lastTimelineDuration = lastTimeline.duration;
            long lastTimelineDuration = (Long) Helpers.getField(lastTimeline, "duration");
            // TODO: modified
            //long lastTimelineStartTime = lastTimeline.startTime;
            long lastTimelineStartTime = (Long) Helpers.getField(lastTimeline, "startTime");

            for (int i = 1; i <= segmentNumShift; i++) {
                oldSegmentTimeline.add(new SegmentTimelineElement(lastTimelineStartTime + (lastTimelineDuration * i), lastTimelineDuration));
            }

            //oldSegmentTimeline.addAll(
            //        newSegmentList.segmentTimeline.subList(newSegmentList.segmentTimeline.size() - (int) segmentNumShift - 1, newSegmentList.segmentTimeline.size()));
        }
    }

    // JoTube M3: recreateMissingSegments / recreateRepresentation / lazyRecreateRepresentations and the
    // MultiSegmentRepresentationWrapper were dead code in 2.10 (the only call was commented out) and were removed in
    // the Media3 port (see git history / exoplayer-amzn-2.10.6 era version of this file).

    private static long getFirstSegmentNum(DashManifest manifest) {
        DashSegmentIndex dashSegmentIndex = manifest.getPeriod(0).adaptationSets.get(0).representations.get(0).getIndex();
        return dashSegmentIndex.getFirstSegmentNum();
    }

    private static long getLastSegmentNum(DashManifest manifest) {
        DashSegmentIndex dashSegmentIndex = manifest.getPeriod(0).adaptationSets.get(0).representations.get(0).getIndex();
        return dashSegmentIndex.getFirstSegmentNum() + dashSegmentIndex.getSegmentCount(DashSegmentIndex.INDEX_UNBOUNDED) - 1;
    }
}
