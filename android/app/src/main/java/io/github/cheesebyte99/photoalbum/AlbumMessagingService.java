package io.github.cheesebyte99.photoalbum;

import androidx.annotation.NonNull;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Map;

/** FCM 메시지 받기 — 앱이 켜져 있을 때의 알림 표시와 토큰 갱신 */
public class AlbumMessagingService extends FirebaseMessagingService {

    @Override
    public void onNewToken(@NonNull String token) {
        Push.saveToken(this, token);
    }

    @Override
    public void onMessageReceived(@NonNull RemoteMessage message) {
        RemoteMessage.Notification n = message.getNotification();
        Map<String, String> data = message.getData();
        String title = n != null && n.getTitle() != null ? n.getTitle() : data.get("title");
        String body = n != null && n.getBody() != null ? n.getBody() : data.get("body");
        if (title == null && body == null) return;
        String tag = n != null ? n.getTag() : null;
        Push.show(this, title == null ? getString(R.string.app_name) : title, body == null ? "" : body,
                data.get("room"), data.get("date"), tag);
    }
}
