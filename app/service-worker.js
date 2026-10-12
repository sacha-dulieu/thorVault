const CACHE = "thorvault-v01222";
const SPRITE_CACHE = "thorvault-sprites-v1";
const FILES = ["/", "/manifest.webmanifest", "/icon.svg"];

self.addEventListener("install", event => {
  event.waitUntil(caches.open(CACHE).then(cache => cache.addAll(FILES)));
  self.skipWaiting();
});

self.addEventListener("activate", event => {
  event.waitUntil(
    caches.keys().then(keys =>
      Promise.all(keys.filter(key => ![CACHE, SPRITE_CACHE].includes(key)).map(key => caches.delete(key)))
    )
  );
  self.clients.claim();
});

self.addEventListener("fetch", event => {
  const requestUrl = new URL(event.request.url);
  if (requestUrl.pathname.startsWith("/api/")) return;

  if ((requestUrl.hostname === "raw.githubusercontent.com" &&
       requestUrl.pathname.includes("/PokeAPI/sprites/")) ||
      (requestUrl.hostname === "pokeapi.co" &&
       requestUrl.pathname.startsWith("/api/v2/move/"))) {
    event.respondWith(
      caches.open(SPRITE_CACHE).then(cache =>
        cache.match(event.request).then(cached =>
          cached || fetch(event.request).then(response => {
            cache.put(event.request, response.clone());
            return response;
          })
        )
      )
    );
    return;
  }

  if (event.request.mode === "navigate") {
    event.respondWith(
      fetch(event.request).then(response => {
        const clone = response.clone();
        caches.open(CACHE).then(cache => cache.put("/", clone));
        return response;
      }).catch(() => caches.match("/"))
    );
    return;
  }

  event.respondWith(
    caches.match(event.request).then(cached =>
      cached || fetch(event.request).then(response => {
        const clone = response.clone();
        caches.open(CACHE).then(cache => cache.put(event.request, clone));
        return response;
      })
    )
  );
});
