package com.sidemic;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * Mantém a captura viva quando o app sai da tela.
 *
 * A partir do Android 14 (API 34), gravar com o app em background exige um
 * foreground service declarado com o tipo `microphone` — sem ele o sistema
 * silencia o microfone assim que a activity para. O serviço não grava: ele
 * apenas segura a permissão de captura enquanto o MediaRecorder da activity
 * continua rodando, e mostra a notificação obrigatória.
 */
public class RecordingService extends Service {

    public static final String ACTION_START = "com.sidemic.service.START";
    public static final String ACTION_STOP = "com.sidemic.service.STOP";
    private static final String CHANNEL_ID = "sidemic_capture";
    private static final int NOTIFICATION_ID = 1;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        return START_STICKY;
    }

    private Notification buildNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Capture",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Shown while Sidemic is recording");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }

        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent stop = new Intent(this, RecordingService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Sidemic is recording")
                .setContentText("Tap to return to the app")
                .setSmallIcon(android.R.drawable.presence_audio_online)
                .setContentIntent(pi)
                .addAction(new Notification.Action.Builder(
                        Icon(), "Stop", stopPi).build())
                .setOngoing(true)
                .build();
    }

    private android.graphics.drawable.Icon Icon() {
        return android.graphics.drawable.Icon.createWithResource(
                this, android.R.drawable.ic_media_pause);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
