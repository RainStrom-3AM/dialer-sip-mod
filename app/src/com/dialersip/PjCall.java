package com.dialersip;

import android.util.Log;

import org.pjsip.pjsua2.Account;
import org.pjsip.pjsua2.AudioMedia;
import org.pjsip.pjsua2.Call;
import org.pjsip.pjsua2.CallInfo;
import org.pjsip.pjsua2.CallOpParam;
import org.pjsip.pjsua2.Endpoint;
import org.pjsip.pjsua2.OnCallMediaStateParam;
import org.pjsip.pjsua2.OnCallStateParam;

/**
 * A single SIP call backed by a pjsua2 Call; forwards state changes to the Telecom Connection.
 * All pjsua2 operations are posted onto the single pjlib-registered thread —
 * calling into pjlib from Telecom's binder threads crashes natively
 * (pj_thread_this on an unregistered thread).
 */
public final class PjCall extends Call {

    private static final String TAG = "DialerSip";

    /**
     * Strong references to every live PjCall. A pjsua2 Call object whose Java
     * wrapper gets garbage-collected runs its native destructor (which calls
     * hangup) on the GC/finalizer thread - an unregistered pjlib thread -
     * and aborts the whole app (pj_thread_this assertion). Calls are removed
     * here only when explicitly delete()d on the pjlib thread post-disconnect.
     */
    private static final java.util.Set<PjCall> ACTIVE =
            java.util.Collections.synchronizedSet(new java.util.HashSet<PjCall>());

    private SipConnection connection;
    private int lastState = -1;
    private String cachedRemoteUri = "";
    private volatile boolean destroyed;

    public PjCall(Account acc, int callId) {
        super(acc, callId);
        ACTIVE.add(this);
    }

    /** Frees the native call on the pjlib thread once Telecom is done with it. */
    private static void scheduleDestroy(PjCall c) {
        PjManager.postDelayed(() -> {
            ACTIVE.remove(c);
            c.destroyed = true;
            try {
                c.delete();
                Log.i(TAG, "native call object freed");
            } catch (Throwable t) {
                Log.w(TAG, "call delete: " + t);
            }
        }, 15000);
    }

    /** Caches the remote URI from the pjsip thread; safe to read from any thread. */
    public void cacheRemoteUri() {
        try {
            cachedRemoteUri = getInfo().getRemoteUri();
        } catch (Exception e) {
            Log.w(TAG, "cacheRemoteUri: " + e.getMessage());
        }
    }

    /**
     * Picks the caller identity from the raw incoming INVITE. Order:
     * P-Asserted-Identity, Remote-Party-ID, then the From header (via
     * CallInfo). Carriers frequently send an anonymous From with the real
     * CLI in P-Asserted-Identity, so From-only parsing logged "Unknown".
     */
    void cacheIncomingIdentity(String rawInvite) {
        if (rawInvite != null && !rawInvite.isEmpty()) {
            try {
                String pai = headerValue(rawInvite, "P-Asserted-Identity");
                String rpid = headerValue(rawInvite, "Remote-Party-ID");
                String from = headerValue(rawInvite, "From");
                Log.i(TAG, "incoming INVITE identity headers:"
                        + " PAI=" + trunc(pai) + " RPID=" + trunc(rpid)
                        + " From=" + trunc(from));
                String chosen = pai != null ? pai : (rpid != null ? rpid : null);
                if (chosen != null && containsSipOrTelUri(chosen)) {
                    cachedRemoteUri = chosen.trim();
                    Log.i(TAG, "caller identity from " + (pai != null ? "P-Asserted-Identity" : "Remote-Party-ID"));
                    return;
                }
            } catch (Exception e) {
                Log.w(TAG, "identity header parse: " + e);
            }
        }
        cacheRemoteUri();
    }

