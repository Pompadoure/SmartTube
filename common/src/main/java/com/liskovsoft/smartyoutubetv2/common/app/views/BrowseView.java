package com.liskovsoft.smartyoutubetv2.common.app.views;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.SettingsGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.errors.ErrorFragmentData;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.BrowseSection;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;

import java.util.List;

public interface BrowseView {
    void addSection(int index, BrowseSection section);
    void removeSection(BrowseSection category);
    void removeAllSections();
    void selectSection(int index, boolean focusOnContent);
    void updateSection(VideoGroup group);
    void updateSection(SettingsGroup group);
    void clearSection(BrowseSection section);
    void selectSectionItem(int index);
    void selectSectionItem(Video item);
    void showError(ErrorFragmentData data);
    void showProgressBar(boolean show);
    boolean isProgressBarShowing();
    void focusOnContent();
    /**
     * JoTube: back to the sidebar (e.g. after the Shorts player that was started from the sidebar is closed)
     */
    void showHeaders();
    /**
     * JoTube: the section content is invisible (the Shorts player is about to open on top of it)
     */
    void setContentHidden(boolean hidden);
    /**
     * JoTube: warm the image cache with the card thumbnails of the content that is about to be shown (sidebar warm-up)
     */
    void preloadCardThumbnails(List<Video> videos);
    /**
     * JoTube: warm the image cache with the vertical thumbnails and blurred backgrounds of the first Shorts
     */
    void preloadShorts(List<Video> videos);
    boolean isEmpty();
    void updateBadge();
}
