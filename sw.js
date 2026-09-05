// Oila Nazorati — service worker.
// Panel family flow is loaded directly by panel.html; SW only adds the common notice.
self.addEventListener('install', (e) => { self.skipWaiting(); });
self.addEventListener('activate', (e) => { e.waitUntil(self.clients.claim()); });
self.addEventListener('fetch', (e) => {
  if (e.request.destination !== 'document') {
    e.respondWith(fetch(e.request).catch(() => caches.match(e.request)));
    return;
  }
  e.respondWith(
    fetch(e.request, {cache: 'no-store'}).then(async (res) => {
      const url = new URL(e.request.url);
      const isSitePage = url.pathname.endsWith('/index.html') || url.pathname.endsWith('/panel.html') || url.pathname === '/';
      if (!isSitePage || !res.ok) return res;
      const text = await res.text();
      let injected = text;
      if (!injected.includes('notice.css')) injected = injected.replace('</head>', '<link rel="stylesheet" href="notice.css"></head>');
      return new Response(injected, {status: res.status, statusText: res.statusText, headers: res.headers});
    }).catch(() => caches.match(e.request))
  );
});
