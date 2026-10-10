package com.liskovsoft.media3.sabr.parser.models;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.Metadata;

import java.util.Objects;

/**
 * Carries YouTube's SABR format discriminator through track selection.
 * JoTube M3: also carries {@code isDrc} and {@code lastModified}, which the 2.10 fork had as extra {@link Format}
 * fields (Media3's Format is final). Read them with {@link #isDrc(Format)} / {@link #getLastModified(Format)}.
 */
public final class SabrFormatMetadata implements Metadata.Entry, Parcelable {
    @Nullable public final String xTags;
    @Nullable public final String audioTrackId;
    public final boolean isDrc;
    /** YouTube's last-modified value (LMT) or {@link Format#NO_VALUE}. */
    public final long lastModified;

    public SabrFormatMetadata(@Nullable String xTags, @Nullable String audioTrackId) {
        this(xTags, audioTrackId, false, Format.NO_VALUE);
    }

    public SabrFormatMetadata(@Nullable String xTags, @Nullable String audioTrackId, boolean isDrc, long lastModified) {
        this.xTags = xTags;
        this.audioTrackId = audioTrackId;
        this.isDrc = isDrc;
        this.lastModified = lastModified;
    }

    private SabrFormatMetadata(Parcel in) {
        this.xTags = in.readString();
        this.audioTrackId = in.readString();
        this.isDrc = in.readInt() != 0;
        this.lastModified = in.readLong();
    }

    /** The SABR metadata entry of a format, or null. */
    @Nullable
    public static SabrFormatMetadata from(@Nullable Format format) {
        if (format == null || format.metadata == null) {
            return null;
        }
        for (int i = 0; i < format.metadata.length(); i++) {
            Metadata.Entry entry = format.metadata.get(i);
            if (entry instanceof SabrFormatMetadata) {
                return (SabrFormatMetadata) entry;
            }
        }
        return null;
    }

    public static boolean isDrc(@Nullable Format format) {
        SabrFormatMetadata metadata = from(format);
        return metadata != null && metadata.isDrc;
    }

    public static long getLastModified(@Nullable Format format) {
        SabrFormatMetadata metadata = from(format);
        return metadata != null ? metadata.lastModified : Format.NO_VALUE;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(xTags);
        dest.writeString(audioTrackId);
        dest.writeInt(isDrc ? 1 : 0);
        dest.writeLong(lastModified);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof SabrFormatMetadata)) {
            return false;
        }
        SabrFormatMetadata other = (SabrFormatMetadata) obj;
        return isDrc == other.isDrc && lastModified == other.lastModified
                && Objects.equals(xTags, other.xTags) && Objects.equals(audioTrackId, other.audioTrackId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(xTags, audioTrackId, isDrc, lastModified);
    }

    public static final Parcelable.Creator<SabrFormatMetadata> CREATOR =
            new Parcelable.Creator<SabrFormatMetadata>() {
                @Override
                public SabrFormatMetadata createFromParcel(Parcel in) {
                    return new SabrFormatMetadata(in);
                }

                @Override
                public SabrFormatMetadata[] newArray(int size) {
                    return new SabrFormatMetadata[size];
                }
            };
}
