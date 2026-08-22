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

    // ---- Audio routing (speaker / earpiece / wired / bluetooth) ----

    @Override
    public void onCallAudioStateChanged(android.telecom.CallAudioState state) {
        applyAudioRoute(state.getRoute());
    }

    private void applyAudioRoute(int route) {
        // setCommunicationDevice needs API 31; guard for minSdk 30 devices
        try {
            applyAudioRouteInner(route);
        } catch (Throwable t) {
            android.util.Log.w("DialerSip", "audio route: " + t);
        }
    }

    private void applyAudioRouteInner(int route) {
        android.media.AudioManager am = (android.media.AudioManager)
                context.getSystemService(android.content.Context.AUDIO_SERVICE);
        if (am == null) return;
        int wanted;
        switch (route) {
            case android.telecom.CallAudioState.ROUTE_SPEAKER:
                wanted = android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;
                break;
            case android.telecom.CallAudioState.ROUTE_EARPIECE:
                wanted = android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE;
                break;
            case android.telecom.CallAudioState.ROUTE_WIRED_HEADSET:
            case android.telecom.CallAudioState.ROUTE_WIRED_OR_EARPIECE:
                wanted = android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET;
                break;
            case android.telecom.CallAudioState.ROUTE_BLUETOOTH:
                wanted = android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO;
                break;
            default:
                return;
        }
        for (android.media.AudioDeviceInfo d : am.getAvailableCommunicationDevices()) {
            if (d.getType() == wanted
                    || (wanted == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET
                        && d.getType() == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES)) {
                if (am.setCommunicationDevice(d)) {
                    android.util.Log.i("DialerSip", "audio route -> type " + d.getType());
                    return;
                }
            }
        }
        // Fallback for devices without communication-device routing.
        am.setSpeakerphoneOn(route == android.telecom.CallAudioState.ROUTE_SPEAKER);
    }

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
