package com.dialersip;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.IBinder;
import android.telecom.TelecomManager;
import android.util.Log;

/**
 * Foreground service keeping the SIP registration alive (NAT keep-alive,
 * incoming INVITE handling). Runs with phoneCall|microphone FGS type.
 */
public final class SipRegistrationService extends Service {

    private static final String TAG = "DialerSip";
    private static final int NOTIF_ID = 0x5131;
    private static final String CHANNEL_ID = "dialer_sip_service";

    /**
     * Incoming calls waiting to be claimed by
     * SipConnectionService.onCreateIncomingConnection.
     * Written on the pjsip worker thread, read on the main binder thread:
     * MUST be synchronized - without the happens-before edge the reader can
     * observe a stale null when stash and take land milliseconds apart
     * (observed as CREATE_CONNECTION_FAILED -> "Unknown" call log entries).
     */
    private static final java.util.ArrayDeque<PjCall> PENDING = new java.util.ArrayDeque<>();
    private static final android.os.Handler MAIN =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private ConnectivityManager.NetworkCallback netCallback;
    private boolean running;

    /** How long Telecom gets to claim a stashed incoming call before we
     *  answer 486 ourselves; normal claims arrive in milliseconds. */
    private static final long UNCLAIMED_BUSY_MS = 8000;

    static void stashIncomingCall(PjCall call) {
        synchronized (PENDING) {
            PENDING.add(call);
            Log.i(TAG, "stashed incoming call: " + call.remoteUri()
                    + " (queue=" + PENDING.size() + ")");
        }
        // Telecom sometimes aborts incoming-call creation for third-party
        // connection services while another call is active on the device
        // (CREATE_CONNECTION_FAILED without ever calling our service). The SIP
        // leg would then ring unanswered until the server's timeout while the
        // caller keeps hearing ringback. If Telecom has not claimed the call
        // within the timeout, reply 486 Busy ourselves so the caller's leg
        // (e.g. a SIM call routed through the SIP provider) ends promptly.
        MAIN.postDelayed(() -> {
            synchronized (PENDING) {
                if (PENDING.remove(call)) {
                    Log.w(TAG, "incoming call unclaimed after " + UNCLAIMED_BUSY_MS
                            + "ms - replying 486 busy: " + call.remoteUri());
                    call.sipReject();
                }
            }
        }, UNCLAIMED_BUSY_MS);
    }

    static PjCall takePendingIncomingCall() {
        // Fast path first; the queue is normally already populated.
        synchronized (PENDING) {
            PjCall c = PENDING.poll();
            if (c != null) {
                Log.i(TAG, "claimed incoming call: " + c.remoteUri()
                        + " (queue=" + PENDING.size() + ")");
                return c;
            }
        }
        // Rare: stash raced the take (or binder reordering). Poll briefly
        // before giving up - onCreateIncomingConnection runs on the main
        // thread and a short bounded wait is safe here.
        long deadline = android.os.SystemClock.uptimeMillis() + 2000;
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            android.os.SystemClock.sleep(100);
            synchronized (PENDING) {
                PjCall c = PENDING.poll();
                if (c != null) {
                    Log.i(TAG, "claimed incoming call after wait: " + c.remoteUri());
                    return c;
                }
            }
        }
        Log.w(TAG, "no pending incoming call after 2s");
        return null;
    }

    public static void start(Context c) {
        try {
            c.startForegroundService(new Intent(c, SipRegistrationService.class));
        } catch (Exception e) {
            Log.e(TAG, "cannot start registration service", e);
        }
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, SipRegistrationService.class));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startInForeground();
        if (!running) {
            running = true;
            SipConnectionService.ensurePhoneAccount(this);
            PjManager pm = PjManager.get(this);
            pm.setListener(new PjManager.Listener() {
                @Override
                public void onIncomingCall(PjCall call) {
                    handleIncoming(call);
                }

                @Override
                public void onRegistrationState(boolean registered, int code, String reason) {
                    Log.i(TAG, "reg state: " + registered + " code=" + code + " " + reason);
                    updateNotification(registered);
                }
            });
            pm.start();
            watchNetwork();
        } else {
            PjManager.get(this).reRegister();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (netCallback != null) {
            try {
                ConnectivityManager cm = (ConnectivityManager)
                        getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) cm.unregisterNetworkCallback(netCallback);
            } catch (Exception ignored) {
            }
            netCallback = null;
        }
        PjManager pm = PjManager.get(this);
        pm.setListener(null);
        pm.shutdown();
        super.onDestroy();
    }

    private void startInForeground() {
        Notification n = buildNotification(false);
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(NOTIF_ID, n,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                                | ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Exception e) {
            Log.e(TAG, "startForeground failed", e);
            stopSelf();
        }
    }

    private void handleIncoming(PjCall call) {
        stashIncomingCall(call);
        TelecomManager tm = (TelecomManager) getSystemService(Context.TELECOM_SERVICE);
        if (tm == null) return;
        try {
            android.os.Bundle extras = new android.os.Bundle();
            tm.addNewIncomingCall(SipConnectionService.handle(this), extras);
        } catch (Exception e) {
            Log.e(TAG, "addNewIncomingCall failed", e);
            call.sipReject();
        }
    }

    private void watchNetwork() {
        ConnectivityManager cm = (ConnectivityManager)
                getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;
        netCallback = new ConnectivityManager.NetworkCallback() {
            private boolean hadNetwork;

            @Override
            public void onAvailable(Network network) {
                if (hadNetwork) {
                    // Network came back after a drop: re-register to refresh NAT binding.
                    PjManager.get(SipRegistrationService.this).reRegister();
                }
                hadNetwork = true;
            }

            @Override
            public void onLost(Network network) {
                hadNetwork = false;
            }
        };
        try {
            cm.registerDefaultNetworkCallback(netCallback);
        } catch (Exception e) {
            Log.e(TAG, "network callback failed", e);
        }
    }

    private void createChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                "SIP calling", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Keeps the SIP registration alive for incoming calls");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification(boolean registered) {
        Intent settings = new Intent(this, SipAccountsActivity.class);
        PendingIntent piSettings = PendingIntent.getActivity(this, 1, settings,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent dial = new Intent(this, SipCallActivity.class);
        PendingIntent piDial = PendingIntent.getActivity(this, 2, dial,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setContentTitle(registered ? "SIP: registered" : "SIP: registering…")
                .setContentText(SipAccountStore.username(this) + "@" + SipAccountStore.server(this))
                .setSmallIcon(android.R.drawable.stat_sys_phone_call)
                .setOngoing(true)
                .setContentIntent(piSettings)
                .addAction(new Notification.Action.Builder(null, "SIP settings", piSettings).build())
                .addAction(new Notification.Action.Builder(null, "New SIP call", piDial).build());
        return b.build();
    }

    private void updateNotification(boolean registered) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        try {
            nm.notify(NOTIF_ID, buildNotification(registered));
        } catch (Exception ignored) {
        }
    }
}
