package com.dialersip;

import android.content.Context;
import android.content.SharedPreferences;

/** Persistent storage for the (single) SIP account and preferences. */
public final class SipAccountStore {

    private static final String PREFS = "dialer_sip_prefs";

    private static final String K_SERVER = "server";
    private static final String K_USERNAME = "username";
    private static final String K_AUTH_USER = "auth_user";
    private static final String K_PASSWORD = "password";
    private static final String K_PORT = "port";
    private static final String K_TRANSPORT = "transport"; // 0=UDP 1=TCP
    private static final String K_RECEIVE = "receive_calls";
    private static final String K_REG_DONE = "reg_ever_succeeded";

    private SipAccountStore() {}

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String server(Context c) { return prefs(c).getString(K_SERVER, ""); }
    public static String username(Context c) { return prefs(c).getString(K_USERNAME, ""); }
    public static String authUser(Context c) { return prefs(c).getString(K_AUTH_USER, ""); }
    public static String password(Context c) { return prefs(c).getString(K_PASSWORD, ""); }

    public static int port(Context c) {
        int p = prefs(c).getInt(K_PORT, 5060);
        return (p >= 1 && p <= 65535) ? p : 5060;
    }

    /** 0 = UDP, 1 = TCP. */
    public static int transport(Context c) { return prefs(c).getInt(K_TRANSPORT, 0); }

    public static boolean receiveCalls(Context c) { return prefs(c).getBoolean(K_RECEIVE, false); }

    public static boolean configured(Context c) {
        return server(c).length() > 0 && username(c).length() > 0;
    }

    public static void save(Context c, String server, String username, String authUser,
                            String password, int port, int transport, boolean receive) {
        prefs(c).edit()
                .putString(K_SERVER, server.trim())
                .putString(K_USERNAME, username.trim())
                .putString(K_AUTH_USER, authUser.trim())
                .putString(K_PASSWORD, password)
                .putInt(K_PORT, port)
                .putInt(K_TRANSPORT, transport)
                .putBoolean(K_RECEIVE, receive)
                .apply();
    }

    /** Erases the stored profile entirely. */
    public static void clear(Context c) {
        prefs(c).edit().clear().apply();
    }

    /** SIP identity: sip:username@server */
    public static String sipUri(Context c) {
        return "sip:" + username(c) + "@" + server(c);
    }

    /** Registrar URI with transport hint for the given transport id. */
    public static String registrarUri(Context c) {
        String t = transport(c) == 1 ? ";transport=tcp" : ";transport=udp";
        return "sip:" + server(c) + t;
    }

    public static String outboundProxyUri(Context c) {
        String t = transport(c) == 1 ? ";transport=tcp" : ";transport=udp";
        return "sip:" + server(c) + ":" + port(c) + t;
    }

    /** Builds a sip: URI for an entered destination over the configured transport. */
    public static String dialUri(Context c, String dest) {
        String d = dest.trim();
        if (d.startsWith("sip:") || d.startsWith("sips:")) return d;
        String t = transport(c) == 1 ? ";transport=tcp" : ";transport=udp";
        if (d.contains("@")) return "sip:" + d + t;
        return "sip:" + d + "@" + server(c) + t;
    }
}
