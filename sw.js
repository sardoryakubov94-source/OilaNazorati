// Oila Nazorati — service worker v2026.09.06d
const STYLE_VERSION='20260906d';
self.addEventListener('install',e=>{self.skipWaiting()});
self.addEventListener('activate',e=>{e.waitUntil(self.clients.claim())});
self.addEventListener('fetch',e=>{
  if(e.request.destination!=='document'){
    e.respondWith(fetch(e.request).catch(()=>caches.match(e.request)));
    return;
  }
  e.respondWith(
    fetch(e.request,{cache:'no-store'}).then(async res=>{
      const url=new URL(e.request.url);
      const isSitePage=url.pathname.endsWith('/index.html')||url.pathname.endsWith('/panel.html')||url.pathname==='/'||url.pathname.endsWith('/ios.html');
      if(!isSitePage||!res.ok)return res;
      let text=await res.text();
      if(!text.includes('notice.css')) text=text.replace('</head>','<link rel="stylesheet" href="notice.css?v='+STYLE_VERSION+'"></head>');
      if(url.pathname.endsWith('/panel.html')&&!text.includes('ios-panel.css')) text=text.replace('</head>','<link rel="stylesheet" href="ios-panel.css?v='+STYLE_VERSION+'"></head>');
      if(url.pathname.endsWith('/panel.html')&&!text.includes('screenshot-request.js')) text=text.replace('</body>','<script type="module" src="screenshot-request.js?v='+STYLE_VERSION+'"></script></body>');
      if(url.pathname.endsWith('/panel.html')&&!text.includes('mic-request.js')) text=text.replace('</body>','<script type="module" src="mic-request.js?v='+STYLE_VERSION+'"></script></body>');
      return new Response(text,{status:res.status,statusText:res.statusText,headers:res.headers});
    }).catch(()=>caches.match(e.request))
  );
});
