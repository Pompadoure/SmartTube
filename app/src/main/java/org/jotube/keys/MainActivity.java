package org.jotube.keys;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
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

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.rgb(0x16, 0x1B, 0x22));
        int pad = (int) (48 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("JoKeys");
        title.setTextColor(Color.WHITE);
        title.setTextSize(32);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

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

        setContentView(root);
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
