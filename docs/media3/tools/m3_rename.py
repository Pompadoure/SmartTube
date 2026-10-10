#!/usr/bin/env python3
"""JoTube M3: mechanical ExoPlayer 2.10 -> Media3 import/package rename for ported files.
Usage: m3_rename.py FILE... (rewrites in place, keeps line endings). Only renames; API changes are hand work."""
import re, sys

E = 'com.google.android.exoplayer2.'
# Most specific first.
MAP = [
    ('source.sabr.', 'com.liskovsoft.media3.sabr.'),
    ('source.sabr;', 'com.liskovsoft.media3.sabr;'),
    ('FormatHolder', 'androidx.media3.exoplayer.FormatHolder'),
    ('SeekParameters', 'androidx.media3.exoplayer.SeekParameters'),
    ('ExoPlaybackException', 'androidx.media3.exoplayer.ExoPlaybackException'),
    ('RendererCapabilities', 'androidx.media3.exoplayer.RendererCapabilities'),
    ('decoder.DecoderInputBuffer', 'androidx.media3.decoder.DecoderInputBuffer'),
    ('drm.DrmInitData', 'androidx.media3.common.DrmInitData'),
    ('drm.', 'androidx.media3.exoplayer.drm.'),
    ('metadata.Metadata;', 'androidx.media3.common.Metadata;'),
    ('metadata.Metadata.', 'androidx.media3.common.Metadata.'),
    ('metadata.', 'androidx.media3.extractor.metadata.'),
    ('extractor.', 'androidx.media3.extractor.'),
    ('offline.StreamKey', 'androidx.media3.common.StreamKey'),
    ('offline.', 'androidx.media3.exoplayer.offline.'),
    ('source.TrackGroupArray', 'androidx.media3.exoplayer.source.TrackGroupArray'),
    ('source.TrackGroup', 'androidx.media3.common.TrackGroup'),
    ('source.', 'androidx.media3.exoplayer.source.'),
    ('trackselection.TrackSelection;', 'androidx.media3.exoplayer.trackselection.ExoTrackSelection;'),
    ('trackselection.', 'androidx.media3.exoplayer.trackselection.'),
    ('upstream.DataSource', 'androidx.media3.datasource.DataSource'),
    ('upstream.DataSpec', 'androidx.media3.datasource.DataSpec'),
    ('upstream.TransferListener', 'androidx.media3.datasource.TransferListener'),
    ('upstream.HttpDataSource', 'androidx.media3.datasource.HttpDataSource'),
    ('upstream.DefaultHttpDataSource', 'androidx.media3.datasource.DefaultHttpDataSource'),
    ('upstream.', 'androidx.media3.exoplayer.upstream.'),
    ('util.MimeTypes', 'androidx.media3.common.MimeTypes'),
    ('util.', 'androidx.media3.common.util.'),
    ('C;', 'androidx.media3.common.C;'),
    ('C.', 'androidx.media3.common.C.'),
    ('Format;', 'androidx.media3.common.Format;'),
    ('ParserException', 'androidx.media3.common.ParserException'),
    ('Timeline', 'androidx.media3.common.Timeline'),
    ('Player', 'androidx.media3.common.Player'),
]

def fix(text):
    def rep(m):
        rest = m.group(2)
        for old, new in MAP:
            if rest.startswith(old):
                return m.group(1) + new + rest[len(old):]
        return m.group(0)
    text = re.sub(r'((?:^|\W)(?:import(?: static)? |package |\{@link |@see |))' + re.escape(E) + r'([\w.;#]+)', rep, text, flags=re.M)
    # Remaining fully-qualified uses in code / javadoc
    text = text.replace('package com.google.android.exoplayer2.source.sabr', 'package com.liskovsoft.media3.sabr')
    text = re.sub(r'option java_package = "com\.google\.android\.exoplayer2\.source\.sabr\.', 'option java_package = "com.liskovsoft.media3.sabr.', text)
    # TrackSelection type -> ExoTrackSelection (only whole-word type uses)
    text = re.sub(r'(?<![\w.])TrackSelection(?=[\s\[>.,)])', 'ExoTrackSelection', text)
    text = text.replace('ExoExoTrackSelection', 'ExoTrackSelection')
    # Media3 ExtractorInput & friends no longer throw InterruptedException
    text = re.sub(r'throws IOException, InterruptedException', 'throws IOException', text)
    return text

for path in sys.argv[1:]:
    with open(path, newline='') as f:
        t = f.read()
    n = fix(t)
    if n != t:
        with open(path, 'w', newline='') as f:
            f.write(n)
