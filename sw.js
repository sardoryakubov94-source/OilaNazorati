// Oila Nazorati — service worker.
// Tarmoqdan yuklaydi va PWA paneliga kerakli yordamchi UI ni ulaydi.
self.addEventListener('install', (e) => { self.skipWaiting(); });
self.addEventListener('activate', (e) => { e.waitUntil(self.clients.claim()); });
self.addEventListener('fetch', (e) => {
  if (e.request.destination !== 'document') {
    e.respondWith(fetch(e.request).catch(() => caches.match(e.request)));
    return;
  }
  e.respondWith(
    fetch(e.request).then(async (res) => {
      const url = new URL(e.request.url);
      const isSitePage = url.pathname.endsWith('/index.html') || url.pathname.endsWith('/panel.html') || url.pathname === '/';
      if (!isSitePage || !res.ok) return res;
      const text = await res.text();
      let injected = text;
      if (!injected.includes('notice.css')) injected = injected.replace('</head>', '<link rel="stylesheet" href="notice.css"></head>');
      if (url.pathname.endsWith('/panel.html') && !injected.includes('pwa-family.js')) {
        injected = injected.replace('</head>', '<script type="module" src="pwa-family.js"></script></head>');
      }
      return new Response(injected, {status: res.status, statusText: res.statusText, headers: res.headers});
    }).catch(() => caches.match(e.request))
  );
});
