package com.dialersip;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Restarts the SIP registration service after reboot when receiving calls is enabled. */
public final class SipBootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                || "com.htc.intent.action.QUICKBOOT_POWERON".equals(action)) {
            if (SipAccountStore.configured(context) && SipAccountStore.receiveCalls(context)) {
                try {
                    SipRegistrationService.start(context);
                } catch (Exception e) {
                    Log.e("DialerSip", "boot start failed", e);
                }
            }
        }
    }
}
