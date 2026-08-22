package com.dialersip;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import org.pjsip.pjsua2.Account;
import org.pjsip.pjsua2.AccountConfig;
import org.pjsip.pjsua2.AuthCredInfo;
import org.pjsip.pjsua2.Call;
import org.pjsip.pjsua2.CallInfo;
import org.pjsip.pjsua2.CallOpParam;
import org.pjsip.pjsua2.Endpoint;
import org.pjsip.pjsua2.EpConfig;
import org.pjsip.pjsua2.OnIncomingCallParam;
import org.pjsip.pjsua2.OnRegStateParam;
import org.pjsip.pjsua2.TransportConfig;

/**
 * Owns the pjsua2 Endpoint lifecycle, account registration and call map.
 * All pjsua2 calls are funneled onto a single background thread.
 */
public final class PjManager {

    private static final String TAG = "DialerSip";
    private static final String LIB_NAME = "pjsua2";

    private static volatile PjManager instance;

    private final Context context;
    private final HandlerThread thread;
    private final Handler handler;
    private Endpoint endpoint;
    private PjAccount account;
    private boolean started;
    private Listener listener;

    /** Posts work onto the single pjlib-registered thread. Null-safe. */
    static void post(Runnable r) {
        PjManager m = instance;
        if (m != null) {
            m.handler.post(r);
        }
    }

    /** Posts delayed work onto the pjlib thread (null-safe, for call cleanup). */
    static void postDelayed(Runnable r, long delayMillis) {
        PjManager m = instance;
        if (m != null) {
            m.handler.postDelayed(r, delayMillis);
        }
    }

    /** The pjsua2 Endpoint, or null before startup. */
    static Endpoint endpoint() {
        PjManager m = instance;
        return m != null ? m.endpoint : null;
    }

    /** Callbacks delivered on the pjsip thread; implementations must hop threads themselves. */
    public interface Listener {
        void onIncomingCall(PjCall call);
        void onRegistrationState(boolean registered, int code, String reason);
    }

    /** pjsua2 account with registration + incoming-call callbacks. */
    static final class PjAccount extends Account {
        @Override
        public void onRegState(OnRegStateParam prm) {
            PjManager m = instance;
            if (m == null || m.listener == null) return;
            int code = prm.getCode();
            m.listener.onRegistrationState(
                    code / 200 == 1 && prm.getExpiration() != 0,
                    code, prm.getReason());
        }

        @Override
        public void onIncomingCall(OnIncomingCallParam prm) {
            PjManager m = instance;
            if (m == null || m.listener == null) return;
            PjCall call = new PjCall(this, prm.getCallId());
            call.cacheRemoteUri(); // runs on pjsip's own thread
            m.listener.onIncomingCall(call);
        }
    }

