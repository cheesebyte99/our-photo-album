package io.github.cheesebyte99.photoalbum;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.webkit.WebViewAssetLoader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 우리 사진첩 — 저장소의 photo-album/ 을 앱 안에 넣어 WebView 로 띄운다.
 *
 * 페이지는 https://cheesebyte99.github.io/our-photo-album/ 주소로 열리지만,
 * 이 경로의 요청은 WebViewAssetLoader 가 앱 안의 파일로 바로 돌려준다(인터넷 없이도 화면이 열림).
 * 그래서 웹판과 출처(origin)가 같고, 앱에서 보낸 공유 링크를 상대가 브라우저로 열어도 같은 앨범에 들어간다.
 * 앨범 공유는 웹판과 똑같이 Firebase Firestore, 사진은 Cloudinary 를 그대로 쓴다.
 */
public class MainActivity extends Activity {

    static final String HOST = "cheesebyte99.github.io";
    static final String BASE_PATH = "/our-photo-album/";
    static final String BASE_URL = "https://" + HOST + BASE_PATH;

    private static final int REQ_FILE = 8;
    private static final int REQ_STORAGE = 9;
    private static final int REQ_NOTIFICATIONS = 7;
    private static final Pattern ROOM_IN_TEXT = Pattern.compile("[?&]room=([A-Za-z0-9-]{1,40})");

    private WebView webView;
    /** 사진 고르기(<input type=file multiple>) 결과를 웹에 돌려줄 통로 */
    private ValueCallback<Uri[]> fileCallback;
    private WebViewAssetLoader assetLoader;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // WebView 는 자기 padding 을 무시하고 화면 끝까지 그리므로, 바깥 틀에 여백을 준다.
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(getColor(R.color.app_bg));
        webView = new WebView(this);
        webView.setBackgroundColor(getColor(R.color.app_bg));
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        applyInsets(root);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // localStorage·IndexedDB: 앨범·설정 보관
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        // 고른 사진(앱 안 복사본, content://…files)을 웹 화면이 읽으려면 켜 둬야 한다.
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setUserAgentString(s.getUserAgentString() + " OurPhotoAlbumApp/1");

        assetLoader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler(BASE_PATH, new AlbumPathHandler(this))
                .build();

        webView.addJavascriptInterface(new Bridge(), "AndroidApp");
        webView.setWebChromeClient(new AlbumChrome());
        webView.setWebViewClient(new AlbumClient());

