package com.dialersip;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

/**
 * SIP account management: create, edit, and delete the profile.
 * Built with the app's bundled Material 3 components under Theme.DialerSip
 * (Google-blue palette, DayNight) so it matches the dialer's own settings.
 * Views are constructed programmatically - no layout resource IDs needed -
 * referencing bundled classes and styles by name at runtime.
 */
public final class SipAccountsActivity extends Activity {

    private TextInputEditText server;
    private TextInputEditText username;
    private TextInputEditText authUser;
    private TextInputEditText password;
    private TextInputEditText port;
    private MaterialSwitch receive;
    private MaterialButton udpBtn;
    private MaterialButton tcpBtn;
    private boolean tcpSelected;
    private MaterialButton saveButton;
    private MaterialButton deleteButton;
    private TextView statusTitle;
    private TextView statusDetail;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        // edge-to-edge is enforced at this targetSdk: pad the root for system bars
        scroll.setFitsSystemWindows(true);
        LinearLayout root = vertical();
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        // top app bar built from framework views only: MaterialToolbar's
        // setTitle/setNavigationIcon live in the R8-renamed support Toolbar super
        // and cannot be invoked from spliced code
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(6), dp(10), dp(16), dp(6));

        android.widget.ImageButton back = new android.widget.ImageButton(this);
        int backRes = res("sip_ic_back", "drawable");
        if (backRes != 0) {
            android.graphics.drawable.Drawable d =
                    getDrawable(backRes);
            if (d != null) {
                d = d.mutate();
                d.setTint(attrColor("colorOnSurface"));
                back.setImageDrawable(d);
            }
        }
        int ripple = attrResId("selectableItemBackgroundBorderless");
        if (ripple != 0) back.setBackgroundResource(ripple);
        back.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(dp(48), dp(48));
        bl.gravity = Gravity.CENTER_VERTICAL;
        bar.addView(back, bl);

        LinearLayout titles = vertical();
        TextView barTitle = new TextView(this);
        barTitle.setText("SIP account");
        barTitle.setTextSize(20);
        barTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        barTitle.setTextColor(attrColor("colorOnSurface"));
        titles.addView(barTitle, matchWrap());
        TextView barSub = new TextView(this);
        barSub.setText("Internet calling");
        barSub.setTextSize(13);
        barSub.setTextColor(attrColor("colorOnSurfaceVariant"));
        titles.addView(barSub, matchWrap());
        bar.addView(titles, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(bar, matchWrap());

        LinearLayout body = vertical();
        int pad = dp(20);
        body.setPadding(pad, dp(4), pad, dp(24));
        root.addView(body, matchWrap());

        // ---- status card (M3 filled-card look: rounded, surfaceContainerHighest) ----
        LinearLayout card = vertical();
        android.graphics.drawable.GradientDrawable cardBg = new android.graphics.drawable.GradientDrawable();
        cardBg.setColor(attrColor("colorSurfaceContainerHighest"));
        cardBg.setCornerRadius(dp(16));
        card.setBackground(cardBg);
        card.setPadding(dp(18), dp(14), dp(18), dp(14));

        statusTitle = new TextView(this);
        statusTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        statusTitle.setTextSize(15);
        statusTitle.setTextColor(attrColor("colorOnSurface"));
        card.addView(statusTitle, matchWrap());

        statusDetail = new TextView(this);
        statusDetail.setTextSize(13);
        statusDetail.setTextColor(attrColor("colorOnSurfaceVariant"));
        LinearLayout.LayoutParams dl = matchWrap();
        dl.topMargin = dp(2);
        card.addView(statusDetail, dl);

        LinearLayout.LayoutParams cl = matchWrap();
        cl.topMargin = dp(16);
        cl.bottomMargin = dp(4);
        body.addView(card, cl);

        // ---- account fields ----
        body.addView(sectionLabel("Account details"));

        server = editText(body,
                input(),
                "Server address",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        server.setText(SipAccountStore.server(this));

        username = editText(body,
                input(),
                "Username",
                InputType.TYPE_CLASS_TEXT);
        username.setText(SipAccountStore.username(this));

        authUser = editText(body,
                input(),
                "Auth username (optional)",
                InputType.TYPE_CLASS_TEXT);
        authUser.setText(SipAccountStore.authUser(this));

        password = editText(body,
                input(),
                "Password",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setText(SipAccountStore.password(this));

        port = editText(body,
                input(),
                "Port",
                InputType.TYPE_CLASS_NUMBER);
        port.setText(String.valueOf(SipAccountStore.port(this)));

        // ---- transport (segmented buttons, painted manually: the toggle group's
        //      setSingleSelection/selection APIs were renamed away by R8) ----
        body.addView(sectionLabel("Transport"));
        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        android.graphics.drawable.GradientDrawable segBg =
                new android.graphics.drawable.GradientDrawable();
        segBg.setStroke(dp(1), attrColor("colorOutline"));
        segBg.setCornerRadius(dp(20));
        seg.setBackground(segBg);
        seg.setPadding(dp(3), dp(3), dp(3), dp(3));
        tcpSelected = SipAccountStore.transport(this) == 1;
        udpBtn = segButton("UDP");
        tcpBtn = segButton("TCP");
        udpBtn.setOnClickListener(v -> { tcpSelected = false; paintSeg(); });
        tcpBtn.setOnClickListener(v -> { tcpSelected = true; paintSeg(); });
        seg.addView(udpBtn, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        seg.addView(tcpBtn, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        paintSeg();
        LinearLayout.LayoutParams gl = matchWrap();
        gl.topMargin = dp(4);
        body.addView(seg, gl);

        // ---- incoming calls ----
        receive = new MaterialSwitch(this);
        receive.setText("Receive incoming calls");
        receive.setChecked(SipAccountStore.receiveCalls(this));
        LinearLayout.LayoutParams rl = matchWrap();
        rl.topMargin = dp(20);
        body.addView(receive, rl);

        TextView cap = caption("Keep the SIP registration alive so incoming calls ring on this phone.");
        body.addView(cap);

        // ---- actions ----
        saveButton = new MaterialButton(this);
        saveButton.setMinHeight(dp(48));
        saveButton.setOnClickListener(v -> save());
        LinearLayout.LayoutParams sl = matchWrap();
        sl.topMargin = dp(28);
        body.addView(saveButton, sl);

        deleteButton = styledButton("ThemeOverlay.DialerSip.OutlinedButton");
        deleteButton.setText("Delete account");
        deleteButton.setOnClickListener(v -> confirmDelete());
        LinearLayout.LayoutParams dsl = matchWrap();
        dsl.topMargin = dp(8);
        body.addView(deleteButton, dsl);

        MaterialButton stop = styledButton("ThemeOverlay.DialerSip.TextButton");
        stop.setText("Stop SIP service (keep account)");
        stop.setOnClickListener(v -> {
            SipRegistrationService.stop(this);
            Toast.makeText(this, "SIP service stopped", Toast.LENGTH_SHORT).show();
            refreshMode();
        });
        LinearLayout.LayoutParams stl = matchWrap();
        stl.topMargin = dp(4);
        body.addView(stop, stl);

        refreshMode();
    }

    /** Switch labels/header between "no account yet" and "editing existing account". */
    private void refreshMode() {
        boolean configured = SipAccountStore.configured(this);
        saveButton.setText(configured ? "Save changes" : "Add account");
        deleteButton.setEnabled(configured);
        deleteButton.setAlpha(configured ? 1f : 0.4f);
        if (configured) {
            statusTitle.setText(SipAccountStore.username(this) + "@" + SipAccountStore.server(this));
            boolean running = false;
            try {
                running = PjManager.get(this).isStarted();
            } catch (Throwable ignored) {
            }
            statusDetail.setText((SipAccountStore.transport(this) == 1 ? "TCP" : "UDP")
                    + ", port " + SipAccountStore.port(this)
                    + "  \u2022  " + (running ? "Service running" : "Service stopped"));
        } else {
            statusTitle.setText("No SIP account");
            statusDetail.setText("Enter your SIP details below to add an account.");
        }
    }

    private void save() {
        String srv = server.getText().toString().trim();
        String usr = username.getText().toString().trim();
        if (srv.isEmpty() || usr.isEmpty()) {
            Toast.makeText(this, "Server and username are required", Toast.LENGTH_SHORT).show();
            return;
        }
        int p;
        try {
            p = Integer.parseInt(port.getText().toString().trim());
        } catch (NumberFormatException e) {
            p = 5060;
        }
        SipAccountStore.save(this, srv, usr, authUser.getText().toString(),
                password.getText().toString(), p,
                tcpSelected ? 1 : 0,
                receive.isChecked());
        SipConnectionService.ensurePhoneAccount(this);
        if (SipAccountStore.receiveCalls(this)) {
            SipRegistrationService.start(this);
            Toast.makeText(this, "Saved — SIP service starting", Toast.LENGTH_SHORT).show();
        } else {
            SipRegistrationService.stop(this);
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
        }
        refreshMode();
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setTitle("Delete SIP account?")
                .setMessage("This removes the saved profile, stops the registration "
                        + "and removes the SIP entry from Calling accounts.")
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        deleteAccount();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteAccount() {
        SipRegistrationService.stop(this);          // stops FGS + PjManager shutdown
        PjManager.get(this).deleteAccount();        // unregister + drop account object
        SipConnectionService.removePhoneAccount(this);
        SipAccountStore.clear(this);
        server.setText("");
        username.setText("");
        authUser.setText("");
        password.setText("");
        port.setText("5060");
        receive.setChecked(false);
        tcpSelected = false;
        paintSeg();
        refreshMode();
        Toast.makeText(this, "SIP account deleted", Toast.LENGTH_SHORT).show();
    }

    // ---- tiny view helpers (Material 3) ----

    private LinearLayout vertical() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private TextView sectionLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setTextColor(attrColor("colorPrimary"));
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(18);
        lp.bottomMargin = dp(6);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView caption(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12.5f);
        t.setTextColor(attrColor("colorOnSurfaceVariant"));
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(2);
        lp.leftMargin = dp(4);
        t.setLayoutParams(lp);
        return t;
    }

    /** A filled-box text field; the floating label is set on the EditText because
     *  TextInputLayout.setHint was renamed away by R8 - the layout adopts the
     *  child's hint on attach. Returns the inner edit text. */
    private TextInputEditText editText(LinearLayout root, TextInputLayout layout,
                                       String hint, int inputType) {
        root.addView(layout, matchWrap());
        TextInputEditText e = new TextInputEditText(layout.getContext());
        e.setSingleLine(true);
        e.setInputType(inputType);
        e.setHint(hint);
        layout.addView(e, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return e;
    }

    private TextInputLayout input() {
        TextInputLayout l = new TextInputLayout(this);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(10);
        l.setLayoutParams(lp);
        return l;
    }

    private MaterialButton segButton(String text) {
        MaterialButton b = new MaterialButton(this);
        b.setText(text);
        b.setMinHeight(dp(40));
        b.setElevation(0);
        return b;
    }

    /** Paints the UDP/TCP segment: filled pill on the selected side. */
    private void paintSeg() {
        paintSegSide(udpBtn, !tcpSelected);
        paintSegSide(tcpBtn, tcpSelected);
    }

    private void paintSegSide(MaterialButton b, boolean selected) {
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp(18));
        if (selected) {
            bg.setColor(attrColor("colorPrimary"));
            b.setTextColor(attrColor("colorOnPrimary"));
        } else {
            bg.setColor(0x00000000);
            b.setTextColor(attrColor("colorPrimary"));
        }
        b.setBackground(bg);
    }

    private MaterialButton styledButton(String overlayStyle) {
        int id = res(overlayStyle, "style");
        Context ctx = id != 0 ? new ContextThemeWrapper(this, id) : this;
        return new MaterialButton(ctx);
    }

    private int res(String name, String type) {
        int id = getResources().getIdentifier(name, type, getPackageName());
        if (id == 0) {
            // standalone build: resources stay keyed to the original package
            id = getResources().getIdentifier(name, type, "com.google.android.dialer");
        }
        return id;
    }

    /** Resolves a theme attribute (e.g. selectableItemBackgroundBorderless) to a res id. */
    private int attrResId(String attrName) {
        int attr = res(attrName, "attr");
        TypedValue v = new TypedValue();
        if (attr != 0 && getTheme().resolveAttribute(attr, v, true) && v.resourceId != 0) {
            return v.resourceId;
        }
        return 0;
    }

    private int attrColor(String attrName) {
        int attr = res(attrName, "attr");
        TypedValue v = new TypedValue();
        if (attr != 0 && getTheme().resolveAttribute(attr, v, true) && v.type >= TypedValue.TYPE_FIRST_COLOR_INT) {
            return v.data;
        }
        return 0xFF444444;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
