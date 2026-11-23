package com.buildsession.aospstackingrecent;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

/**
 * 模块的设置界面，用于调整多任务卡片间距和堆叠紧密度。
 */
public class MainActivity extends AppCompatActivity {
    private static final String PREFS_NAME = "com.buildsession.aospstackingrecent_preferences";
    private static final String KEY_SPACING = "card_spacing_factor";
    private static final String KEY_TIGHTNESS = "card_tightness_factor";
    private static final float DEFAULT_SPACING = 4.7f;
    private static final float DEFAULT_TIGHTNESS = 0.72f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Use ScrollView as root to prevent content being cut off
        ScrollView scrollView = new ScrollView(this);
        scrollView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = dpToPx(24);
        layout.setPadding(padding, padding, padding, padding);
        
        // Add some top margin to avoid overlap with ActionBar/StatusBar if any
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        layout.setLayoutParams(layoutParams);
        
        scrollView.addView(layout);

        TextView titleView = new TextView(this);
        titleView.setText("AOSPStackingRecent");
        titleView.setTextSize(28);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        titleView.setPadding(0, dpToPx(16), 0, dpToPx(8));
        titleView.setGravity(android.view.Gravity.CENTER);
        layout.addView(titleView);

        TextView authorView = new TextView(this);
        authorView.setText("Module by BuildSession");
        authorView.setTextSize(16);
        authorView.setAlpha(0.7f);
        authorView.setPadding(0, 0, 0, dpToPx(32));
        authorView.setGravity(android.view.Gravity.CENTER);
        layout.addView(authorView);

        View divider = new View(this);
        divider.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(1)));
        divider.setBackgroundColor(0xFFCCCCCC);
        layout.addView(divider);

        final SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        
        // Ensure keys exist so file is created
        if (!prefs.contains(KEY_SPACING)) {
            prefs.edit().putFloat(KEY_SPACING, DEFAULT_SPACING).commit();
        }
        if (!prefs.contains(KEY_TIGHTNESS)) {
            prefs.edit().putFloat(KEY_TIGHTNESS, DEFAULT_TIGHTNESS).commit();
        }
        fixPermissions();

        addBlurSettings(layout, prefs);

        TextView statusView = new TextView(this);
        statusView.setText("前往LSP管理器激活模块即可使用");
        statusView.setGravity(android.view.Gravity.CENTER);
        statusView.setPadding(0, dpToPx(48), 0, dpToPx(24));
        layout.addView(statusView);

        TextView githubView = new TextView(this);
        githubView.setText("查看项目 GitHub 页面");
        githubView.setTextColor(0xFF007AFF); // iOS style blue
        githubView.setGravity(android.view.Gravity.CENTER);
        githubView.setPadding(0, dpToPx(16), 0, dpToPx(16));
        githubView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW);
                intent.setData(android.net.Uri.parse("https://github.com/C70246247/AOSPStackingRecent"));
                startActivity(intent);
            }
        });
        layout.addView(githubView);

        setContentView(scrollView);
    }

    /** Settings for the blurred wallpaper behind Recents (see RecentsBlurHook). */
    private void addBlurSettings(LinearLayout layout, final SharedPreferences prefs) {
        TextView header = new TextView(this);
        header.setText("Blurred wallpaper background");
        header.setTextSize(18);
        header.setTypeface(null, android.graphics.Typeface.BOLD);
        header.setPadding(0, dpToPx(24), 0, dpToPx(8));
        layout.addView(header);

        android.widget.Switch toggle = new android.widget.Switch(this);
        toggle.setText("Blur wallpaper behind Recents");
        toggle.setChecked(prefs.getBoolean(RecentsBlurHook.KEY_BLUR_ENABLED, RecentsBlurHook.DEFAULT_BLUR_ENABLED));
        toggle.setPadding(0, dpToPx(8), 0, dpToPx(8));
        toggle.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                prefs.edit().putBoolean(RecentsBlurHook.KEY_BLUR_ENABLED, checked).commit();
                fixPermissions();
            }
        });
        layout.addView(toggle);

        addPercentSlider(layout, prefs, "Blur strength", RecentsBlurHook.KEY_BLUR_STRENGTH,
                RecentsBlurHook.DEFAULT_BLUR_STRENGTH, 100);
        addPercentSlider(layout, prefs, "Darkening", RecentsBlurHook.KEY_BLUR_DIM,
                RecentsBlurHook.DEFAULT_BLUR_DIM, 90);
    }

    private void addPercentSlider(LinearLayout layout, final SharedPreferences prefs, final String title,
                                  final String key, float defaultValue, int maxPercent) {
        final TextView label = new TextView(this);
        label.setPadding(0, dpToPx(12), 0, 0);
        layout.addView(label);

        int percent = Math.round(prefs.getFloat(key, defaultValue) * 100f);
        label.setText(title + ": " + percent + "%");

        SeekBar bar = new SeekBar(this);
        bar.setMax(maxPercent);
        bar.setProgress(Math.min(percent, maxPercent));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                label.setText(title + ": " + progress + "%");
                if (fromUser) {
                    prefs.edit().putFloat(key, progress / 100f).commit();
                    fixPermissions();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        layout.addView(bar);
    }

    private void fixPermissions() {
        try {
            java.io.File sharedPrefsDir = new java.io.File(getApplicationInfo().dataDir, "shared_prefs");
            if (sharedPrefsDir.exists()) {
                sharedPrefsDir.setExecutable(true, false);
                sharedPrefsDir.setReadable(true, false);
                java.io.File prefsFile = new java.io.File(sharedPrefsDir, PREFS_NAME + ".xml");
                if (prefsFile.exists()) {
                    prefsFile.setReadable(true, false);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round((float) dp * density);
    }

    interface OnValueChangeListener {
        void onValueChanged(float value);
    }
}
