package com.kanfan.player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.util.List;
import java.util.Locale;

/**
 * 下载前台服务：持有 BT 引擎，每秒刷一次状态，通知栏显示总速度。
 *
 * Android 8 起后台网络会被掐，所以必须挂着前台通知；退出界面下载照跑。
 */
public class TorrentService extends Service {

    private static final String CHANNEL = "kanfan_torrent";
    private static final int NOTIF_ID = 4301;

    private Handler ui;
    private Runnable tick;
    private TorrentEngine engine;
    private long lastNotif;
    private int lastText;

    public static void ensure(Context c) {
        Intent i = new Intent(c, TorrentService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        engine = TorrentEngine.get(this);
        createChannel();
        startForeground(NOTIF_ID, build("正在启动…"));

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    engine.start();
                    engine.restore();
                } catch (Throwable ignored) {
                }
            }
        }, "torrent-init").start();

        ui = new Handler(Looper.getMainLooper());
        tick = new Runnable() {
            @Override
            public void run() {
                engine.refresh();
                long now = System.currentTimeMillis();
                if (now - lastNotif > 1500) {
                    lastNotif = now;
                    update();
                }
                List<TorrentEngine.Task> list = engine.snapshot();
                if (list.isEmpty()) {
                    stopSelf();
                    return;
                }
                ui.postDelayed(this, 1000);
            }
        };
        ui.postDelayed(tick, 800);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    private void update() {
        List<TorrentEngine.Task> list = engine.snapshot();
        int active = 0, seeding = 0;
        long up = 0;
        for (TorrentEngine.Task t : list) {
            if (t.state == TorrentEngine.ST_DOWN || t.state == TorrentEngine.ST_META
                    || t.state == TorrentEngine.ST_CHECK) {
                active++;
            } else if (t.state == TorrentEngine.ST_SEED) {
                seeding++;
                up += t.up;
            }
        }
        long rate = engine.totalRate();
        String text;
        if (active > 0) {
            text = String.format(Locale.US, "%d 个任务 · %s/s", active, fmt(rate));
            if (seeding > 0) text += String.format(Locale.US, " · 做种 %d", seeding);
        } else if (seeding > 0) {
            text = String.format(Locale.US, "做种 %d 个 · ↑ %s/s", seeding, fmt(up));
        } else {
            text = list.isEmpty() ? "没有任务" : "已暂停 · " + list.size() + " 个任务";
        }
        int hash = text.hashCode();
        if (hash != lastText) {
            lastText = hash;
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            try {
                nm.notify(NOTIF_ID, build(text));
            } catch (Throwable ignored) {
            }
        }
    }

    private Notification build(String text) {
        Intent it = new Intent(this, TorrentActivity.class);
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int piFlag = Build.VERSION.SDK_INT >= 23
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getActivity(this, 0, it, piFlag);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
            b.setPriority(Notification.PRIORITY_LOW);
        }
        b.setSmallIcon(R.drawable.ic_download)
                .setContentTitle("看番 · 下载")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi);
        return b.build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "下载",
                NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        ch.setDescription("后台下载进度");
        nm.createNotificationChannel(ch);
    }

    static String fmt(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format(Locale.US, "%.1f KB", b / 1024.0);
        return String.format(Locale.US, "%.1f MB", b / 1048576.0);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (ui != null && tick != null) ui.removeCallbacks(tick);
        if (engine != null) engine.stopAsync();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
