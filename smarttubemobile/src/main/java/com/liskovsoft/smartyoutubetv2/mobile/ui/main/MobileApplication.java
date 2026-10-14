package com.liskovsoft.smartyoutubetv2.mobile.ui.main;

import androidx.multidex.MultiDexApplication;

import com.liskovsoft.smartyoutubetv2.common.app.views.AppDialogView;
import com.liskovsoft.smartyoutubetv2.common.app.views.BrowseView;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;
import com.liskovsoft.smartyoutubetv2.common.app.views.SearchView;
import com.liskovsoft.smartyoutubetv2.common.app.views.SignInView;
import com.liskovsoft.smartyoutubetv2.common.app.views.SplashView;
import com.liskovsoft.smartyoutubetv2.common.app.views.ViewManager;
import com.liskovsoft.smartyoutubetv2.common.prefs.NetworkData;
import com.liskovsoft.smartyoutubetv2.mobile.ui.browse.BrowseActivity;
import com.liskovsoft.smartyoutubetv2.mobile.ui.dialogs.AppDialogActivity;
import com.liskovsoft.smartyoutubetv2.mobile.ui.playback.PlaybackActivity;
import com.liskovsoft.smartyoutubetv2.mobile.ui.search.SearchActivity;
import com.liskovsoft.smartyoutubetv2.mobile.ui.signin.SignInActivity;

import org.conscrypt.Conscrypt;

import java.security.Provider;
import java.security.Security;

/**
 * JoTube mobile: same engine as the TV app (:common), phone views. Only the views are registered here.
 */
public class MobileApplication extends MultiDexApplication {
    static {
        System.setProperty("http.keepAlive", "false");
    }

    @Override
    public void onCreate() {
        super.onCreate();

        Provider conscryptProvider = null;
        try {
            conscryptProvider = Conscrypt.newProvider();
        } catch (Throwable e) {
            // UnsatisfiedLinkError
        }

        if (conscryptProvider != null && NetworkData.instance(this).isConscryptEnabled()) {
            try {
                Security.insertProviderAt(conscryptProvider, 1);
            } catch (Throwable e) {
                // UnsatisfiedLinkError
            }
        }

        setupViewManager();
    }

    private void setupViewManager() {
        ViewManager viewManager = ViewManager.instance(this);

        viewManager.setRoot(BrowseActivity.class);
        viewManager.register(SplashView.class, SplashActivity.class);
        viewManager.register(BrowseView.class, BrowseActivity.class);
        viewManager.register(PlaybackView.class, PlaybackActivity.class, BrowseActivity.class);
        viewManager.register(AppDialogView.class, AppDialogActivity.class, BrowseActivity.class);
        viewManager.register(SearchView.class, SearchActivity.class, BrowseActivity.class);
        viewManager.register(SignInView.class, SignInActivity.class, BrowseActivity.class);
    }
}
