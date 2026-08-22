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

    private SipConnection connection;
    private int lastState = -1;
    private String cachedRemoteUri = "";

    public PjCall(Account acc, int callId) {
        super(acc, callId);
    }

    /** Caches the remote URI from the pjsip thread; safe to read from any thread. */
    public void cacheRemoteUri() {
        try {
            cachedRemoteUri = getInfo().getRemoteUri();
        } catch (Exception e) {
            Log.w(TAG, "cacheRemoteUri: " + e.getMessage());
        }
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
                    if (CallRecording.enabled(AppContext.get())) {
                        CallRecording.startSip(callMedia, ep);
                    }
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
            if (c == null) return;
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
                    CallRecording.stopSip();
                    c.onSipDisconnected();
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
        PjManager.post(() -> {
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
        PjManager.post(() -> {
            try {
                CallOpParam prm = new CallOpParam();
                prm.setStatusCode(486);
                hangup(prm);
            } catch (Exception e) {
                Log.e(TAG, "reject failed", e);
            }
        });
    }

    /** Hangs up an active or pending call. Posted. */
    public void sipHangup() {
        PjManager.post(() -> {
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
        PjManager.post(() -> {
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
