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
        android.net.Uri caller = callerAddress(call.remoteUri());
        Log.i(TAG, "incoming caller: raw=" + call.remoteUri() + " -> " + caller);
        conn.setAddress(caller, TelecomManager.PRESENTATION_ALLOWED);
        String name = callerDisplayName(call.remoteUri());
        if (name != null) conn.setCallerDisplayName(name, TelecomManager.PRESENTATION_ALLOWED);
        conn.setRinging();
        return conn;
    }

    /**
     * pjsua2's CallInfo.getRemoteUri() returns the raw From header value, e.g.
     *   "JoY" &lt;sip:JoY@host&gt;;tag=abc   or   &lt;sip:1000000000@host&gt;;tag=abc
     * Uri.parse() on that string yields a scheme-less invalid Uri, which Telecom
     * renders as "Unknown". Extract the sip: URI, split user@host, and hand
     * Telecom a clean tel: URI for phone-number callers (matches contacts and
     * the call log) or sip: URI for SIP usernames.
     */
    static android.net.Uri callerAddress(String remoteUri) {
        String user;
        String host = "";
        String s = remoteUri == null ? "" : remoteUri;
        int i = indexOfScheme(s);
        if (i >= 0) {
            String uri = s.substring(i);
            for (int k = 0; k < uri.length(); k++) {
                char c = uri.charAt(k);
                if (c == ';' || c == '>' || c == ' ' || c == '"' || c == '<') {
                    uri = uri.substring(0, k);
                    break;
                }
            }
            int colon = uri.indexOf(':');
            int at = uri.indexOf('@');
            user = at > 0 ? uri.substring(colon + 1, at) : uri.substring(colon + 1);
            if (at > 0) host = uri.substring(at + 1);
        } else {
            user = s.trim();
        }
        int semi = user.indexOf(';');
        if (semi >= 0) user = user.substring(0, semi);
        user = Uri.decode(user.trim());
        if (user.isEmpty()) return Uri.parse("sip:unknown@unknown");

        // phone-number-like caller (digits, +, *, #, separators) -> tel: URI
        if (user.matches("[+]?[0-9*# \\-()]+")) {
            return Uri.parse("tel:" + Uri.encode(user));
        }
        return Uri.parse("sip:" + Uri.encode(user) + "@" + (host.isEmpty() ? "unknown" : host));
    }

    /** Display name from the raw remote URI, e.g. "JoY" from "JoY" &lt;sip:...&gt;. */
    static String callerDisplayName(String remoteUri) {
        String s = remoteUri == null ? "" : remoteUri;
        int lt = s.indexOf('<');
        if (lt > 0) {
            String name = s.substring(0, lt).replace("\"", "").trim();
            if (!name.isEmpty()) return name;
        }
        return null;
    }

    private static int indexOfScheme(String s) {
        int a = s.indexOf("sip:");
        int b = s.indexOf("sips:");
        int c = s.indexOf("tel:");
        int best = -1;
        if (a >= 0) best = a;
        if (b >= 0 && (best < 0 || b < best)) best = b;
        if (c >= 0 && (best < 0 || c < best)) best = c;
        return best;
    }
}