        Push.createChannel(this);
        Push.listener = token -> runOnUiThread(() -> sendTokenToPage(token));
        Push.refreshToken(this);
        // 혹시 페이지가 다운로드로 넘어가면(첨부 파일 응답 등) 직접 저장한다
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, length) ->
                saveImage(url, null));

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        }
        if (webView.getUrl() == null) {
            webView.loadUrl(urlForIntent(getIntent()));
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String room = roomFromIntent(intent);
        if (room != null) {
            webView.loadUrl(BASE_URL + "?room=" + room);
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FILE || fileCallback == null) return;
        Uri[] picked = null;
        if (resultCode == RESULT_OK && data != null) {
            ClipData clip = data.getClipData();
            if (clip != null && clip.getItemCount() > 0) {
                picked = new Uri[clip.getItemCount()];
                for (int i = 0; i < clip.getItemCount(); i++) picked[i] = clip.getItemAt(i).getUri();
            } else if (data.getData() != null) {
                picked = new Uri[]{data.getData()};
            }
        }
        final ValueCallback<Uri[]> callback = fileCallback;
        fileCallback = null;
        if (picked == null) {
            callback.onReceiveValue(null);
            return;
        }
        // 고른 사진을 앱 안으로 복사(HEIC 는 JPG 로)한 뒤 넘긴다 — 큰 사진도 있으니 바깥 스레드에서.
        final Uri[] chosen = picked;
        new Thread(() -> {
            Uri[] ready = PickedPhotos.copyAll(MainActivity.this, chosen);
            runOnUiThread(() -> callback.onReceiveValue(ready));
        }).start();
    }

    private final class AlbumChrome extends WebChromeClient {
        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = callback;
            PickedPhotos.clear(MainActivity.this);
            try {
                Intent pick = new Intent(Intent.ACTION_GET_CONTENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("image/*");
                if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }
                startActivityForResult(Intent.createChooser(pick, getString(R.string.pick_photos)), REQ_FILE);
                return true;
            } catch (ActivityNotFoundException e) {
                fileCallback = null;
                return false;
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_NOTIFICATIONS) return;
        boolean granted = Push.hasPermission(this);
        webView.evaluateJavascript("window.__onPushPermission && window.__onPushPermission('"
                + (granted ? "granted" : "denied") + "')", null);
        if (granted) {
            String t = Push.savedToken(this);
            if (!t.isEmpty()) sendTokenToPage(t); else Push.refreshToken(this);
        }
    }

    private void sendTokenToPage(String token) {
        if (webView == null || token == null || token.isEmpty() || !Push.hasPermission(this)) return;
        webView.evaluateJavascript("window.__onPushToken && window.__onPushToken('"
                + token.replace("\\", "").replace("'", "") + "')", null);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
    }

    @Override
    protected void onPause() {
        webView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        Push.listener = null;
        webView.destroy();
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        // 대화상자·크게 보기·추억 담기 창이 열려 있으면 닫고, 다른 탭이면 타임라인으로, 그다음에 앱을 나간다.
        webView.evaluateJavascript(
                "(function(){function open(id){var e=document.getElementById(id);"
                        + "return e&&!e.classList.contains('hidden');}"
                        + "if(open('dialog')||open('lightbox')||open('day-sheet')){"
                        + "document.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true}));return true;}"
                        + "if(!open('view-timeline')){var t=document.querySelector('.tab[data-view=\"timeline\"]');"
                        + "if(t){t.click();return true;}}"
                        + "return false;})()",
                value -> {
                    if (!"true".equals(value)) {
                        MainActivity.super.onBackPressed();
                    }
                });
    }

    /** 상태바·내비게이션 바·키보드 영역만큼 WebView 를 감싼 틀을 안쪽으로 밀어 넣는다(Android 15 전체 화면 대응). */
    private static void applyInsets(View view) {
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout()
                    | WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
    }

    private static String urlForIntent(Intent intent) {
        String room = roomFromIntent(intent);
        return room != null ? BASE_URL + "?room=" + room : BASE_URL;
    }

    /** 공유 링크(VIEW) 또는 공유받은 글(SEND)에서 방 ID 를 꺼낸다. */
    static String roomFromIntent(Intent intent) {
        if (intent == null) return null;
        String fromPush = intent.getStringExtra("room"); // 알림을 눌러 연 경우
        if (fromPush != null && fromPush.matches("[a-z0-9-]{1,40}")) return fromPush;
        String text = null;
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            text = intent.getData().toString();
        } else if (Intent.ACTION_SEND.equals(intent.getAction())) {
            text = intent.getStringExtra(Intent.EXTRA_TEXT);
        }
        if (text == null) return null;
        Matcher m = ROOM_IN_TEXT.matcher(text);
        if (m.find()) return m.group(1).toLowerCase();
        String bare = text.trim().toLowerCase();
        return bare.matches("[a-z0-9-]{1,40}") ? bare : null;
    }

    // ---------------------------------------------------------------- 사진 저장

    private void toast(int res) {
        runOnUiThread(() -> Toast.makeText(this, res, Toast.LENGTH_SHORT).show());
    }

    /** data: 주소나 Cloudinary 주소의 사진을 휴대폰 「사진/우리사진첩」 폴더에 저장한다. */
    void saveImage(String url, String name) {
        if (url == null || !(url.startsWith("data:image/") || url.startsWith("https://"))) return;
        if (Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            return; // 허락받은 뒤 다시 누르면 저장된다
        }
        toast(R.string.saving);
        new Thread(() -> {
            try {
                String mime;
                byte[] bytes;
                if (url.startsWith("data:")) {
                    int comma = url.indexOf(',');
                    mime = url.substring(5, url.indexOf(';') > 0 && url.indexOf(';') < comma ? url.indexOf(';') : comma);
                    bytes = Base64.decode(url.substring(comma + 1), Base64.DEFAULT);
                } else {
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(20000);
                    c.setReadTimeout(60000);
                    try (InputStream in = c.getInputStream()) {
                        mime = c.getContentType();
                        bytes = readAll(in);
                    } finally {
                        c.disconnect();
                    }
                }
                if (mime == null || !mime.startsWith("image/")) mime = "image/jpeg";
                mime = mime.split(";")[0].trim();
                String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
                if (ext == null) ext = "jpg";
                String file = (name != null && !name.isEmpty())
                        ? name.replaceAll("[\\\\/:*?\"<>|]", "_")
                        : "추억-" + System.currentTimeMillis() + "." + ext;
                writeToPictures(file, mime, bytes);
                toast(R.string.saved);
            } catch (Exception e) {
                toast(R.string.save_failed);
            }
        }).start();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    @SuppressWarnings("deprecation")
    private void writeToPictures(String file, String mime, byte[] bytes) throws IOException {
        String folder = "우리사진첩";
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver r = getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, file);
            v.put(MediaStore.Images.Media.MIME_TYPE, mime);
            v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + folder);
            v.put(MediaStore.Images.Media.IS_PENDING, 1);
            Uri uri = r.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IOException("insert failed");
            try (OutputStream out = r.openOutputStream(uri)) {
                if (out == null) throw new IOException("open failed");
                out.write(bytes);
            }
            v.clear();
            v.put(MediaStore.Images.Media.IS_PENDING, 0);
            r.update(uri, v, null, null);
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), folder);
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("mkdir failed");
            File f = new File(dir, file);
            try (OutputStream out = new FileOutputStream(f)) {
                out.write(bytes);
            }
            MediaScannerConnection.scanFile(this, new String[]{f.getAbsolutePath()}, new String[]{mime}, null);
        }
    }

    // ---------------------------------------------------------------- 웹 연결

    /** /our-photo-album/ 아래 요청을 앱 안의 www/ 파일로 돌려준다. */
    private static final class AlbumPathHandler implements WebViewAssetLoader.PathHandler {
        private final Context context;

        AlbumPathHandler(Context context) {
            this.context = context.getApplicationContext();
        }

        @Override
        public WebResourceResponse handle(@NonNull String path) {
            if (path.isEmpty()) path = "index.html";
            if (path.contains("..")) return null;
            String mime;
            if (path.endsWith(".html")) mime = "text/html";
            else if (path.endsWith(".svg")) mime = "image/svg+xml";
            else if (path.endsWith(".png")) mime = "image/png";
            else if (path.endsWith(".webmanifest")) mime = "application/manifest+json";
            else return null; // 그 밖의 경로는 실제 인터넷에서 받는다
            try {
                InputStream in = context.getAssets().open("www/" + path);
                return new WebResourceResponse(mime, "utf-8", in);
            } catch (IOException e) {
                return null;
            }
        }
    }

    private final class AlbumClient extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            return assetLoader.shouldInterceptRequest(request.getUrl());
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (HOST.equals(uri.getHost()) && uri.getPath() != null
                    && uri.getPath().startsWith(BASE_PATH.substring(0, BASE_PATH.length() - 1))) {
                return false; // 사진첩 안의 이동은 앱에서
            }
            String host = uri.getHost();
            if (host != null && host.endsWith("res.cloudinary.com")) {
                saveImage(uri.toString(), null); // 「원본 저장」 링크
                return true;
            }
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, uri)); // 그 밖의 링크는 브라우저로
            } catch (ActivityNotFoundException ignored) {
                // 열 앱이 없으면 무시
            }
            return true;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            view.evaluateJavascript(ENHANCE_JS, null);
        }
    }

    /**
     * 웹판(photo-album/index.html)은 그대로 두고, 앱에서만 다음을 덧붙인다.
     * - 「⤓ 원본 저장」: 브라우저 다운로드 대신 휴대폰 사진 폴더(사진/우리사진첩)에 바로 저장
     * - 공유 링크 「복사」 → 카카오톡 등으로 바로 보내는 안드로이드 공유 창
     * - 방 ID 칸에 공유 링크를 통째로 붙여 넣어도 방 ID 만 골라 들어가기(웹판도 지원하지만 한 번 더 보정)
     */
    private static final String ENHANCE_JS =
            "(function(){if(window.__ourAlbumApp)return;window.__ourAlbumApp=1;"
            + "var click=HTMLAnchorElement.prototype.click;"
            + "HTMLAnchorElement.prototype.click=function(){var h=this.href||'';"
            + "if(/^data:image\\//.test(h)||/^https:\\/\\/res\\.cloudinary\\.com\\//.test(h)){"
            + "AndroidApp.saveImage(h,this.download||'');return;}"
            + "return click.apply(this,arguments);};"
            + "var b=document.getElementById('btn-copy-link');"
            + "if(b){b.addEventListener('click',function(e){var l=document.getElementById('share-link');"
            + "var u=l&&l.value;if(!u)return;e.stopImmediatePropagation();e.preventDefault();"
            + "AndroidApp.share('📸 우리 사진첩에 같이 사진 모아요!\\n'+u);},true);}"
            + "})();";

    /** 페이지에서 window.AndroidApp 으로 부르는 기능. */
    private final class Bridge {
        /** 이 기기의 FCM 토큰(아직 없거나 알림을 못 쓰면 빈 문자열) */
        @JavascriptInterface
        public String getPushToken() {
            if (!Push.enabled()) return "";
            String t = Push.savedToken(MainActivity.this);
            if (t.isEmpty()) Push.refreshToken(MainActivity.this);
            return t;
        }

        /** 'granted' | 'default' | 'unavailable' */
        @JavascriptInterface
        public String pushPermission() {
            if (!Push.enabled()) return "unavailable";
            return Push.hasPermission(MainActivity.this) ? "granted" : "default";
        }

        @JavascriptInterface
        public void requestPushPermission() {
            runOnUiThread(() -> {
                if (Push.hasPermission(MainActivity.this)) {
                    onRequestPermissionsResult(REQ_NOTIFICATIONS, new String[0], new int[0]);
                } else if (Build.VERSION.SDK_INT >= 33) {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
                } else {
                    // Android 12 이하: 앱 알림이 꺼져 있으면 설정 화면으로
                    try {
                        startActivity(new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName()));
                    } catch (ActivityNotFoundException ignored) {
                        // 무시
                    }
                    onRequestPermissionsResult(REQ_NOTIFICATIONS, new String[0], new int[0]);
                }
            });
        }

        @JavascriptInterface
        public void share(String text) {
            runOnUiThread(() -> {
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_TEXT, text);
                startActivity(Intent.createChooser(send, getString(R.string.share_title)));
            });
        }

        @JavascriptInterface
        public void saveImage(String url, String name) {
            runOnUiThread(() -> MainActivity.this.saveImage(url, name));
        }
    }
}
