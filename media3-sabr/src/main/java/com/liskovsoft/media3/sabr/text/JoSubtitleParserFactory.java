package com.liskovsoft.media3.sabr.text;

import android.text.Html;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.text.CuesWithTiming;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;
import androidx.media3.extractor.text.SubtitleParser;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JoTube M3: Media3's subtitle parsers plus the two WebVTT fixes of the ExoPlayer 2.10 fork:
 * <ul>
 *     <li>HTML entities: Media3's WebvttCueParser knows only {@code &lt; &gt; &amp; &nbsp;} and drops every other
 *     entity (e.g. {@code &#39;} or {@code &quot;} disappear from the text). The others are decoded before parsing.</li>
 *     <li>Long lasting subs: a WebVTT cue is shown 10 s at most (fork's WebvttSubtitle.MAX_CUE_LENGTH_US).</li>
 * </ul>
 * Used by the SABR chunk source and set on the DASH media sources.
 */
@UnstableApi
public final class JoSubtitleParserFactory implements SubtitleParser.Factory {
    private static final long MAX_CUE_LENGTH_US = 10_000_000; // NOTE (fork): lower values may break some videos
    // Every entity except the four Media3 handles itself (decoding &lt;/&gt; here would create fake tags)
    private static final Pattern ENTITY = Pattern.compile("&(?!(?:lt|gt|amp|nbsp);)(#\\d+|#[xX][0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]*);");
    private final SubtitleParser.Factory mDelegate;

    public JoSubtitleParserFactory() {
        this(new DefaultSubtitleParserFactory());
    }

    public JoSubtitleParserFactory(SubtitleParser.Factory delegate) {
        mDelegate = delegate;
    }

    @Override
    public boolean supportsFormat(Format format) {
        return mDelegate.supportsFormat(format);
    }

    @Override
    public int getCueReplacementBehavior(Format format) {
        return mDelegate.getCueReplacementBehavior(format);
    }

    @Override
    public SubtitleParser create(Format format) {
        SubtitleParser parser = mDelegate.create(format);
        return MimeTypes.TEXT_VTT.equals(format.sampleMimeType) ? new WebvttFixParser(parser) : parser;
    }

    /**
     * Decodes the entities Media3 would drop. Returns null when there's nothing to decode.
     */
    @Nullable
    static String decodeEntities(String text) {
        Matcher matcher = ENTITY.matcher(text);
        if (!matcher.find()) {
            return null;
        }

        StringBuffer result = new StringBuffer(text.length());
        do {
            String decoded = Html.fromHtml(matcher.group()).toString();
            // A numeric &, < or > goes back as the entity Media3 knows (a bare < would start a fake tag)
            if ("<".equals(decoded)) {
                decoded = "&lt;";
            } else if (">".equals(decoded)) {
                decoded = "&gt;";
            } else if ("&".equals(decoded)) {
                decoded = "&amp;";
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(decoded));
        } while (matcher.find());
        matcher.appendTail(result);
        return result.toString();
    }

    private static final class WebvttFixParser implements SubtitleParser {
        private final SubtitleParser mParser;

        WebvttFixParser(SubtitleParser parser) {
            mParser = parser;
        }

        @Override
        public void parse(byte[] data, int offset, int length, OutputOptions outputOptions, Consumer<CuesWithTiming> output) {
            String decoded = decodeEntities(new String(data, offset, length, StandardCharsets.UTF_8));
            if (decoded != null) {
                data = decoded.getBytes(StandardCharsets.UTF_8);
                offset = 0;
                length = data.length;
            }

            mParser.parse(data, offset, length, outputOptions, cues -> {
                if (cues.durationUs != C.TIME_UNSET && cues.durationUs > MAX_CUE_LENGTH_US) {
                    output.accept(new CuesWithTiming(cues.cues, cues.startTimeUs, MAX_CUE_LENGTH_US));
                } else {
                    output.accept(cues);
                }
            });
        }

        @Override
        public void reset() {
            mParser.reset();
        }

        @Override
        public int getCueReplacementBehavior() {
            return mParser.getCueReplacementBehavior();
        }
    }
}
