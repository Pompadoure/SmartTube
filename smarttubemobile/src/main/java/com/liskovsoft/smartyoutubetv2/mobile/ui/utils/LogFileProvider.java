package com.liskovsoft.smartyoutubetv2.mobile.ui.utils;

import androidx.core.content.FileProvider;

/**
 * Own FileProvider subclass for the shared logs: a library of the app already declares androidx.core.content.FileProvider,
 * and two providers with the same class name make the manifest merge fail.
 */
public class LogFileProvider extends FileProvider {
}
