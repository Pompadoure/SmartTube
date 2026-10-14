package com.liskovsoft.smartyoutubetv2.mobile.ui.signin;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.liskovsoft.smartyoutubetv2.common.app.presenters.SignInPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.SignInView;
import com.liskovsoft.smartyoutubetv2.mobile.R;
import com.liskovsoft.smartyoutubetv2.mobile.ui.main.MobileActivity;
import com.liskovsoft.smartyoutubetv2.mobile.ui.widgets.Ui;

/**
 * Sign in with a code (the same flow as on TV): the code is shown, the link opens in the phone's browser.
 */
public class SignInActivity extends MobileActivity implements SignInView {
    private SignInPresenter mPresenter;
    private TextView mCode;
    private TextView mUrl;
    private Button mOpen;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(getColor(R.color.yt_bg));
        root.setPadding(Ui.dp(this, 24), Ui.dp(this, 24), Ui.dp(this, 24), Ui.dp(this, 24));

        TextView title = new TextView(this);
        title.setText(R.string.sign_in_title);
        title.setTextColor(getColor(R.color.yt_text));
        title.setTextSize(24);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText(R.string.sign_in_hint);
        hint.setTextColor(getColor(R.color.yt_text_secondary));
        hint.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 24));
        root.addView(hint);

        mCode = new TextView(this);
        mCode.setTextColor(getColor(R.color.yt_text));
        mCode.setTextSize(36);
        mCode.setTextIsSelectable(true);
        root.addView(mCode);

        mUrl = new TextView(this);
        mUrl.setTextColor(getColor(R.color.yt_text_secondary));
        mUrl.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 16));
        root.addView(mUrl);

        mOpen = new Button(this);
        mOpen.setText(R.string.sign_in_open);
        mOpen.setVisibility(View.GONE);
        root.addView(mOpen, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);

        mPresenter = SignInPresenter.instance(this);
        mPresenter.setView(this);
        mPresenter.onViewInitialized();
    }

    @Override
    public void showCode(String userCode, String signInUrl) {
        showCode(userCode, signInUrl, signInUrl);
    }

    @Override
    public void showCode(String userCode, String signInUrl, String fullSignInUrl) {
        mCode.setText(userCode);
        mUrl.setText(signInUrl);

        final String target = fullSignInUrl != null ? fullSignInUrl : signInUrl;
        mOpen.setVisibility(target != null ? View.VISIBLE : View.GONE);
        mOpen.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(target.startsWith("http") ? target : "https://" + target)));
            } catch (RuntimeException e) {
                // no browser
            }
        });
    }

    @Override
    public void close() {
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mPresenter.onViewDestroyed();
    }
}
