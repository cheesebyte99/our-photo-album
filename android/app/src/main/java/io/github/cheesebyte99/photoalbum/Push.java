package io.github.cheesebyte99.photoalbum;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.google.firebase.messaging.FirebaseMessaging;

/**
 * 푸시 알림(FCM) 도우미 — 토큰 보관, 알림 채널, 알림 띄우기.
 * google-services.json 없이 빌드하면(BuildConfig.HAS_FCM=false) 아무 일도 하지 않는다.
 */
final class Push {
    static final String CHANNEL_ID = "album";
    private static final String PREFS = "push";
    private static final String KEY_TOKEN = "token";

    /** 토큰이 생기거나 바뀌면 화면(웹)에 알려 주는 통로 */
    interface TokenListener {
        void onToken(String token);
    }

    static volatile TokenListener listener;

    private Push() {
    }

    static boolean enabled() {
        return BuildConfig.HAS_FCM;
    }

    static String savedToken(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TOKEN, "");
    }

    static void saveToken(Context c, String token) {
        SharedPreferences.Editor e = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        e.putString(KEY_TOKEN, token == null ? "" : token).apply();
        TokenListener l = listener;
        if (l != null && token != null && !token.isEmpty()) l.onToken(token);
    }

    /** FCM 토큰을 새로 받아 저장한다(받으면 listener 로도 알림) */
    static void refreshToken(Context c) {
        if (!enabled()) return;
        final Context app = c.getApplicationContext();
        try {
            FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
                if (task.isSuccessful() && task.getResult() != null) saveToken(app, task.getResult());
            });
        } catch (RuntimeException ignored) {
            // Firebase 초기화 실패 등 — 알림 없이 계속
        }
    }

    static boolean hasPermission(Context c) {
        if (Build.VERSION.SDK_INT < 33) return NotificationManagerCompat.from(c).areNotificationsEnabled();
        return c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    static void createChannel(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                c.getString(R.string.channel_name), NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription(c.getString(R.string.channel_desc));
        nm.createNotificationChannel(ch);
    }

    /** 앱이 켜져 있을 때 온 알림을 직접 띄운다(꺼져 있을 때는 시스템이 띄움) */
    static void show(Context c, String title, String body, String room, String date, String tag) {
        if (!hasPermission(c)) return;
        createChannel(c);
        Intent open = new Intent(c, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (room != null) open.putExtra("room", room);
        if (date != null) open.putExtra("date", date);
        int id = (tag == null ? String.valueOf(System.currentTimeMillis()) : tag).hashCode();
        PendingIntent pi = PendingIntent.getActivity(c, id, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(0xFFC0704A)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pi);
        try {
            NotificationManagerCompat.from(c).notify(id, b.build());
        } catch (SecurityException ignored) {
            // 권한이 막 꺼진 경우
        }
    }
}