    /** Value of a header from a raw SIP message (handles folded lines); null if absent. */
    private static String headerValue(String msg, String header) {
        String[] lines = msg.split("\r?\n");
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            int colon = l.indexOf(':');
            if (colon <= 0) continue;
            if (!l.substring(0, colon).trim().equalsIgnoreCase(header)) continue;
            StringBuilder v = new StringBuilder(l.substring(colon + 1).trim());
            for (int j = i + 1; j < lines.length; j++) {   // folded continuation lines
                String nl = lines[j];
                if (nl.isEmpty() || (nl.charAt(0) != ' ' && nl.charAt(0) != '\t')) break;
                v.append(' ').append(nl.trim());
            }
            return v.toString();
        }
        return null;
    }

    private static boolean containsSipOrTelUri(String v) {
        return v != null && (v.contains("sip:") || v.contains("sips:") || v.contains("tel:"));
    }

    private static String trunc(String s) {
        if (s == null) return "none";
        return s.length() > 60 ? s.substring(0, 60) + "..." : s;
    }

    public void setConnection(SipConnection c) {
        connection = c;
    }

    public SipConnection getConnection() {
        return connection;
    }

    @Override
    public void onCallMediaState(OnCallMediaStateParam prm) {
        // pjsip thread — safe to touch pjlib directly.
        // pjsua2 does NOT auto-connect call media to the sound device;
        // without these bridges the call connects but carries no audio.
        try {
            CallInfo info = getInfo();
            for (int i = 0; i < info.getMedia().size(); i++) {
                if (info.getMedia().get(i).getType()
                        != org.pjsip.pjsua2.pjmedia_type.PJMEDIA_TYPE_AUDIO) {
                    continue;
                }
                try {
                    Endpoint ep = PjManager.endpoint();
                    AudioMedia callMedia = getAudioMedia(i);
                    AudioMedia mic = ep.audDevManager().getCaptureDevMedia();
                    AudioMedia speaker = ep.audDevManager().getPlaybackDevMedia();
                    mic.startTransmit(callMedia);      // microphone  -> call
                    callMedia.startTransmit(speaker);  // call        -> speaker
                    // Volume boost: 1.5x what we receive (speaker side),
                    // 1.2x what we send (mic side, conservative to avoid clipping).
                    callMedia.adjustRxLevel(1.5f);
                    callMedia.adjustTxLevel(1.2f);
                    Log.i(TAG, "audio bridged (media #" + i + ")");
                } catch (Exception e) {
                    Log.e(TAG, "bridge media #" + i + " failed", e);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "onCallMediaState", e);
        }
    }

    @Override
    public void onCallState(OnCallStateParam prm) {
        // Runs on pjsip's own thread — safe to call getInfo() directly.
        SipConnection c = connection;
        try {
            CallInfo info = getInfo();
            int state = info.getState();
            if (state == lastState) return;
            lastState = state;
            if (state == 6) { // DISCONNECTED — log why, for diagnostics
                Log.i(TAG, "call disconnected: code=" + info.getLastStatusCode()
                        + " reason=" + info.getLastReason()
                        + " remote=" + info.getRemoteUri());
            }
            if (c == null) {
                if (state == 6) scheduleDestroy(this);
                return;
            }
            // PJSIP_INV_STATE_*: 0 NULL, 1 CALLING, 2 INCOMING, 3 EARLY, 4 CONNECTING,
            // 5 CONFIRMED, 6 DISCONNECTED
            switch (state) {
                case 3: // EARLY (180/183): remote is ringing
                    c.onSipRinging();
                    break;
                case 5: // CONFIRMED
                    c.onSipActive();
                    break;
                case 6: // DISCONNECTED
                    c.onSipDisconnected();
                    scheduleDestroy(this);
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            Log.e(TAG, "onCallState", e);
        }
    }

    /** Answers an incoming call. Posted: called from Telecom's binder thread. */
    public void sipAnswer() {
        if (destroyed) return;
        PjManager.post(() -> {
            if (destroyed) return;
            try {
                CallOpParam prm = new CallOpParam();
                prm.setStatusCode(200);
                answer(prm);
            } catch (Exception e) {
                Log.e(TAG, "answer failed", e);
            }
        });
    }

    /** Declines with BUSY_HERE. Posted. */
    public void sipReject() {
        if (destroyed) return;
        PjManager.post(() -> {
            if (destroyed) return;
            try {
                CallOpParam prm = new CallOpParam();
                prm.setStatusCode(486);
                prm.setReason("Busy Here");
                hangup(prm);
                Log.i(TAG, "486 Busy Here sent");
            } catch (Exception e) {
                Log.e(TAG, "reject failed", e);
            }
        });
    }

    /** Hangs up an active or pending call. Posted. */
    public void sipHangup() {
        if (destroyed) return;
        PjManager.post(() -> {
            if (destroyed) return;
            try {
                CallOpParam prm = new CallOpParam();
                prm.setStatusCode(0);
                hangup(prm);
            } catch (Exception e) {
                Log.e(TAG, "hangup failed", e);
            }
        });
    }

    /** Sends DTMF. Posted. */
    public void sipDtmf(String digits) {
        if (destroyed) return;
        PjManager.post(() -> {
            if (destroyed) return;
            try {
                dialDtmf(digits);
            } catch (Exception e) {
                Log.e(TAG, "dtmf failed", e);
            }
        });
    }

    /** Remote URI for display (cached on the pjsip thread; safe from any thread). */
    public String remoteUri() {
        return cachedRemoteUri;
    }
}
