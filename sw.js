// Oila Nazorati — minimal service worker.
// Faqat PWA "o'rnatish mumkin" mezonini bajarish uchun; keshlash qilmaydi,
// har doim tarmoqdan yuklaydi (ma'lumotlar doim yangi bo'lishi kerak).
self.addEventListener('install', (e) => { self.skipWaiting(); });
self.addEventListener('activate', (e) => { self.clients.claim(); });
self.addEventListener('fetch', (e) => {
  e.respondWith(fetch(e.request).catch(() => caches.match(e.request)));
});
