package com.liskovsoft.media3.sabr.text;

import android.text.Html;

import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.text.CuesWithTiming;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;
import androidx.media3.extractor.text.SubtitleParser;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
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
    // A WebVTT cue timing line: start --> end [settings]; hours are optional
    private static final Pattern TIMING = Pattern.compile("((?:\\d+:)?\\d{2}:\\d{2}\\.\\d{3})([ \\t]+-->[ \\t]+)((?:\\d+:)?\\d{2}:\\d{2}\\.\\d{3})");
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
            // A numeric &, < or > goes back as the entity Media3 knows (a bare < would start a fake tag),
            // a line break becomes a space (an empty line would end the cue)
            if (decoded.isEmpty() || decoded.contains("\n") || decoded.contains("\r")) { // fromHtml collapses a lone line break to ""
                decoded = " ";
            } else if ("<".equals(decoded)) {
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

    /**
     * Caps every cue's own end time at start + 10 s (like the fork's WebvttSubtitle). Done on the text: Media3 emits
     * one CuesWithTiming per interval between cue boundaries, so capping those wouldn't cap a cue that overlaps others.
     * Returns null when no cue is longer.
     */
    @Nullable
    static String capCueLengths(String text) {
        Matcher matcher = TIMING.matcher(text);
        if (!matcher.find()) {
            return null;
        }

        boolean changed = false;
        StringBuffer result = new StringBuffer(text.length());
        do {
            long startMs = parseTimestampMs(matcher.group(1));
            long endMs = parseTimestampMs(matcher.group(3));
            String replacement = matcher.group();
            if (endMs - startMs > MAX_CUE_LENGTH_US / 1000) {
                replacement = matcher.group(1) + matcher.group(2) + formatTimestamp(startMs + MAX_CUE_LENGTH_US / 1000);
                changed = true;
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        } while (matcher.find());
        matcher.appendTail(result);
        return changed ? result.toString() : null;
    }

    private static long parseTimestampMs(String timestamp) {
        String[] parts = timestamp.split("[:.]");
        long ms = 0;
        for (int i = 0; i < parts.length - 1; i++) {
            ms = ms * 60 + Long.parseLong(parts[i]);
        }
        return ms * 1000 + Long.parseLong(parts[parts.length - 1]);
    }

    private static String formatTimestamp(long ms) {
        return String.format(Locale.US, "%02d:%02d:%02d.%03d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000);
    }

    private static final class WebvttFixParser implements SubtitleParser {
        private final SubtitleParser mParser;

        WebvttFixParser(SubtitleParser parser) {
            mParser = parser;
        }

        @Override
        public void parse(byte[] data, int offset, int length, OutputOptions outputOptions, Consumer<CuesWithTiming> output) {
            String text = new String(data, offset, length, StandardCharsets.UTF_8);
            String decoded = decodeEntities(text);
            String capped = capCueLengths(decoded != null ? decoded : text);
            String result = capped != null ? capped : decoded;
            if (result != null) {
                data = result.getBytes(StandardCharsets.UTF_8);
                offset = 0;
                length = data.length;
            }

            mParser.parse(data, offset, length, outputOptions, output);
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
