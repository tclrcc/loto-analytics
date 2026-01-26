const CACHE_NAME = 'loto-master-v1';
const ASSETS_TO_CACHE = [
    '/',
    '/css/style.css',
    '/js/app.js',
    '/images/icons/icon-192x192.png',
    '/images/icons/icon-512x512.png',
    // Les librairies externes (Boostrap, etc.) sont gérées par le navigateur,
    // pas besoin de les mettre ici.
];

// Installation : Mise en cache des fichiers critiques
self.addEventListener('install', event => {
    event.waitUntil(
        caches.open(CACHE_NAME).then(cache => {
            return cache.addAll(ASSETS_TO_CACHE);
        })
    );
});

// Interception des requêtes : Réseau en priorité, Cache en secours
self.addEventListener('fetch', event => {
    event.respondWith(
        fetch(event.request).catch(() => {
            return caches.match(event.request);
        })
    );
});
