package com.yunar.atelier;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;

/**
 * Outbound half of the remote channel.
 *
 * The set cannot host a listener — TCL's SELinux answers `bind()` with EPERM — but it can open
 * outbound sockets, so this posts a heartbeat + state to the operator's rendezvous every few
 * seconds and receives at most one whitelisted command in the reply. Nothing inbound ever has to
 * reach the TV, which is also why it keeps working after Android kills adbd in deep sleep.
 *
 * Configuration lives outside the APK, in the app's external files dir:
 *   link.host   "<your-agent-host-tailnet-ip>:7391"
 *   link.token  shared secret with the VM side
 * Both are pushed once over adb. Missing either one simply means "no link", never a crash.
 */
final class NetLink {

    private static final String TAG = "ATELIER_LINK";
    private static final long INTERVAL_MS = 8000L;

    private final Context ctx;
    private volatile boolean alive = true;
    private Thread thread;

    NetLink(Context ctx) { this.ctx = ctx; }

    void start() {
        if (thread != null && thread.isAlive()) { return; }
        alive = true;
        thread = new Thread(new Runnable() {
            @Override public void run() { loop(); }
        }, "atelier-link");
        thread.setDaemon(true);
        thread.start();
    }

    void stop() { alive = false; thread = null; }

    private String conf(String name) {
        try {
            File dir = ctx.getExternalFilesDir(null);
            if (dir == null) { return null; }
            File f = new File(dir, name);
            if (!f.exists()) { return null; }
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f));
            String line = r.readLine();
            r.close();
            return line == null ? null : line.trim();
        } catch (Exception e) { return null; }
    }

    private void loop() {
        String host = conf("link.host");
        String token = conf("link.token");
        if (host == null || token == null) {
            note("no link.host / link.token on device; channel disabled");
            return;
        }
        note("link up: host=" + host + " token_len=" + token.length());
        String pendingResult = "";
        int failures = 0;
        int okCount = 0;
        while (alive) {
            try {
                String state = ControlServer.State.json(ctx);
                String body = "{\"state\":" + state + ",\"result\":" + q(pendingResult) + "}";
                String reply = post("http://" + host + "/sync", token, body);
                pendingResult = "";
                failures = 0;
                okCount++;
                if (okCount == 1 || okCount % 60 == 0) { note("synced ok #" + okCount); }
                String cmd = field(reply, "cmd");
                if (cmd != null && !cmd.isEmpty()) {
                    note("cmd: " + cmd);
                    if (cmd.equals("arc-rearm")) {
                        pendingResult = rearm();
                    } else if (ControlServer.Shell.allowed(cmd)) {
                        pendingResult = ControlServer.Shell.run("/system/bin/sh -c \"" + cmd.replace("\"", "") + "\"", 20000);
                        if (pendingResult.length() > 3500) {
                            pendingResult = pendingResult.substring(0, 3500) + "…";
                        }
                    } else {
                        pendingResult = "refused (not whitelisted): " + cmd;
                    }
                }
            } catch (Exception e) {
                failures++;
                if (failures == 1 || failures % 20 == 0) { note("sync failed (" + failures + "): " + e); }
            }
            try { Thread.sleep(INTERVAL_MS); } catch (InterruptedException e) { return; }
        }
    }

    /**
     * Same sequence the wake watchdog uses, driven on demand: cycle the whole CEC controller with a
     * 15 s gap (measured: shorter gaps tear down a working handshake and never rebuild it).
     */
    private String rearm() {
        try {
            android.provider.Settings.Global.putInt(
                    ctx.getContentResolver(), "hdmi_control_enabled", 0);
            note("rearm: CEC off");
            Thread.sleep(15000L);
            android.provider.Settings.Global.putInt(
                    ctx.getContentResolver(), "hdmi_control_enabled", 1);
            note("rearm: CEC on");
            Thread.sleep(12000L);
            String p = "";
            try {
                Class<?> sp = Class.forName("android.os.SystemProperties");
                Object v = sp.getMethod("get", String.class).invoke(null, "persist.tcl.isARCConnect");
                p = v == null ? "" : v.toString();
            } catch (Throwable ignored) { }
            note("rearm: arc=" + p);
            return "rearm done, isARCConnect=" + p;
        } catch (Exception e) {
            note("rearm failed: " + e);
            return "rearm failed: " + e;
        }
    }

    private void note(String msg) {
        Log.i(TAG, msg);
        try {
            File dir = ctx.getExternalFilesDir(null);
            if (dir == null) { return; }
            java.io.FileWriter w = new java.io.FileWriter(new File(dir, "netlink.log"), true);
            w.write(new java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(new java.util.Date())
                    + " " + msg + "\n");
            w.flush();
            w.close();
        } catch (Exception ignored) { }
    }

    private static String post(String url, String token, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Authorization", "Bearer " + token);
            OutputStream os = c.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.flush();
            os.close();
            int code = c.getResponseCode();
            if (code != 200) { throw new Exception("HTTP " + code); }
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) { sb.append(line); }
            r.close();
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }

    private static String q(String s) {
        if (s == null) { return "null"; }
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "") + "\"";
    }

    /** Minimal field reader: the reply is a flat JSON object with string values. */
    private static String field(String json, String key) {
        if (json == null) { return null; }
        String needle = "\"" + key + "\":";
        int i = json.indexOf(needle);
        if (i < 0) { return null; }
        i += needle.length();
        while (i < json.length() && json.charAt(i) == ' ') { i++; }
        if (i >= json.length()) { return null; }
        if (json.charAt(i) == 'n') { return null; }          // null
        if (json.charAt(i) != '"') { return null; }
        i++;
        StringBuilder sb = new StringBuilder();
        while (i < json.length()) {
            char ch = json.charAt(i);
            if (ch == '\\' && i + 1 < json.length()) {
                char n = json.charAt(i + 1);
                if (n == 'n') { sb.append('\n'); }
                else if (n == '"') { sb.append('"'); }
                else if (n == '\\') { sb.append('\\'); }
                else { sb.append(n); }
                i += 2;
                continue;
            }
            if (ch == '"') { break; }
            sb.append(ch);
            i++;
        }
        return sb.toString();
    }
}
