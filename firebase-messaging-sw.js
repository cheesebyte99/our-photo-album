/* 우리 사진첩 — 웹 푸시 알림 받기 (아이폰 홈 화면 앱·안드로이드 크롬)
 * 앱이 닫혀 있을 때 온 알림을 Firebase 가 대신 띄워 주고, 누르면 그 앨범을 연다. */
importScripts('https://www.gstatic.com/firebasejs/10.14.1/firebase-app-compat.js');
importScripts('https://www.gstatic.com/firebasejs/10.14.1/firebase-messaging-compat.js');

firebase.initializeApp({
  apiKey: 'AIzaSyCXfKiu_ckkePfdPBOL8NkUNvpTD9DGisU',
  authDomain: 'jaeyeon-life.firebaseapp.com',
  projectId: 'jaeyeon-life',
  storageBucket: 'jaeyeon-life.firebasestorage.app',
  messagingSenderId: '1085807433548',
  appId: '1:1085807433548:web:36ae02c4b357e8ea23e1e9'
});

// 알림(notification) 메시지는 Firebase 가 자동으로 표시하고, 누르면 fcmOptions.link 를 연다.
firebase.messaging();

self.addEventListener('install', function () { self.skipWaiting(); });
self.addEventListener('activate', function (event) { event.waitUntil(self.clients.claim()); });
