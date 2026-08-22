package com.dialersip;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * SIP account management: create, edit, and delete the profile.
 * Deliberately built with plain programmatic views: no layout resources,
 * no androidx, so it splices into the decompiled APK without resource-ID surgery.
 */
public final class SipAccountsActivity extends Activity {

    private EditText server;
    private EditText username;
    private EditText authUser;
    private EditText password;
    private EditText port;
    private CheckBox receive;
    private android.widget.RadioGroup transport;
    private Button saveButton;
    private Button deleteButton;
    private TextView statusHeader;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, dp(80));
        scroll.addView(root);

        statusHeader = new TextView(this);
        statusHeader.setTextSize(14);
        statusHeader.setPadding(0, 0, 0, dp(12));
        root.addView(statusHeader, wrap());

        root.addView(title("SIP account"));

        server = field(root, "Server / registrar host", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_URI);
        server.setText(SipAccountStore.server(this));

        username = field(root, "Username (SIP user)", InputType.TYPE_CLASS_TEXT);
        username.setText(SipAccountStore.username(this));

        authUser = field(root, "Auth username (optional, defaults to username)",
                InputType.TYPE_CLASS_TEXT);
        authUser.setText(SipAccountStore.authUser(this));

        password = field(root, "Password", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setText(SipAccountStore.password(this));

        port = field(root, "Port (default 5060)", InputType.TYPE_CLASS_NUMBER);
        port.setText(String.valueOf(SipAccountStore.port(this)));

        root.addView(label("Transport"));
        transport = new android.widget.RadioGroup(this);
        transport.setOrientation(LinearLayout.HORIZONTAL);
        android.widget.RadioButton udp = new android.widget.RadioButton(this);
        udp.setId(View.generateViewId());
        udp.setText("UDP");
        android.widget.RadioButton tcp = new android.widget.RadioButton(this);
        tcp.setId(View.generateViewId());
        tcp.setText("TCP");
        transport.addView(udp);
        transport.addView(tcp);
        if (SipAccountStore.transport(this) == 1) tcp.setChecked(true); else udp.setChecked(true);
        root.addView(transport, wrap());

        receive = new CheckBox(this);
        receive.setText("Receive incoming calls (keeps registration alive)");
        receive.setChecked(SipAccountStore.receiveCalls(this));
        root.addView(receive, wrap());

        saveButton = new Button(this);
        saveButton.setOnClickListener(v -> save());
        root.addView(saveButton, wrap());

        deleteButton = new Button(this);
        deleteButton.setText("Delete account");
        deleteButton.setOnClickListener(v -> confirmDelete());
        root.addView(deleteButton, wrap());

        Button stop = new Button(this);
        stop.setText("Stop SIP service (keep account)");
        stop.setOnClickListener(v -> {
            SipRegistrationService.stop(this);
            Toast.makeText(this, "SIP service stopped", Toast.LENGTH_SHORT).show();
        });
        root.addView(stop, wrap());

        root.addView(hint("Calls are placed from the persistent SIP notification "
                + "or the launcher shortcut \"New SIP call\". The SIP account also "
                + "appears under Settings > Calling accounts for enabling/disabling."));

        refreshMode();
        setContentView(scroll);
    }

    /** Switch labels/header between "no account yet" and "editing existing account". */
    private void refreshMode() {
        boolean configured = SipAccountStore.configured(this);
        saveButton.setText(configured ? "Save changes" : "Add account");
        deleteButton.setEnabled(configured);
        deleteButton.setAlpha(configured ? 1f : 0.4f);
        if (configured) {
            statusHeader.setText("Account: " + SipAccountStore.username(this)
                    + "@" + SipAccountStore.server(this)
                    + "  (" + (SipAccountStore.transport(this) == 1 ? "TCP" : "UDP")
                    + ", port " + SipAccountStore.port(this) + ")");
        } else {
            statusHeader.setText("No SIP account configured — enter details below.");
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
                transport.indexOfChild(transport.findViewById(transport.getCheckedRadioButtonId())) == 1 ? 1 : 0,
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
        refreshMode();
        Toast.makeText(this, "SIP account deleted", Toast.LENGTH_SHORT).show();
    }

    // ---- tiny view helpers ----

    private TextView title(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(20);
        t.setGravity(Gravity.START);
        t.setPadding(0, 0, 0, dp(12));
        return t;
    }

    private TextView label(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setPadding(0, dp(10), 0, dp(4));
        return t;
    }

    private TextView hint(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12);
        t.setPadding(0, dp(16), 0, 0);
        return t;
    }

    private EditText field(LinearLayout root, String hint, int inputType) {
        root.addView(label(hint));
        EditText e = new EditText(this);
        e.setInputType(inputType);
        e.setSingleLine(true);
        root.addView(e, wrap());
        return e;
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
