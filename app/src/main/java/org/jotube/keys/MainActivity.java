package org.jotube.keys;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Status and a shortcut to the accessibility settings, where the service is switched on once. */
public class MainActivity extends Activity {
    private TextView mStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int pad = (int) (48 * getResources().getDisplayMetrics().density);

        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.HORIZONTAL);
        screen.setGravity(Gravity.CENTER);
        screen.setBackgroundColor(Color.rgb(0x0F, 0x12, 0x17));
        screen.setPadding(pad, pad / 2, pad, pad / 2);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        screen.addView(root, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        // The remote (vector: sharp on every TV size); the two remapped buttons pulse softly
        ImageView remote = new ImageView(this);
        remote.setImageResource(R.drawable.remote_anim);
        remote.setAdjustViewBounds(true);
        remote.setScaleType(ImageView.ScaleType.FIT_CENTER);
        screen.addView(remote, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.8f));
        Drawable d = remote.getDrawable();
        if (d instanceof Animatable) {
            ((Animatable) d).start();
        }

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.banner);
        logo.setAdjustViewBounds(true);
        root.addView(logo, new LinearLayout.LayoutParams((int) (280 * getResources().getDisplayMetrics().density), LinearLayout.LayoutParams.WRAP_CONTENT));


        TextView info = new TextView(this);
        info.setText("YouTube-knappen öppnar JoTube\nNetflix-knappen öppnar Plex");
        info.setTextColor(Color.LTGRAY);
        info.setTextSize(20);
        info.setGravity(Gravity.CENTER);
        info.setPadding(0, pad / 2, 0, pad / 2);
        root.addView(info);

        mStatus = new TextView(this);
        mStatus.setTextSize(20);
        mStatus.setGravity(Gravity.CENTER);
        mStatus.setPadding(0, 0, 0, pad / 2);
        root.addView(mStatus);

        Button open = new Button(this);
        open.setText("Öppna tillgänglighetsinställningar");
        open.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (RuntimeException e) {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            }
        });
        root.addView(open);

        setContentView(screen);
        open.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean on = isServiceOn();
        mStatus.setText(on ? "Påslagen ✓" : "Avstängd: slå på \"JoKeys knappar\" under Tillgänglighet");
        mStatus.setTextColor(on ? Color.rgb(0x7C, 0xD9, 0x92) : Color.rgb(0xFF, 0xB0, 0x70));
    }

    private boolean isServiceOn() {
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) {
            return false;
        }
        String me = new ComponentName(this, KeyService.class).flattenToString();
        String meShort = new ComponentName(this, KeyService.class).flattenToShortString();
        for (String s : enabled.split(":")) {
            if (s.equalsIgnoreCase(me) || s.equalsIgnoreCase(meShort)) {
                return true;
            }
        }
        return false;
    }
}
