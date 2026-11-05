package com.controllerstar.x360bridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import java.io.IOException;

/**
 * Short-lived foreground service used for one-shot commands and for the
 * "restore after boot" retry loop. It never stays resident: the bridge itself
 * is a detached native process, and the ongoing notification keeps working
 * through PendingIntents after this service has stopped.
 */
public class BridgeService extends Service {
    public static final String ACTION_RESTORE = "com.controllerstar.x360bridge.RESTORE";
    public static final String ACTION_START = "com.controllerstar.x360bridge.START";
    public static final String ACTION_STOP = "com.controllerstar.x360bridge.STOP";
    public static final String ACTION_TOGGLE = "com.controllerstar.x360bridge.TOGGLE";

    private static final String CHANNEL = "x360bridge";
    private static final int NOTIFICATION_ID = 3601;
    private static final int RETRY_COUNT = 36;   // 36 * 10s = 6 minutes
    private static final long RETRY_INTERVAL = 10000L;

    private volatile boolean working;
    private String lastText = "手柄桥";
    private boolean lastRunning = false;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_TOGGLE : intent.getAction();
        if (action == null) action = ACTION_TOGGLE;
        createChannel();
        startForeground(NOTIFICATION_ID, buildNotification("正在操作手柄桥…", false));
        if (working) return START_NOT_STICKY;
        working = true;
        final String task = action;
        new Thread(() -> {
            try {
                runTask(task);
            } catch (Exception e) {
                showNotification("操作失败：" + e.getMessage(), false);
            } finally {
                working = false;
                stopForeground(true);
                showNotification(lastText, lastRunning);
                stopSelf();
            }
        }, "x360bridge-command").start();
        return START_NOT_STICKY;
    }

    private void runTask(String action) throws IOException {
        BridgeManager manager = BridgeManager.get(this);
        if (ACTION_RESTORE.equals(action)) {
            restoreAfterBoot(manager);
            return;
        }
        String command;
        if (ACTION_STOP.equals(action)) {
            command = manager.buildStopCommand();
        } else if (ACTION_START.equals(action)) {
            command = manager.buildStartCommand();
        } else {
            BridgeManager.Status status = readStatus(manager);
            command = status.running() ? manager.buildStopCommand() : manager.buildStartCommand();
        }
        if (command == null) {
            showNotification("找不到对应 ABI 的手柄桥程序", false);
            return;
        }
        String result = manager.execSync(command);
        sleep(1200);
        BridgeManager.Status status = readStatus(manager);
        if (status.running() && manager.isCloseOverlay()) {
            manager.execSync(manager.buildOverlayCommand());
        }
        showNotification(status.running()
                        ? "手柄桥已运行 · " + (status.physical.isEmpty() ? "等待手柄" : status.physical)
                        : "手柄桥已停止",
                status.running());
        if (result.startsWith("ERROR:")) showNotification(result, false);
    }

    /** Wait for Shizuku to become available, then make sure the bridge is up. */
    private void restoreAfterBoot(BridgeManager manager) throws IOException {
        for (int attempt = 0; attempt < RETRY_COUNT; ++attempt) {
            manager.connect();
            BridgeManager.Status status = readStatus(manager);
            if (!status.running() && manager.isShizukuReady()) {
                String start = manager.buildStartCommand();
                if (start != null) manager.execSync(start);
                sleep(1500);
                status = readStatus(manager);
                if (status.running() && manager.isCloseOverlay()) {
                    manager.execSync(manager.buildOverlayCommand());
                }
            }
            if (status.running()) {
                showNotification("手柄桥已自动恢复 · "
                        + (status.physical.isEmpty() ? "等待手柄" : status.physical), true);
                return;
            }
            showNotification("等待 Shizuku 启动并恢复手柄桥…", false);
            sleep(RETRY_INTERVAL);
        }
        showNotification("未能自动恢复：请在 Shizuku 启动后打开应用手动点一次", false);
    }

    private BridgeManager.Status readStatus(BridgeManager manager) {
        return manager.parseStatus(manager.execSync(manager.buildStatusCommand()));
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null || nm.getNotificationChannel(CHANNEL) != null) return;
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL, "手柄桥", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("手柄桥运行状态与快捷控制");
            nm.createNotificationChannel(channel);
        }
    }

    private PendingIntent actionIntent(String action, int requestCode) {
        Intent intent = new Intent(this, BridgeService.class).setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return PendingIntent.getForegroundService(this, requestCode, intent, flags);
        }
        return PendingIntent.getService(this, requestCode, intent, flags);
    }

    private Notification buildNotification(String text, boolean running) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        builder.setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("手柄桥")
                .setContentText(text)
                .setOngoing(false)
                .setOnlyAlertOnce(true)
                .setContentIntent(actionIntent(ACTION_TOGGLE, 1))
                .addAction(android.R.drawable.ic_media_play, "启动", actionIntent(ACTION_START, 2))
                .addAction(android.R.drawable.ic_media_pause, "停止", actionIntent(ACTION_STOP, 3));
        return builder.build();
    }

    private void showNotification(String text, boolean running) {
        lastText = text;
        lastRunning = running;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(text, running));
    }
}
