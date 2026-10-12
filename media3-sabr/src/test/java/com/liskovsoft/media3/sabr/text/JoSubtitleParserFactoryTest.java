package com.liskovsoft.media3.sabr.text;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

/**
 * JoTube M3: the fork's WebVTT fixes (entities, 10 s cue cap) done on the WebVTT text.
 */
@RunWith(AndroidJUnit4.class)
@Config(sdk = 28)
public class JoSubtitleParserFactoryTest {
    @Test
    public void capsOnlyLongCues() {
        String vtt = "WEBVTT\n\n00:00:00.000 --> 00:00:30.000 align:start\nA\n\n00:05.000 --> 00:08.000\nB\n\n"
                + "1:00:00.500 --> 1:00:20.000\nC\n";
        String expected = "WEBVTT\n\n00:00:00.000 --> 00:00:10.000 align:start\nA\n\n00:05.000 --> 00:08.000\nB\n\n"
                + "1:00:00.500 --> 01:00:10.500\nC\n";
        assertEquals(expected, JoSubtitleParserFactory.capCueLengths(vtt));
    }

    @Test
    public void shortCuesUnchanged() {
        assertNull(JoSubtitleParserFactory.capCueLengths("WEBVTT\n\n00:00:01.000 --> 00:00:11.000\nA\n"));
    }

    @Test
    public void decodesEntitiesMedia3WouldDrop() {
        assertEquals("it's \"x\" &lt;b&gt; &amp; &lt; a b",
                JoSubtitleParserFactory.decodeEntities("it&#39;s &quot;x&quot; &lt;b&gt; &amp; &#60; a&#10;b"));
        assertNull(JoSubtitleParserFactory.decodeEntities("no &amp; entities &lt; here &nbsp;"));
    }
}
