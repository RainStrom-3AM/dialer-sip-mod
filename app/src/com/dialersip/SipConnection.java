package com.dialersip;

import android.telecom.Connection;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccountHandle;

/**
 * Telecom Connection bridged to a PjCall. Telecom calls the on*() methods;
 * PjCall invokes the onSip*() callbacks from the pjsip thread.
 */
public final class SipConnection extends Connection {

    private final PhoneAccountHandle handle;
    private final android.content.Context context;
    private final boolean incoming;
    private PjCall pjCall;
    private boolean audioSetup;

    public SipConnection(PhoneAccountHandle handle, android.content.Context context,
                         boolean incoming) {
        this.handle = handle;
        this.context = context;
        this.incoming = incoming;
        setAudioModeIsVoip(true);
        if (incoming) {
            setRinging();
        } else {
            // Audible ringback for outgoing calls; Telecom plays it while DIALING.
            setRingbackRequested(true);
        }
    }

    void attach(PjCall call) {
        pjCall = call;
    }

    // ---- Telecom-driven actions ----

    @Override
    public void onAnswer(int videoState) {
        if (pjCall != null) pjCall.sipAnswer();
    }

    @Override
    public void onReject() {
        if (pjCall != null) pjCall.sipReject();
        setDisconnected(new DisconnectCause(DisconnectCause.REJECTED));
        destroy();
    }

    @Override
    public void onDisconnect() {
        if (pjCall != null) pjCall.sipHangup();
        setDisconnected(new DisconnectCause(DisconnectCause.LOCAL));
        destroy();
    }

    @Override
    public void onHold() {
        // Hold across a single SIP call: not supported in v1.
    }

    @Override
    public void onUnhold() {
    }

    @Override
    public void onPlayDtmfTone(char c) {
        if (pjCall != null) {
            pjCall.sipDtmf(String.valueOf(c));
        }
    }

    // ---- SIP-driven state updates (called from pjsip thread) ----

    void onSipRinging() {
        // Remote 180/183. For outgoing calls this is the CALLEE ringing:
        // stay in DIALING (Telecom plays the local ringback we requested).
        // Only incoming connections may enter RINGING — calling setRinging()
        // on an outgoing connection flips the whole call to the incoming UI.
        if (incoming) {
            setRinging();
        }
    }

    void onSipActive() {
        setActive();
        setupAudio();
    }

    void onSipDisconnected() {
        teardownAudio();
        setDisconnected(new DisconnectCause(DisconnectCause.REMOTE));
        destroy();
    }

    // ---- Audio ----

    // ---- Audio ----
    // NOTE: audio routing (speaker/earpiece/wired/Bluetooth) is handled entirely
    // by Telecom's CallAudioRouteController for managed connections. Applying
    // setCommunicationDevice() ourselves races Telecom's own application,
    // re-triggers its onCommunicationDeviceChanged state machine and makes the
    // speaker toggle snap back to earpiece. Do NOT route from here.

    private void setupAudio() {
        if (audioSetup) return;
        audioSetup = true;
        android.media.AudioManager am = (android.media.AudioManager)
                context.getSystemService(android.content.Context.AUDIO_SERVICE);
        if (am != null) {
            am.setMode(android.media.AudioManager.MODE_IN_COMMUNICATION);
            // Gentle in-call volume bump: raise to 90% only if the user's
            // current level is below that.
            try {
                int max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_VOICE_CALL);
                int cur = am.getStreamVolume(android.media.AudioManager.STREAM_VOICE_CALL);
                if (cur < max * 9 / 10) {
                    am.setStreamVolume(android.media.AudioManager.STREAM_VOICE_CALL,
                            max * 9 / 10, 0);
                }
            } catch (Exception ignored) {
            }
        }
    }

    private void teardownAudio() {
        if (!audioSetup) return;
        audioSetup = false;
        android.media.AudioManager am = (android.media.AudioManager)
                context.getSystemService(android.content.Context.AUDIO_SERVICE);
        if (am != null) am.setMode(android.media.AudioManager.MODE_NORMAL);
    }
}
