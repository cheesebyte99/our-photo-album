# 📸 우리 사진첩 · 데이트와 기념일

데이트한 날, 기념일의 사진을 날짜별로 모아 두는 둘만의 공유 앨범입니다. 웹앱과 안드로이드 앱(APK)이 같은 파일을 씁니다.

- 앱: https://cheesebyte99.github.io/our-photo-album/
- 공유 링크: `https://cheesebyte99.github.io/our-photo-album/?room=<방ID>`
- 데이터: 날짜 기록은 Firebase `jaeyeon-life` 프로젝트 `albums/{방ID}` (규칙·알림 서버는 [our-firebase](https://github.com/cheesebyte99/our-firebase)),
  사진 원본은 Cloudinary
- APK 받기: [Releases](https://github.com/cheesebyte99/our-photo-album/releases/latest) 의 `our-photo-album.apk`

> 예전 주소 `https://cheesebyte99.github.io/book-tracker/photo-album/` 는 이 주소로 자동으로 넘어갑니다(`?room=` 그대로).
> 주소의 도메인(`cheesebyte99.github.io`)이 같아서 이 기기에 저장된 앨범·설정은 그대로 이어집니다.

## 파일

| 파일 | 설명 |
| --- | --- |
| `index.html` | 사진첩 앱 본체 (HTML + CSS + JS 인라인) |
| `manifest.webmanifest`, `icon*.png`, `icon.svg`, `apple-touch-icon.png` | 홈 화면 아이콘·앱 정보 (`start_url` 없음 — 추가할 때 열린 앨범 주소로 열림) |
| `firebase-messaging-sw.js` | 웹·아이폰 홈 화면 앱의 푸시 알림 받기 |
| `.nojekyll` | GitHub Pages 가 그대로 서빙하도록 |
| `android/` | 안드로이드 앱(APK) 프로젝트 |
| `.github/workflows/android-apk.yml` | APK 자동 빌드·릴리스 |

## 💝 기념일

설정 → 💝 우리 기념일에서 💕 **연인이 된 날**(100일 단위·n주년, 그날이 1일째)과 💍 **결혼기념일**(결혼 n주년, 앞으로의 날이면 결혼식까지 D-n)을 넣으면
타임라인 맨 위 카드에 함께한 날수·결혼한 날수와 가장 가까운 기념일 하나가, 설정에는 다가오는 기념일 5개가 D-day 와 함께 보입니다.
함께 쓰는 앨범에서는 앨범 문서 `albums/{방ID}` 의 `since`·`wedding` 에 저장돼 두 사람이 같은 날을 씁니다.

## 🔔 알림

- **설정 → 🔔 알림**: 알림 받기 · 새 추억·사진 알림 · 기념일 알림(당일·하루 전·일주일 전) · 기념일 알림 시간(기본 09:00).
  함께 쓰는 앨범에서만 동작하며, 켜면 `albums/{방ID}/devices/{기기ID}` 에 등록합니다.
- 상대가 추억을 담거나 사진을 올리면 「○○님이 「제목」에 사진 3장을 올렸어요」. 저장할 때 `editedBy`·`editor`·`photoCount` 를 적어 두어 본인에게는 오지 않습니다.
- **받는 쪽**: 아이폰은 Safari → 공유 → 「홈 화면에 추가」로 연 앱(iOS 16.4 이상)에서 `firebase-messaging-sw.js` + VAPID 키로 받고,
  안드로이드 앱은 네이티브 FCM(`android/app/google-services.json`, Firebase 에 패키지 `io.github.cheesebyte99.photoalbum` 로 등록됨)으로 받습니다.
- 보내는 쪽(서버)은 [our-firebase](https://github.com/cheesebyte99/our-firebase) 의 `albumDayChanged`·`albumAnniversaries` 입니다.

## 📱 안드로이드 앱 (APK) — `android/`

맨 위의 웹 파일을 빌드 때 그대로 앱 안에 넣어 WebView 로 띄웁니다. 페이지는 `https://cheesebyte99.github.io/our-photo-album/` 주소로 열리되
앱 안의 파일로 바로 응답하므로, 인터넷 없이도 화면이 열리고 웹판과 같은 공유 앨범·Cloudinary 설정을 그대로 씁니다.

앱에서만 달라지는 점:
- **⤓ 원본 저장** → 휴대폰 `사진/우리사진첩` 폴더에 바로 저장
- 공유 링크 **복사** → 카카오톡 등으로 바로 보내는 공유 창
- 받은 공유 링크(새 주소·예전 주소 모두)를 누르거나 「공유 → 우리 사진첩」으로 보내면 그 앨범으로 바로 열림
- 사진 여러 장 한 번에 고르기, 뒤로 가기로 창 닫기·타임라인으로 돌아가기, 푸시 알림(네이티브 FCM)

```bash
cd android
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
```

GitHub Actions 가 push 때마다 APK 를 만들어 Actions 아티팩트(`our-photo-album-apk`)로 올리고, `main` 에서는 「우리 사진첩 APK」 릴리스(최신)로도 올립니다.
예전 저장소에서 1.0.11 까지 나갔으므로 이 저장소는 **2.0.N (versionCode 100+N)** 으로 이어 갑니다(덮어쓰기 업데이트 가능).
서명은 비밀값 `ANDROID_KEYSTORE_*` 가 있으면 그 키, 없으면 `android/debug.keystore` 입니다.

GitHub Pages 는 `main` 브랜치 루트를 그대로 서빙합니다(Settings → Pages → Deploy from a branch → `main` / `/ (root)`).
