package com.dialersip;

/** Process-wide application context holder (set by our services/activities). */
public final class AppContext {
    private static volatile android.content.Context ctx;

    private AppContext() {}

    public static void set(android.content.Context c) {
        if (c != null) ctx = c.getApplicationContext();
    }

    public static android.content.Context get() {
        return ctx;
    }
}
