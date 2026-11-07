package com.liskovsoft.smartyoutubetv2.common.misc;

import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;

/** JoTube: the stream info of the video that is open in the player, kept so the phone can hand it to a Cast device. */
public final class CastSource {
    private static volatile String sVideoId;
    private static volatile MediaItemFormatInfo sFormatInfo;

    private CastSource() {}

    public static void set(String videoId, MediaItemFormatInfo formatInfo) {
        sVideoId = videoId;
        sFormatInfo = formatInfo;
    }

    public static String getVideoId() {
        return sVideoId;
    }

    public static MediaItemFormatInfo getFormatInfo() {
        return sFormatInfo;
    }
}