    private PjManager(Context appContext) {
        context = appContext.getApplicationContext();
        thread = new HandlerThread("pjsip-main");
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    public static PjManager get(Context c) {
        PjManager m = instance;
        if (m == null) {
            synchronized (PjManager.class) {
                if (instance == null) instance = new PjManager(c);
                m = instance;
            }
        }
        return m;
    }

    public void setListener(Listener l) { listener = l; }

    public boolean isStarted() { return started; }

    /** Starts the endpoint (idempotent) and (re)registers the account from stored settings. */
    public void start() {
        handler.post(() -> {
            try {
                ensureStarted();
                ensureRegistered();
            } catch (Exception e) {
                Log.e(TAG, "start failed", e);
            }
        });
    }

    /** Routes pjsip's internal logs (incl. full SIP packets) into logcat. */
    private static final class PjLogWriter extends org.pjsip.pjsua2.LogWriter {
        @Override
        public void write(org.pjsip.pjsua2.LogEntry entry) {
            String msg = entry.getMsg();
            switch (entry.getLevel()) {
                case 1: android.util.Log.e("PjsipTrace", msg); break;
                case 2: android.util.Log.w("PjsipTrace", msg); break;
                case 3: android.util.Log.i("PjsipTrace", msg); break;
                default: android.util.Log.d("PjsipTrace", msg); break;
            }
        }
    }

    // LogConfig holds only a native pointer; keep a strong Java reference.
    private static PjLogWriter logWriter;

    /** Runs on the pjsip thread only. Brings up the endpoint if needed. */
    private void ensureStarted() throws Exception {
        if (started) return;
        System.loadLibrary(LIB_NAME);
        endpoint = new Endpoint();
        endpoint.libCreate();
        EpConfig cfg = new EpConfig();
        cfg.getUaConfig().setUserAgent("DialerSip/1.0");
        logWriter = new PjLogWriter();
        cfg.getLogConfig().setWriter(logWriter);
        cfg.getLogConfig().setLevel(5);
        // WebRTC echo canceller (built into our native lib) instead of the
        // simple subtractive EC — much better on Android speakerphone.
        cfg.getMedConfig().setEcOptions(3);   // PJMEDIA_ECHO_WEBRTC
        cfg.getMedConfig().setEcTailLen(200);
        cfg.getMedConfig().setClockRate(16000);
        endpoint.libInit(cfg);
        // Must come AFTER libInit(): libRegisterThread locks threadDescMutex,
        // which is only created inside libInit() — calling it earlier aborts
        // natively ("assertion mutex failed").
        endpoint.libRegisterThread("dialer-sip-main");
        TransportConfig udp = new TransportConfig();
        udp.setPort(0);
        endpoint.transportCreate(
                org.pjsip.pjsua2.pjsip_transport_type_e.PJSIP_TRANSPORT_UDP, udp);
        TransportConfig tcp = new TransportConfig();
        tcp.setPort(0);
        endpoint.transportCreate(
                org.pjsip.pjsua2.pjsip_transport_type_e.PJSIP_TRANSPORT_TCP, tcp);
        endpoint.libStart();
        // Prefer Opus (HD voice, wideband) over G.711 whenever the server
        // supports it; PCMU/PCMA remain as fallback.
        try {
            endpoint.codecSetPriority("opus/48000/2", (short) 255);
            endpoint.codecSetPriority("PCMU/8000", (short) 210);
            endpoint.codecSetPriority("PCMA/8000", (short) 200);
        } catch (Exception e) {
            Log.w(TAG, "codec priorities: " + e.getMessage());
        }
        started = true;
    }

    /** Runs on the pjsip thread only. Creates the account if missing. */
    private void ensureRegistered() {
        if (!SipAccountStore.configured(context)) return;
        if (account != null) return;
        try {
            AccountConfig cfg = new AccountConfig();
            cfg.setIdUri(SipAccountStore.sipUri(context));
            cfg.getRegConfig().setRegistrarUri(SipAccountStore.registrarUri(context));
            String proxy = SipAccountStore.outboundProxyUri(context);
            cfg.getSipConfig().getProxies().add(proxy);
            String authUser = SipAccountStore.authUser(context);
            if (authUser.isEmpty()) authUser = SipAccountStore.username(context);
            cfg.getSipConfig().getAuthCreds().add(new AuthCredInfo(
                    "digest", "*", authUser, 0, SipAccountStore.password(context)));
            cfg.getRegConfig().setRegisterOnAdd(true);
            cfg.getRegConfig().setTimeoutSec(300);
            cfg.getRegConfig().setRetryIntervalSec(30);
            // Keep NAT binding alive so incoming INVITEs can reach us.
            cfg.getNatConfig().setIceEnabled(false);
            cfg.getNatConfig().setSdpNatRewriteUse(1);
            account = new PjAccount();
            account.create(cfg);
        } catch (Exception e) {
            Log.e(TAG, "ensureRegistered failed", e);
        }
    }

    /** Blocks (pjsip thread) until the account registers, up to ~4s. */
    private void waitForRegistration() {
        for (int i = 0; i < 40 && account != null; i++) {
            try {
                if (account.getInfo().getRegStatus() / 100 == 2) return;
            } catch (Exception e) {
                // account not ready yet
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
                return;
            }
        }
        Log.w(TAG, "registration wait timed out; placing call anyway");
    }

    /** Re-reads stored settings and re-registers (settings changed / network back). */
    public void reRegister() {
        handler.post(() -> {
            if (!started) return;
            try {
                if (account != null) {
                    try {
                        account.setRegistration(false);
                        account.delete();
                    } catch (Exception e) {
                        Log.w(TAG, "old account teardown: " + e.getMessage());
                    }
                    account = null;
                }
                ensureRegistered();
            } catch (Exception e) {
                Log.e(TAG, "reRegister failed", e);
            }
        });
    }

    /** Drops the registration and account entirely (profile deleted). */
    public void deleteAccount() {
        handler.post(() -> {
            try {
                if (account != null) {
                    try {
                        account.setRegistration(false);
                        account.delete();
                    } catch (Exception e) {
                        Log.w(TAG, "account delete: " + e.getMessage());
                    }
                    account = null;
                }
            } catch (Exception e) {
                Log.e(TAG, "deleteAccount failed", e);
            }
        });
    }

    /** Places an outgoing call; returns the PjCall that backs the Connection.
     *  Self-sufficient: boots the stack + account if this process never did. */
    public PjCall makeCall(String dialUri, SipConnection connection) {
        final PjCall[] out = new PjCall[1];
        final Object lock = new Object();
        handler.post(() -> {
            try {
                ensureStarted();
                ensureRegistered();
                waitForRegistration();
                if (account == null) throw new IllegalStateException("no account after start");
                PjCall call = new PjCall(account, -1);
                call.setConnection(connection);
                CallOpParam prm = new CallOpParam(true);
                call.makeCall(dialUri, prm);
                out[0] = call;
            } catch (Exception e) {
                Log.e(TAG, "makeCall failed", e);
            }
            synchronized (lock) { lock.notifyAll(); }
        });
        synchronized (lock) {
            try { lock.wait(12000); } catch (InterruptedException ignored) {}
        }
        return out[0];
    }

    /** Full shutdown; called when receiving calls is disabled. */
    public void shutdown() {
        handler.post(() -> {
            try {
                if (account != null) {
                    account.setRegistration(false);
                    account.delete();
                    account = null;
                }
                if (started && endpoint != null) {
                    endpoint.libDestroy();
                    endpoint.delete();
                    endpoint = null;
                }
            } catch (Exception e) {
                Log.e(TAG, "shutdown failed", e);
            } finally {
                started = false;
            }
        });
    }
}
