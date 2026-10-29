package org.jotube.keys;

import android.app.Activity;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The JoKeys screen: the remote on the left (vector, the remapped buttons pulse), the two buttons and their apps on the
 * right. A button can open any installed app or keep its own one.
 */
public class MainActivity extends Activity {
    private static final int BG_TOP = 0xFF12161D;
    private static final int BG_BOTTOM = 0xFF07090C;
    private static final int CARD = 0xFF161B23;
    private static final int CARD_FOCUS = 0xFF1E2530;
    private static final int LINE = 0xFF262D39;
    private static final int TEXT = 0xFFF2F4F7;
    private static final int MUTED = 0xFF8C95A4;
    private static final int RED = 0xFFE62117;
    private static final int AMBER = 0xFFE5A00D;
    private static final int GREEN = 0xFF4CC38A;

    // The remote's viewport and its two remapped buttons (drawable/remote.xml)
    private static final float VIEW_W = 160f, VIEW_H = 520f, BTN_Y = 345f, BTN_R = 20f;
    private static final float[] BTN_X = {52f, 108f};
    private static final String[] BUTTONS = {Mapping.YOUTUBE, Mapping.NETFLIX};

    private Typeface mBold, mMedium, mRegular;
    private TextView mStatusText;
    private View mStatusDot;
    private LinearLayout mStatusPill;
    private TextView mAccessButton;
    private final TextView[] mAppNames = new TextView[2];
    private final ImageView[] mAppIcons = new ImageView[2];
    private final ImageView[] mOverlays = new ImageView[2];
    private ImageView mRemote;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mBold = font("Poppins-Bold.ttf");
        mMedium = font("Poppins-Medium.ttf");
        mRegular = font("Poppins-Regular.ttf");

