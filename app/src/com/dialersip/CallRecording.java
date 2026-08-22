package com.dialersip;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.util.Log;

import org.pjsip.pjsua2.AudioMedia;
import org.pjsip.pjsua2.AudioMediaRecorder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * "Record all calls" engine.
 *
 * SIP calls: recorded inside pjsua2 (both legs mixed into the conference
 * recorder) — best quality, no audio-device contention.
 * SIM calls: captured from the voice-call audio source (permitted for this
 * privileged app via CAPTURE_AUDIO_OUTPUT).
 *
 * Finished recordings are published via MediaStore into
 * Recordings/Call recordings/ so they appear in Files and players.
 */
public final class CallRecording {

    private static final String TAG = "DialerSip";
    private static final String PREFS = "dialer_sip_prefs";
    private static final String K_RECORD = "record_all_calls";

    // ---- SIP side (runs on the pjsip thread only) ----

    private static AudioMediaRecorder sipRecorder;
    private static File sipTmpFile;

    public static boolean enabled(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(K_RECORD, false);
    }

    public static void setEnabled(Context c, boolean on) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(K_RECORD, on).apply();
        if (on) {
            startSimWatch(c.getApplicationContext());
        } else {
            stopSimWatch();
        }
    }

    /** Called from PjCall.onCallMediaState after the audio bridge is up. */
    static void startSip(AudioMedia callMedia, org.pjsip.pjsua2.Endpoint ep) {
        if (sipRecorder != null) return; // one recording at a time
        try {
            Context c = AppContext.get();
            if (c == null) return;
            File base = c.getExternalFilesDir("recordings");
            if (base == null) base = c.getDir("recordings", Context.MODE_PRIVATE);
            //noinspection ResultOfMethodCallIgnored
            base.mkdirs();
            sipTmpFile = new File(base, "sip_" + stamp() + ".wav");
            sipRecorder = new AudioMediaRecorder();
            sipRecorder.createRecorder(sipTmpFile.getAbsolutePath());
            callMedia.startTransmit(sipRecorder);                       // remote voice
            ep.audDevManager().getCaptureDevMedia()
                    .startTransmit(sipRecorder);                        // local voice
            Log.i(TAG, "SIP recording started: " + sipTmpFile.getName());
        } catch (Exception e) {
            Log.e(TAG, "SIP recording start failed", e);
            sipRecorder = null;
            sipTmpFile = null;
        }
    }

    /** Called from PjCall when the call ends. */
    static void stopSip() {
        AudioMediaRecorder r = sipRecorder;
        File f = sipTmpFile;
        sipRecorder = null;
        sipTmpFile = null;
        if (r == null || f == null) return;
        try {
            r.delete(); // finalizes the WAV header
        } catch (Exception e) {
            Log.e(TAG, "recorder delete failed", e);
        }
        publish(f);
    }

    // ---- SIM side ----

    private static TelephonyManager telephonyManager;
    private static SimListener simListener;
    private static SimRecorderThread simThread;

    private static class SimListener extends TelephonyCallback
            implements TelephonyCallback.CallStateListener {
        @Override
        public void onCallStateChanged(int state) {
            if (state == TelephonyManager.CALL_STATE_OFFHOOK) {
                startSimRecording();
            } else if (state == TelephonyManager.CALL_STATE_IDLE) {
                stopSimRecording();
            }
        }
    }

    @SuppressWarnings({"deprecation", "removal"})
    private static class LegacyListener extends PhoneStateListener {
        @Override
        public void onCallStateChanged(int state, String phoneNumber) {
            if (state == TelephonyManager.CALL_STATE_OFFHOOK) {
                startSimRecording();
            } else if (state == TelephonyManager.CALL_STATE_IDLE) {
                stopSimRecording();
            }
        }
    }

    private static Object watchHandle; // TelephonyCallback or legacy listener

    static void startSimWatch(Context c) {
        if (watchHandle != null) return;
        telephonyManager =
                (TelephonyManager) c.getSystemService(Context.TELEPHONY_SERVICE);
        if (telephonyManager == null) return;
        try {
            SimListener l = new SimListener();
            telephonyManager.registerTelephonyCallback(Runnable::run, l);
            watchHandle = l;
        } catch (Exception e) {
            try {
                LegacyListener l = new LegacyListener();
                telephonyManager.listen(l, PhoneStateListener.LISTEN_CALL_STATE);
                watchHandle = l;
            } catch (Exception e2) {
                Log.e(TAG, "SIM call watch failed", e2);
            }
        }
    }

    private static void stopSimWatch() {
        if (watchHandle == null) return;
        try {
            if (watchHandle instanceof TelephonyCallback) {
                telephonyManager.unregisterTelephonyCallback((TelephonyCallback) watchHandle);
            } else {
                telephonyManager.listen((PhoneStateListener) watchHandle,
                        PhoneStateListener.LISTEN_NONE);
            }
        } catch (Exception ignored) {
        }
        watchHandle = null;
        stopSimRecording();
    }

    private static void startSimRecording() {
        if (simThread != null) return;
        simThread = new SimRecorderThread();
        simThread.start();
    }

    private static void stopSimRecording() {
        SimRecorderThread t = simThread;
        simThread = null;
        if (t != null) t.shutdown();
    }

    private static final class SimRecorderThread extends Thread {
        private volatile boolean running = true;
        private AudioRecord record;
        private File tmp;

        SimRecorderThread() {
            super("sim-recorder");
        }

        void shutdown() {
            running = false;
        }

        @Override
        public void run() {
            try {
                int rate = 48000;
                int minBuf = AudioRecord.getMinBufferSize(rate,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                record = new AudioRecord(MediaRecorder.AudioSource.VOICE_CALL, rate,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                        Math.max(minBuf * 4, 65536));
                if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "VOICE_CALL AudioRecord not initialized");
                    return;
                }
                Context c = AppContext.get();
                if (c == null) return;
                File base = c.getExternalFilesDir("recordings");
                if (base == null) base = c.getDir("recordings", Context.MODE_PRIVATE);
                //noinspection ResultOfMethodCallIgnored
                base.mkdirs();
                tmp = new File(base, "sim_" + stamp() + ".wav");
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    byte[] silence = new byte[44];
                    out.write(silence); // WAV header placeholder
                    record.startRecording();
                    ByteBuffer bb = ByteBuffer.allocate(16384);
                    long total = 0;
                    while (running) {
                        int n = record.read(bb.array(), 0, bb.capacity());
                        if (n > 0) {
                            out.write(bb.array(), 0, n);
                            total += n;
                        } else if (n < 0) {
                            break;
                        }
                    }
                    record.stop();
                    rewriteHeader(out, total, rate);
                }
                Log.i(TAG, "SIM recording finished: " + tmp.getName());
                publish(tmp);
            } catch (Exception e) {
                Log.e(TAG, "SIM recording failed", e);
            } finally {
                if (record != null) {
                    try {
                        record.release();
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        /** Reopens the file and writes the real RIFF header with the true size. */
        private void rewriteHeader(FileOutputStream ignored, long dataBytes, int rate) {
            try (RandomAccessFileShim raf = new RandomAccessFileShim(tmp)) {
                byte[] header = wavHeader(dataBytes, rate);
                raf.write(header, 0, 44);
            } catch (Exception e) {
                Log.e(TAG, "wav header rewrite failed", e);
            }
        }
    }

    /** Minimal RandomAccessFile wrapper (avoids extra import juggling). */
    private static final class RandomAccessFileShim implements AutoCloseable {
        private final java.io.RandomAccessFile raf;

        RandomAccessFileShim(File f) throws IOException {
            raf = new java.io.RandomAccessFile(f, "rw");
        }

        void write(byte[] b, int off, int len) throws IOException {
            raf.seek(0);
            raf.write(b, off, len);
        }

        @Override
        public void close() throws IOException {
            raf.close();
        }
    }

    private static byte[] wavHeader(long dataBytes, int rate) {
        ByteBuffer b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        long totalLen = 36 + dataBytes;
        b.put("RIFF".getBytes()).putInt((int) totalLen).put("WAVE".getBytes());
        b.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1);
        b.putInt(rate).putInt(rate * 2).putShort((short) 2).putShort((short) 16);
        b.put("data".getBytes()).putInt((int) dataBytes);
        return b.array();
    }

    // ---- Publishing into MediaStore (Recordings/Call recordings/) ----

    private static void publish(File wav) {
        final File f = wav;
        android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        h.post(() -> {
            Context c = AppContext.get();
            if (c == null || f == null || !f.exists()) return;
            try {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Audio.Media.DISPLAY_NAME, f.getName());
                v.put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav");
                v.put(MediaStore.Audio.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_RECORDINGS + "/Call recordings");
                v.put(MediaStore.Audio.Media.IS_PENDING, 1);
                android.net.Uri uri = c.getContentResolver()
                        .insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v);
                if (uri == null) return;
                try (OutputStream os = c.getContentResolver().openOutputStream(uri);
                     FileInputStream in = new FileInputStream(f)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        os.write(buf, 0, n);
                    }
                }
                ContentValues done = new ContentValues();
                done.put(MediaStore.Audio.Media.IS_PENDING, 0);
                c.getContentResolver().update(uri, done, null, null);
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                Log.i(TAG, "recording published: " + f.getName());
            } catch (Exception e) {
                Log.e(TAG, "publish recording failed", e);
            }
        });
    }

    private static String stamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(new Date());
    }
}
