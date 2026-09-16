// SalooRepo §74A — Dizilla modülü (iskelet).
// Sadece modül yapısı; scraper mantığı §74B'de (§72/§73 kanıtları üzerine).
// NOT: Root build script'i tüm provider modüllerine ortak cloudstream
// yapılandırmasını uyguladığı için burada yalnızca cloudstream bloğu var
// (DiziYou ile aynı kalıp).

version = 1

cloudstream {
    authors = listOf("Saloo1575")
    language = "tr"
    description = "Dizilla - Türkçe Dizi & Film (§74B: search+load canlı doğrulanmış akışla yazıldı; loadLinks §74C'de)"

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     **/
    status = 0
    tvTypes = listOf("TvSeries")
    iconUrl = "https://www.google.com/s2/favicons?domain=dizilla.now&sz=%size%"
}