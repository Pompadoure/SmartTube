package com.liskovsoft.smartyoutubetv2.mobile.ui.utils;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Process;
import androidx.core.content.FileProvider;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedList;
import java.util.Locale;

public class LogShare {
    private static final String TAG = LogShare.class.getSimpleName();
    private static final int MAX_LOG_FILES = 5;

    public static void share(Context context) {
        if (context == null) {
            return;
        }

        new Thread(() -> {
            try {
                File logsDir = new File(context.getCacheDir(), "logs");
                if (!logsDir.exists()) {
                    logsDir.mkdirs();
                }

                pruneOldLogs(logsDir);

                String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                File logFile = new File(logsDir, "jotube-log-" + timeStamp + ".txt");

                dumpLogcat(logFile);

                if (!logFile.exists() || logFile.length() == 0) {
                    Log.e(TAG, "Log file is empty or not created");
                    return;
                }

                String authority = context.getPackageName() + ".logshare";
                Uri contentUri = FileProvider.getUriForFile(context, authority, logFile);

                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType("text/plain");
                shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                Intent chooser = Intent.createChooser(shareIntent, "Share log");
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                context.startActivity(chooser);
            } catch (Exception e) {
                Log.e(TAG, "Error sharing log: %s", e.getMessage());
            }
        }, "LogShareThread").start();
    }

    private static void dumpLogcat(File outputFile) {
        int pid = Process.myPid();
        String[] cmd = new String[] {
                "logcat",
                "-d",
                "-t", "60000",
                "-v", "threadtime",
                "--pid=" + pid
        };

        String[] noisyTags = {
                "JoTubeSabr", "SabrStream", "DefaultSabrChunkSource", "SabrMatroskaAdapter",
                "SabrExtractorInput", "CCodec", "CCodecConfig", "ReflectedParamUpdater",
                "AidlBufferPool", "JsonPathTypeAdapter", "VRI", "BLASTBufferQueue",
                "Choreographer", "MediaCodec", "LoudnessCodecController"
        };

        try {
            java.lang.Process process = Runtime.getRuntime().exec(cmd);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                 PrintWriter writer = new PrintWriter(new FileWriter(outputFile))) {
                String line;
                LinkedList<String> lines = new LinkedList<>();
                int totalBytes = 0;
                final int MAX_BYTES = 3 * 1024 * 1024; // ~3 MB

                while ((line = reader.readLine()) != null) {
                    boolean skip = false;
                    for (String tag : noisyTags) {
                        // Noise is dropped, but the lines that tell what failed stay (network errors, retries)
                        if (line.contains(tag) && !line.contains("failed") && !line.contains("Retrying") && !line.contains("Exception")) {
                            skip = true;
                            break;
                        }
                    }
                    if (skip) continue;

                    lines.add(line);
                    totalBytes += line.length() + 1; // +1 for newline

                    while (totalBytes > MAX_BYTES && !lines.isEmpty()) {
                        String removed = lines.removeFirst();
                        totalBytes -= (removed.length() + 1);
                    }
                }

                for (String l : lines) {
                    writer.println(l);
                }
            }
            process.waitFor();
        } catch (Exception e) {
            Log.e(TAG, "Failed to run logcat dump: %s", e.getMessage());
        }
    }

    private static void pruneOldLogs(File logsDir) {
        File[] files = logsDir.listFiles((dir, name) -> name.startsWith("jotube-log-") && name.endsWith(".txt"));
        if (files == null || files.length < MAX_LOG_FILES) {
            return;
        }

        Arrays.sort(files, (f1, f2) -> Long.compare(f1.lastModified(), f2.lastModified()));

        int filesToDelete = files.length - MAX_LOG_FILES + 1;
        for (int i = 0; i < filesToDelete; i++) {
            if (files[i].exists()) {
                files[i].delete();
            }
        }
    }
}
