package com.strongholdprotocol.client;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * Keeps the process (and therefore the Node child) alive while the phone is hosting a LAN game.
 *
 * <p>Deliberately thin: it does not own {@link LocalServer}. The server lives on {@link App}, so if
 * promoting to the foreground fails on some OEM build — or the player never leaves the settings
 * screen — the server still runs. This service only buys the process a reason not to be reclaimed.
 */
public class LocalServerService extends Service {

    private static final String TAG = "LocalServerService";
    private static final String CHANNEL = "localsrv";
    private static final int NOTIFICATION_ID = 4711;

    public static final String ACTION_START = "com.strongholdprotocol.client.START_LOCAL_SERVER";
    public static final String ACTION_STOP = "com.strongholdprotocol.client.STOP_LOCAL_SERVER";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopForegroundCompat();
            stopSelf();
            return START_NOT_STICKY;
        }

        promote();
        LocalServer srv = App.localServerOf(this);
        if (!srv.isRunning()) {
            final LocalServer.Listener noop = null;
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        App.localServerOf(LocalServerService.this).start(noop);
                    } catch (Exception e) {
                        Log.w(TAG, "local server failed to start", e);
                    }
                }
            }, "localsrv-start").start();
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        Log.i(TAG, "service destroyed, stopping the child process");
        App.localServerOf(this).stop();
        super.onDestroy();
    }

    private void promote() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm.getNotificationChannel(CHANNEL) == null) {
                    NotificationChannel ch = new NotificationChannel(CHANNEL, "本机服务器",
                        NotificationManager.IMPORTANCE_LOW);
                    ch.setDescription("局域网联机时保持服务端运行");
                    ch.setShowBadge(false);
                    nm.createNotificationChannel(ch);
                }
            }
            Intent open = new Intent(this, MainActivity.class);
            int piFlags = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
            PendingIntent content = PendingIntent.getActivity(this, 0, open, piFlags);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
            b.setContentTitle("本机服务器运行中")
             .setContentText("局域网里的其他设备可以连接")
             .setSmallIcon(R.mipmap.ic_launcher)
             .setContentIntent(content)
             .setOngoing(true);
            startForeground(NOTIFICATION_ID, b.build());
        } catch (Throwable t) {
            // Some OEM builds are strict about foreground promotion. Not fatal: the server is a child
            // of this process either way, it just loses its protection from being reclaimed.
            Log.w(TAG, "startForeground failed, continuing without it", t);
        }
    }

    private void stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
            else stopForeground(true);
        } catch (Throwable ignored) { }
    }

    /** Starts the service the way the running API level wants it started. Never throws. */
    public static void start(Context c) {
        try {
            Intent i = new Intent(c, LocalServerService.class).setAction(ACTION_START);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Throwable t) {
            Log.w(TAG, "could not start the foreground service", t);
        }
    }

    public static void stop(Context c) {
        try {
            Intent i = new Intent(c, LocalServerService.class).setAction(ACTION_STOP);
            c.startService(i);
        } catch (Throwable t) {
            Log.w(TAG, "could not stop the service", t);
        }
    }
}
