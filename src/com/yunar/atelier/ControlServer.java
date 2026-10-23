package com.yunar.atelier;

import android.content.Context;

/**
 * The parts every remote path in this app shares: how to describe the set, and what may be run.
 *
 * History worth keeping: this started life as a listener on the TV. On this firmware an untrusted
 * uid cannot bind a listening socket — and every socket call fails with
 * `EPERM (Operation not permitted)` unless the app holds INTERNET, a missing permission that reads
 * exactly like a firewall. The working design is the outbound link in {@link NetLink}.
 */
final class ControlServer {

    private ControlServer() { }

    /**
     * Device state through app-level APIs only. An untrusted app cannot run `dumpsys` or read
     * /proc, so this is assembled from PowerManager, AudioManager and the vendor ARC property —
     * the honest ceiling without root, and enough to answer "is the bar receiving audio".
     */
    static final class State {
        static String json(Context ctx) {
            String arcProp = "";
            try {
                Class<?> sp = Class.forName("android.os.SystemProperties");
                Object v = sp.getMethod("get", String.class).invoke(null, "persist.tcl.isARCConnect");
                arcProp = v == null ? "" : v.toString();
            } catch (Throwable ignored) { }

            boolean interactive = false;
            long upS = 0;
            try {
                android.os.PowerManager pm =
                        (android.os.PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
                interactive = pm != null && pm.isInteractive();
                upS = android.os.SystemClock.elapsedRealtime() / 1000L;
            } catch (Exception ignored) { }

            String outputs = "";
            int musicVol = -1;
            boolean muted = false;
            try {
                android.media.AudioManager am =
                        (android.media.AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
                if (am != null) {
                    StringBuilder sb = new StringBuilder();
                    for (android.media.AudioDeviceInfo d : am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)) {
                        if (sb.length() > 0) { sb.append(','); }
                        sb.append(typeName(d.getType()));
                    }
                    outputs = sb.toString();
                    musicVol = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
                    muted = am.isStreamMute(android.media.AudioManager.STREAM_MUSIC);
                }
            } catch (Exception ignored) { }

            String ver = "";
            try {
                ver = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
            } catch (Exception ignored) { }

            return "{"
                    + "\"ok\":true,"
                    + "\"version\":" + q(ver) + ","
                    + "\"arc_connect\":" + q(arcProp) + ","
                    + "\"interactive\":" + interactive + ","
                    + "\"uptime_s\":" + upS + ","
                    + "\"outputs\":" + q(outputs) + ","
                    + "\"music_vol\":" + musicVol + ","
                    + "\"music_muted\":" + muted
                    + "}";
        }

        private static String typeName(int t) {
            switch (t) {
                case android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: return "speaker";
                case android.media.AudioDeviceInfo.TYPE_HDMI: return "hdmi";
                case android.media.AudioDeviceInfo.TYPE_HDMI_ARC: return "hdmi_arc";
                case android.media.AudioDeviceInfo.TYPE_HDMI_EARC: return "hdmi_earc";
                case android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: return "bt_a2dp";
                case android.media.AudioDeviceInfo.TYPE_USB_HEADSET: return "usb_headset";
                default: return "type" + t;
            }
        }

        static String q(String s) {
            if (s == null) { return "null"; }
            StringBuilder sb = new StringBuilder("\"");
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"':  sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': break;
                    case '\t': sb.append("\\t"); break;
                    default:
                        if (c < 0x20) { sb.append(' '); } else { sb.append(c); }
                }
            }
            return sb.append('"').toString();
        }
    }

    /** Runs a command with a hard timeout. Only what an untrusted uid is allowed to run. */
    static final class Shell {
        private static final String[] ALLOWED = { "getprop", "settings get ", "settings put ", "pm list" };

        private Shell() { }

        static boolean allowed(String cmd) {
            if (cmd == null) { return false; }
            String c = cmd.trim();
            if (c.isEmpty() || c.length() > 400) { return false; }
            // no chaining, no redirection, no substitution
            if (c.contains(";") || c.contains("&&") || c.contains("||") || c.contains("`") || c.contains("$(")) {
                return false;
            }
            for (String p : ALLOWED) {
                if (c.startsWith(p)) { return true; }
            }
            return false;
        }

        static String run(String cmd, int timeoutMs) {
            StringBuilder sb = new StringBuilder();
            Process p = null;
            try {
                p = Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", cmd});
                final Process proc = p;
                Thread killer = new Thread(new Runnable() {
                    @Override public void run() {
                        try { Thread.sleep(timeoutMs); } catch (InterruptedException ignored) { }
                        try { proc.destroy(); } catch (Exception ignored) { }
                    }
                });
                killer.setDaemon(true);
                killer.start();
                drain(p.getInputStream(), sb);
                drain(p.getErrorStream(), sb);
                p.waitFor();
                killer.interrupt();
            } catch (Exception e) {
                sb.append("error: ").append(e);
            } finally {
                try { if (p != null) { p.destroy(); } } catch (Exception ignored) { }
            }
            return sb.toString();
        }

        private static void drain(java.io.InputStream in, StringBuilder sb) throws java.io.IOException {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) { sb.append(new String(buf, 0, n, "UTF-8")); }
        }
    }
}
