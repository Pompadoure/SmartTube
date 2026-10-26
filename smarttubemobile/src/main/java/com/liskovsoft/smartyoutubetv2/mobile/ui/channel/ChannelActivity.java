package com.liskovsoft.smartyoutubetv2.mobile.ui.channel;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.ChannelPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.ChannelView;

public class ChannelActivity extends ChannelBaseActivity implements ChannelView {
    private ChannelPresenter mPresenter;

    @Override
    protected void onPresenterAttach() {
        mPresenter = ChannelPresenter.instance(this);
        mPresenter.setView(this);
        mPresenter.onViewInitialized();
    }

    @Override
    protected void onPresenterDetach() {
        mPresenter.onViewDestroyed();
    }

    @Override
    protected void onPresenterFinish() {
        mPresenter.onFinish();
    }

    @Override
    protected void onVideoClicked(Video video) {
        mPresenter.onVideoItemClicked(video);
    }

    @Override
    protected void onVideoLongClicked(Video video) {
        mPresenter.onVideoItemLongClicked(video);
    }

    @Override
    protected void onScrollEnd(Video last) {
        mPresenter.onScrollEnd(last);
    }

    @Override
    protected String channelTitle() {
        Video channel = mPresenter != null ? mPresenter.getChannel() : null;
        return channel != null ? channel.title : null;
    }

    @Override
    public void setPosition(int index) {
        // NOP
    }
}
