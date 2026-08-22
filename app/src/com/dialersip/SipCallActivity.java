package com.dialersip;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

/** Mini dialer: enter a number or SIP address, place the call via Telecom over SIP. */
public final class SipCallActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        EditText number = new EditText(this);
        number.setHint("Number or user@host");
        number.setSingleLine(true);
        number.setTextSize(18);
        root.addView(number, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button call = new Button(this);
        call.setText("Call over SIP");
        call.setOnClickListener(v -> place(number.getText().toString()));
        root.addView(call);

        setContentView(root);
    }

    private void place(String dest) {
        String d = dest.trim();
        if (d.isEmpty()) return;
        if (!SipAccountStore.configured(this)) {
            Toast.makeText(this, "Configure the SIP account first", Toast.LENGTH_LONG).show();
            return;
        }
        // Make sure the stack is up so the call can be made even when
        // the registration service is not running.
        if (!SipAccountStore.receiveCalls(this)) {
            PjManager.get(this).start();
        }
        PhoneAccountHandle h = SipConnectionService.handle(this);
        TelecomManager tm = (TelecomManager) getSystemService(Context.TELECOM_SERVICE);
        android.net.Uri uri = android.net.Uri.parse(SipAccountStore.dialUri(this, d));
        android.os.Bundle extras = new android.os.Bundle();
        extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, h);
        try {
            tm.placeCall(uri, extras);
            finish();
        } catch (Exception e) {
            Toast.makeText(this, "Call failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
