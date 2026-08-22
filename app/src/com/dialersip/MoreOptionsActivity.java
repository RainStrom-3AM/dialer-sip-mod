package com.dialersip;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.material.materialswitch.MaterialSwitch;

/**
 * "More options" screen. Layout metrics follow the dialer's settings rows
 * (64dp rows, 24dp icons inset 16dp, 16sp titles) so it reads as native.
 * v1: one toggle — "Record all calls".
 */
public final class MoreOptionsActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppContext.set(this);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        scroll.addView(root);

        root.addView(sectionHeader("CALL RECORDINGS"));
        root.addView(recordRow());
        root.addView(hint());

        setContentView(scroll);
    }

    // ---- rows ----

    private View recordRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(64));
        row.setPadding(dp(16), 0, dp(16), 0);
        row.setBackground(getSelectableBackground());

        ImageView icon = new ImageView(this);
        icon.setImageResource(android.R.drawable.ic_menu_more);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        row.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));

        TextView title = new TextView(this);
        title.setText("Record all calls");
        title.setTextSize(16);
        title.setTextColor(Color.parseColor("#DE000000"));
        title.setPadding(dp(16), 0, 0, 0);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(title, tp);

        MaterialSwitch sw = new MaterialSwitch(this);
        sw.setChecked(CallRecording.enabled(this));
        sw.setOnCheckedChangeListener((b, checked) -> CallRecording.setEnabled(this, checked));
        row.addView(sw, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        return row;
    }

    private TextView sectionHeader(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(14);
        t.setAllCaps(true);
        t.setLetterSpacing(0.05f);
        t.setTextColor(Color.parseColor("#8C000000"));
        t.setPadding(dp(16), dp(24), dp(16), dp(8));
        return t;
    }

    private TextView hint() {
        TextView t = new TextView(this);
        t.setText("SIP and SIM calls are saved to Recordings / Call recordings.\n"
                + "Call recording laws vary — some places require all parties' consent.");
        t.setTextSize(12);
        t.setTextColor(Color.parseColor("#99000000"));
        t.setPadding(dp(16), dp(12), dp(16), dp(24));
        return t;
    }

    // ---- helpers ----

    private android.graphics.drawable.Drawable getSelectableBackground() {
        return obtainStyledAttributes(new int[]{android.R.attr.selectableItemBackground})
                .getDrawable(0);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
