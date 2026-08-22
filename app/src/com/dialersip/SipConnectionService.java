package com.dialersip;

import android.content.ComponentName;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.telecom.Connection;
import android.telecom.ConnectionRequest;
import android.telecom.ConnectionService;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.util.Log;

/**
 * Managed ConnectionService: SIP calls appear in the default dialer's in-call UI
 * just like SIM calls.
 */
public final class SipConnectionService extends ConnectionService {

    private static final String TAG = "DialerSip";
    public static final String ACCOUNT_ID = "dialer_sip_account";

    @Override
    public void onCreate() {
        super.onCreate();
        // Warm the stack whenever Telecom binds us — covers processes that
        // restarted without the registration service (e.g. after a crash).
        PjManager.get(this).start();
    }

    public static PhoneAccountHandle handle(Context c) {
        ComponentName cn = new ComponentName(c, SipConnectionService.class);
        return new PhoneAccountHandle(cn, ACCOUNT_ID);
    }

    /** Registers (or refreshes) the SIP PhoneAccount with Telecom. */
    public static void ensurePhoneAccount(Context c) {
        TelecomManager tm = (TelecomManager) c.getSystemService(Context.TELECOM_SERVICE);
        if (tm == null) return;
        PhoneAccountHandle h = handle(c);
        PhoneAccount pa = new PhoneAccount.Builder(h, "SIP")
                .setAddress(Uri.parse(SipAccountStore.configured(c)
                        ? SipAccountStore.sipUri(c) : "sip:sip@localhost"))
                .setSubscriptionAddress(Uri.parse(SipAccountStore.configured(c)
                        ? SipAccountStore.sipUri(c) : "sip:sip@localhost"))
                .setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER)
                .setSupportedUriSchemes(java.util.Arrays.asList("sip", "tel"))
                .setShortDescription("SIP calling via " + SipAccountStore.server(c))
                .build();
        tm.registerPhoneAccount(pa);
        try {
            // Hidden API; we're a system (priv-app) package so hidden-API
            // enforcement does not apply. Requires MODIFY_PHONE_STATE (granted).
            java.lang.reflect.Method m = TelecomManager.class.getMethod(
                    "enablePhoneAccount", PhoneAccountHandle.class, boolean.class);
            m.invoke(tm, h, true);
        } catch (Exception e) {
            Log.w(TAG, "enablePhoneAccount: " + e.getMessage());
        }
    }

    /** Removes the SIP PhoneAccount from Telecom (profile deleted). */
    public static void removePhoneAccount(Context c) {
        TelecomManager tm = (TelecomManager) c.getSystemService(Context.TELECOM_SERVICE);
        if (tm == null) return;
        try {
            tm.unregisterPhoneAccount(handle(c));
        } catch (Exception e) {
            Log.w(TAG, "unregisterPhoneAccount: " + e.getMessage());
        }
    }

    @Override
    public Connection onCreateOutgoingConnection(
            PhoneAccountHandle connectionManagerPhoneAccount, ConnectionRequest request) {
        Uri address = request.getAddress();
        if (address == null) return Connection.createFailedConnection(
                new android.telecom.DisconnectCause(android.telecom.DisconnectCause.ERROR));
        SipConnection conn = new SipConnection(handle(getApplicationContext()),
                getApplicationContext(), false);
        String scheme = address.getScheme() == null ? "tel" : address.getScheme();
        String ssp = address.getSchemeSpecificPart();
        String uri;
        if (("sip".equals(scheme) || "sips".equals(scheme)) && ssp.contains("@")) {
            uri = scheme + ":" + ssp;
        } else {
            // tel: number, bare user, or sip user without domain — route via SIP server
            uri = SipAccountStore.dialUri(getApplicationContext(), ssp);
        }
        PjCall call = PjManager.get(getApplicationContext()).makeCall(uri, conn);
        if (call == null) {
            return Connection.createFailedConnection(
                    new android.telecom.DisconnectCause(android.telecom.DisconnectCause.ERROR));
        }
        conn.attach(call);
        conn.setDialing();
        conn.setAddress(address, android.telecom.TelecomManager.PRESENTATION_ALLOWED);
        return conn;
    }

    @Override
    public Connection onCreateIncomingConnection(
            PhoneAccountHandle connectionManagerPhoneAccount, ConnectionRequest request) {
        // Incoming calls are added via TelecomManager.addNewIncomingCall from
        // SipRegistrationService; Telecom then calls back here.
        Bundle extras = request.getExtras();
        SipConnection conn = new SipConnection(handle(getApplicationContext()),
                getApplicationContext(), true);
        PjCall call = SipRegistrationService.takePendingIncomingCall();
        if (call == null) {
            Log.w(TAG, "incoming connection requested but no pending call");
            return Connection.createFailedConnection(
                    new android.telecom.DisconnectCause(android.telecom.DisconnectCause.CANCELED));
        }
        conn.attach(call);
        call.setConnection(conn);
        conn.setAddress(Uri.parse(call.remoteUri()), TelecomManager.PRESENTATION_ALLOWED);
        conn.setRinging();
        return conn;
    }
}
