package com.yunar.atelier;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * Keeps the tailnet control channel alive.
 *
 * This exists because TCL's TGuardService kills idle apps to make room for Play Store — the log
 * says it plainly: "killing pkg:com.yunar.atelier uid:10066 [service] is not depends on
 * com.android.vending". A plain Thread inside the activity therefore disappears within a minute,
 * which is exactly how a remote channel must not behave. A foreground service raises the process
 * importance enough to survive that sweep, and START_STICKY brings it back if it ever does die.
 */
public class ControlService extends Service {

    private static final String CHANNEL = "atelier_ctl";
    private static final int NOTE_ID = 42;
    private NetLink server;

    @Override public void onCreate() {
        super.onCreate();
        startForeground(NOTE_ID, buildNotification());
        server = new NetLink(this);
        server.start();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (server != null) { server.start(); }
        return START_STICKY;
    }

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL, "Atelier remote", NotificationManager.IMPORTANCE_MIN);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return b.setContentTitle("Atelier")
                .setContentText("Remote channel active")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setOngoing(true)
                .build();
    }

    @Override public void onDestroy() {
        if (server != null) { server.stop(); }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