        FrameLayout screen = new FrameLayout(this);
        screen.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{BG_TOP, BG_BOTTOM}));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(56), dp(32), dp(64), dp(32));
        screen.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        row.addView(buildRemote(), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.85f));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.15f);
        scrollParams.leftMargin = dp(40);
        row.addView(scroll, scrollParams);
        scroll.addView(buildPanel());

        setContentView(screen);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    // ---------------------------------------------------------------- remote

    private View buildRemote() {
        FrameLayout frame = new FrameLayout(this);
        mRemote = new ImageView(this);
        mRemote.setImageResource(R.drawable.remote_anim);
        mRemote.setScaleType(ImageView.ScaleType.FIT_CENTER);
        frame.addView(mRemote, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        for (int i = 0; i < 2; i++) {
            ImageView overlay = new ImageView(this);
            overlay.setVisibility(View.GONE);
            mOverlays[i] = overlay;
            frame.addView(overlay, new FrameLayout.LayoutParams(1, 1));
        }

        mRemote.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> v.post(this::placeOverlays));
        Drawable d = mRemote.getDrawable();
        if (d instanceof Animatable) {
            ((Animatable) d).start();
        }
        return frame;
    }

    /** An app other than the drawn JoTube / Plex: its round icon on the remote's button. */
    private void placeOverlays() {
        int w = mRemote.getWidth(), h = mRemote.getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        float scale = Math.min(w / VIEW_W, h / VIEW_H);
        float left = (w - VIEW_W * scale) / 2f, top = (h - VIEW_H * scale) / 2f;
        int size = Math.round(2 * BTN_R * scale);

        for (int i = 0; i < 2; i++) {
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) mOverlays[i].getLayoutParams();
            int x = Math.round(left + (BTN_X[i] - BTN_R) * scale), y = Math.round(top + (BTN_Y - BTN_R) * scale);
            if (p.width != size || p.leftMargin != x || p.topMargin != y) {
                p.width = size;
                p.height = size;
                p.leftMargin = x;
                p.topMargin = y;
                mOverlays[i].setLayoutParams(p);
            }
        }
    }

    // ---------------------------------------------------------------- panel

    private View buildPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        panel.setPadding(dp(6), dp(6), dp(6), dp(6));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.mipmap.launcher);
        header.addView(icon, new LinearLayout.LayoutParams(dp(56), dp(56)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(16), 0, 0, 0);
        titles.addView(text("JoKeys", 30, mBold, TEXT));
        TextView sub = text("Genvägar på fjärrkontrollen", 15, mRegular, MUTED);
        sub.setPadding(0, dp(4), 0, 0);
        titles.addView(sub);
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        mStatusPill = new LinearLayout(this);
        mStatusPill.setOrientation(LinearLayout.HORIZONTAL);
        mStatusPill.setGravity(Gravity.CENTER_VERTICAL);
        mStatusPill.setPadding(dp(14), dp(8), dp(16), dp(8));
        mStatusDot = new View(this);
        mStatusPill.addView(mStatusDot, new LinearLayout.LayoutParams(dp(8), dp(8)));
        mStatusText = text("", 13, mMedium, TEXT);
        mStatusText.setPadding(dp(8), 0, 0, 0);
        mStatusPill.addView(mStatusText);
        header.addView(mStatusPill);
        panel.addView(header);

        panel.addView(section("KNAPPAR"));
        String[] titlesText = {"YouTube-knappen", "Netflix-knappen"};
        int[] colors = {RED, AMBER};
        for (int i = 0; i < 2; i++) {
            panel.addView(card(i, titlesText[i], colors[i]), cardParams());
        }

        panel.addView(section("INSTÄLLNINGAR"));
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        mAccessButton = pill("Tillgänglighet", false);
        mAccessButton.setOnClickListener(v -> openAccessibility());
        actions.addView(mAccessButton);
        TextView reset = pill("Återställ standard", false);
        reset.setOnClickListener(v -> {
            Mapping.reset(this);
            refresh();
        });
        LinearLayout.LayoutParams resetParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        resetParams.leftMargin = dp(12);
        actions.addView(reset, resetParams);
        panel.addView(actions);

        TextView hint = text("Ändringar gäller direkt. Övriga knappar fungerar som vanligt.", 13, mRegular, MUTED);
        hint.setPadding(dp(4), dp(20), 0, 0);
        panel.addView(hint);
        return panel;
    }

    private View card(int index, String title, int accent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(18), dp(14), dp(22), dp(14));
        card.setFocusable(true);
        card.setClickable(true);
        card.setBackground(cardBackground(accent));
        card.setOnClickListener(v -> pick(index, title));

        FrameLayout badge = new FrameLayout(this);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(accent);
        badge.setBackground(ring);
        ImageView appIcon = new ImageView(this);
        appIcon.setPadding(dp(9), dp(9), dp(9), dp(9));
        appIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        mAppIcons[index] = appIcon;
        badge.addView(appIcon, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        card.addView(badge, new LinearLayout.LayoutParams(dp(52), dp(52)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(18), 0, dp(12), 0);
        texts.addView(text(title, 13, mMedium, MUTED));
        TextView app = text("", 19, mBold, TEXT);
        app.setPadding(0, dp(4), 0, 0);
        app.setSingleLine(true);
        app.setEllipsize(TextUtils.TruncateAt.END);
        mAppNames[index] = app;
        texts.addView(app);
        card.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        card.addView(text("Ändra  ›", 14, mMedium, MUTED));
        return card;
    }

    private void refresh() {
        boolean on = isServiceOn();
        mStatusText.setText(on ? "Aktiv" : "Inte påslagen");
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(on ? GREEN : AMBER);
        mStatusDot.setBackground(dot);
        mStatusPill.setBackground(rounded(((on ? GREEN : AMBER) & 0x00FFFFFF) | 0x26000000, dp(20), 0, 0));
        mAccessButton.setText(on ? "Tillgänglighet" : "Slå på i Tillgänglighet");
        mAccessButton.setBackground(pillBackground(!on));

        PackageManager pm = getPackageManager();
        for (int i = 0; i < 2; i++) {
            String pkg = Mapping.get(this, BUTTONS[i]);
            Drawable icon = null;
            String name;
            if (pkg.isEmpty()) {
                name = i == 0 ? "YouTube (som förut)" : "Netflix (som förut)";
            } else {
                try {
                    name = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
                    icon = pm.getApplicationIcon(pkg);
                } catch (PackageManager.NameNotFoundException e) {
                    name = pkg + " (inte installerad)";
                }
            }
            mAppNames[i].setText(name);
            mAppIcons[i].setImageDrawable(icon);

            boolean custom = !Mapping.isDefault(this, BUTTONS[i]) && icon != null;
            mOverlays[i].setImageDrawable(custom ? round(icon, dp(96)) : null);
            mOverlays[i].setVisibility(custom ? View.VISIBLE : View.GONE);
        }
        placeOverlays();
    }

    // ---------------------------------------------------------------- app picker

    private void pick(int index, String title) {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(28), dp(24), dp(28), dp(20));
        box.setBackground(rounded(0xFF12161D, dp(24), LINE, dp(1)));
        box.addView(text(title, 22, mBold, TEXT));
        TextView sub = text("Välj appen som knappen ska öppna", 14, mRegular, MUTED);
        sub.setPadding(0, dp(6), 0, dp(14));
        box.addView(sub);

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(list);
        box.addView(scroll, new LinearLayout.LayoutParams(dp(520), dp(420)));

        String current = Mapping.get(this, BUTTONS[index]);
        View focus = null;

        View keep = row(null, index == 0 ? "YouTube (som förut)" : "Netflix (som förut)", current.isEmpty());
        keep.setOnClickListener(v -> {
            Mapping.set(this, BUTTONS[index], Mapping.KEEP);
            dialog.dismiss();
            refresh();
        });
        list.addView(keep);
        if (current.isEmpty()) {
            focus = keep;
        }

        for (App app : apps()) {
            boolean selected = app.pkg.equals(current);
            View r = row(app.icon, app.label, selected);
            r.setOnClickListener(v -> {
                Mapping.set(this, BUTTONS[index], app.pkg);
                dialog.dismiss();
                refresh();
            });
            list.addView(r);
            if (selected) {
                focus = r;
            }
        }

        dialog.setContentView(box);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setDimAmount(0.7f);
        }
        dialog.show();
        final View first = focus != null ? focus : keep;
        first.post(first::requestFocus);
    }

    private View row(Drawable icon, String label, boolean selected) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(10), dp(16), dp(10));
        row.setFocusable(true);
        row.setClickable(true);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, rounded(CARD_FOCUS, dp(14), 0x55FFFFFF, dp(1)));
        bg.addState(new int[]{}, rounded(0x00000000, dp(14), 0, 0));
        row.setBackground(bg);

        ImageView iv = new ImageView(this);
        if (icon != null) {
            iv.setImageDrawable(round(icon, dp(40)));
        } else {
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(LINE);
            iv.setBackground(g);
        }
        row.addView(iv, new LinearLayout.LayoutParams(dp(40), dp(40)));

        TextView name = text(label, 16, selected ? mBold : mMedium, TEXT);
        name.setPadding(dp(16), 0, dp(8), 0);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (selected) {
            row.addView(text("✓", 18, mBold, GREEN));
        }
        return row;
    }

    private static final class App {
        final String pkg, label;
        final Drawable icon;

        App(String pkg, String label, Drawable icon) {
            this.pkg = pkg;
            this.label = label;
            this.icon = icon;
        }
    }

    /** The apps that can be started (TV launcher and phone-style launcher entries), by name. */
    private List<App> apps() {
        PackageManager pm = getPackageManager();
        List<App> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        seen.add(getPackageName());
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo info : pm.queryIntentActivities(intent, 0)) {
                String pkg = info.activityInfo.packageName;
                if (seen.add(pkg)) {
                    result.add(new App(pkg, String.valueOf(info.loadLabel(pm)), info.loadIcon(pm)));
                }
            }
        }
        Collections.sort(result, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return result;
    }

    // ---------------------------------------------------------------- helpers

    private void openAccessibility() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (RuntimeException e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private boolean isServiceOn() {
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) {
            return false;
        }
        ComponentName me = new ComponentName(this, KeyService.class);
        for (String s : enabled.split(":")) {
            if (s.equalsIgnoreCase(me.flattenToString()) || s.equalsIgnoreCase(me.flattenToShortString())) {
                return true;
            }
        }
        return false;
    }

    private Typeface font(String name) {
        try {
            return Typeface.createFromAsset(getAssets(), "fonts/" + name);
        } catch (RuntimeException e) {
            return Typeface.DEFAULT;
        }
    }

    private TextView text(String s, float sp, Typeface face, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(face);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    private TextView section(String s) {
        TextView t = text(s, 12, mMedium, MUTED);
        t.setLetterSpacing(0.14f);
        t.setPadding(dp(4), dp(30), 0, dp(12));
        return t;
    }

    private TextView pill(String s, boolean primary) {
        TextView t = text(s, 15, mMedium, TEXT);
        t.setPadding(dp(22), dp(12), dp(22), dp(12));
        t.setFocusable(true);
        t.setClickable(true);
        t.setBackground(pillBackground(primary));
        return t;
    }

    private Drawable pillBackground(boolean primary) {
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, rounded(primary ? 0xFFFF3B30 : 0xFF2A3240, dp(24), 0xFFFFFFFF, dp(2)));
        bg.addState(new int[]{}, rounded(primary ? RED : CARD, dp(24), primary ? 0 : LINE, dp(1)));
        return bg;
    }

    private Drawable cardBackground(int accent) {
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, rounded(CARD_FOCUS, dp(20), accent, dp(2)));
        bg.addState(new int[]{}, rounded(CARD, dp(20), LINE, dp(1)));
        return bg;
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = dp(12);
        return p;
    }

    private static GradientDrawable rounded(int color, int radius, int strokeColor, int strokeWidth) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        if (strokeWidth > 0) {
            g.setStroke(strokeWidth, strokeColor);
        }
        return g;
    }

    /** An app icon cut to a circle (the remote's buttons are round). */
    private Drawable round(Drawable icon, int size) {
        Bitmap src = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(src);
        icon.setBounds(0, 0, size, size);
        icon.draw(c);
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        new Canvas(out).drawCircle(size / 2f, size / 2f, size / 2f, paint);
        src.recycle();
        return new BitmapDrawable(getResources(), out);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
