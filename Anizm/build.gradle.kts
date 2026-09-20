// SalooRepo §90 — Anizm modülü.
// Keşif kanıtı (canlı doğrulamalı, 2026-09-17): search=/searchAnime (GET, type=detailed),
// bölüm→/episode/<epId>/translator/<tid>, /video/<videoId> → /player/<videoId>,
// Beta Player → pl.puffytr.tr/watch/<hash> → /stream/<hash>/native.m3u8 (HLS master;
// gerçek kaliteler yalnız 480p/720p/1080p). Dizilla ile aynı modül kalıbı.

version = 1

cloudstream {
    authors = listOf("Saloo1575")
    language = "tr"
    description = "Anizm - Türkçe Altyazılı Anime (Beta Player HLS; §90: keşif canlı doğrulamalı)"

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     **/
    status = 1
    tvTypes = listOf("TvSeries")
    iconUrl = "https://www.google.com/s2/favicons?domain=anizm.com.tr&sz=%size%"
}
