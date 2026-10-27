import sys

def modify_file(filepath, replacements):
    with open(filepath, 'rb') as f:
        content = f.read()

    for old, new in replacements:
        content = content.replace(old, new)

    with open(filepath, 'wb') as f:
        f.write(content)

replacements = [
    (b'import androidx.media3.common.C;\n', b'import com.google.android.exoplayer2.C;\n'),
    (b'import androidx.media3.common.C;\r\n', b'import com.google.android.exoplayer2.C;\r\n')
]

modify_file('smarttubetv/src/main/java/com/liskovsoft/smartyoutubetv2/tv/ui/playback/ShortsPreview.java', replacements)
