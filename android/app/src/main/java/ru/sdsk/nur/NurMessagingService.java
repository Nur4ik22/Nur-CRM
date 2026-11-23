package ru.sdsk.nur;

import android.Manifest;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

public class NurMessagingService extends FirebaseMessagingService {
    @Override
    public void onNewToken(String token) {
        Push.forget(this);
    }

    @Override
    public void onMessageReceived(RemoteMessage message) {
        String title = message.getNotification() != null ? message.getNotification().getTitle() : message.getData().get("title");
        String body = message.getNotification() != null ? message.getNotification().getBody() : message.getData().get("body");
        String url = message.getData().get("url");
        if (title == null && body == null) return;
        if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_URL, url);
        int id = url != null ? url.hashCode() : (int) System.currentTimeMillis();
        PendingIntent pending = PendingIntent.getActivity(this, id, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "tickets")
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(ContextCompat.getColor(this, R.color.accent))
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pending);
        try {
            NotificationManagerCompat.from(this).notify(id, builder.build());
        } catch (SecurityException ignored) {
        }
    }
}
