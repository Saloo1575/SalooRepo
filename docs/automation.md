# SalooRepo Otomasyon Sistemi (v2)

Bu belge, SalooRepo'nun otomatik site yönetim hattını açıklar: keşif,
mükerrer koruma, aktiflik modeli ve CloudStream yayın politikası.

## 1. Bileşenler

| Bileşen | Görev |
|---|---|
| `scripts/site_registry.py` | Registry kütüphanesi: kayıt/arama/taşıma/probe (yalnızca stdlib) |
| `scripts/publish_list.py` | Registry-temelli plugins.json yayın filtresi |
| `scripts/test_registry.py` | Tüm mantığın offline testleri (`python3 scripts/test_registry.py`) |
| `scripts/discovery_sources.json` | Küratörlü keşif kaynak listesi |
| `.github/workflows/discovery.yml` | Aday keşfi + (gate'li) otomatik ekleme |
| `.github/workflows/health-check.yml` | ACTIVE/DEGRADED/INACTIVE durum takibi |
| `.github/workflows/domain-check.yml` | Domain taşınma/çakışma takibi |
| `.github/workflows/build.yml` | Gradle derleme + yayın listesi filtresi + builds branch |
| `.github/workflows/validate-repository.yml` | Şema + yayın politikası + test denetimi |

## 2. Mükerrer koruma (katmanlar)

1. **API katmanı** — `add_provider` / `add_alias`:
   `find_provider_by_domain` sırasıyla `canonical url` → `lastWorkingDomain`
   → `aliases` üzerinden registered-domain bazlı eşleşme yapar
   (`www.` / `m.` alt domainleri ve protokol/path farkları tek anahtara
   iner). Çakışmada `DuplicateSiteError` fırlatılır.
2. **Validasyon katmanı** — `validate_registry`: bir domain (`url`,
   `canonicalDomain`, `lastWorkingDomain`, `aliases` ve registered
   formları) iki farklı `siteId`'de bulunamaz; ihlalde CI fail eder.
3. **Domain taşınması** — `update_domain`: onaysız taşınma
   `UnconfirmedDomainChangeError` verir; onaylı taşınma AYNI kaydı
   günceller, eski domain alias olarak saklanır (1 → 1).
4. **Ayna (mirror) tespiti** — `find_provider_by_title`: farklı
   registered domain ama aynı içerik (`<title>` ↔ `titleSignature`
   eşleşmesi) tespit edilir; yeni kayıt açılmaz, `add_alias` ile mevcut
   kaynağa ikincil domain eklenir (birincil url korunur).

## 3. Aktiflik modeli

- `check_site`: önce canonical url, olmazsa `lastWorkingDomain` fallback;
  retry'lı probe. `2xx/3xx → active`, `HTTP hatası → degraded`,
  `erişilemiyor → inactive`.
- `apply_check_result`: status + lastError + lastWorkingDomain (probe'un
  ulaştığı final domain) + ilk başarılı `<title>` → titleSignature.
- INACTIVE kayıtlar registry'den ASLA silinmez; `enabled=false` elle
  kapatma dışında tüm geçişler otomatiktir (geri dönünce ACTIVE olur).
- `enabled: false` kayıtlar tüm kontrollerden atlanır.

## 4. CloudStream yayın politikası (publish_list.py)

`build.yml`, Gradle çıktısını (`build/plugins.json`) şu kurallarla
süzerek yayınlar:

1. `status: inactive` kayıtların eklentileri listeden DÜŞÜRÜLÜR (.cs3
   builds branch'te kalsa bile liste sunmaz).
2. `status: active|degraded` yayınlanır (sıkılaştırmak için
   `--statuses active`).
3. `enabled: false` kayıtlar hiç yayınlanmaz.
4. Eşleme: kaydın `internalNames` listesi ya da otomatik
   `siteId → CamelCase` ("internet-archive" → "InternetArchive").
   Eşleşmeyen (manuel) girdiler dokunulmadan geçer.
5. Ayna grupları (`titleSignature` aynı) arasında yalnızca en sağlıklı /
   en güncel sınanmış kayıt yayınlanır (active > degraded, sonra en yeni
   `lastChecked`).

`health-check` durum değişikliğini commit'lediğinde `build.yml`'i
tetikler; böylece INACTIVE'e düşen site birkaç dakika içinde listeden
kalkar. `validate-repository.yml` de committed `plugins.json` üzerinde
aynı politikayı `--check` ile zorlar.

## 5. Keşif (discovery.yml)

- Kaynaklar `scripts/discovery_sources.json`'dan okunur (bozuk/eksikse
  gömülü 3'lü yedek liste). Kaynak eklemek = gözden geçirilmiş bir commit
  (keşif kendi kendine kaynak eklemez).
- Adım zinciri: S1 aday → S2 domain normalize → S3/S4 duplicate
  (canonical/lastWorking/alias) → S5 domain-change sahipliği → S6 aktiflik
  probu → S6b ayna (title) kontrolü → S7 karar.
- `AUTO_ADD_NEW_SITES=false` iken çıktı yalnızca Issue raporudur; probe
  durumu, final domain ve ayna bilgisi rapora eklenir.
- `AUTO_ADD_NEW_SITES=true` yapıldığında: registry güncellemesi + scaffold
  + **commit/push adımı** (workflow içinde) + `build.yml` tetikleme
  çalışır. Scaffold yalnızca yer tutucu MainAPI üretir; gerçek kazıma
  kodu insan tarafından yazılmalıdır. Bu yüzden gate varsayılan kapalıdır.

### Sabit aday sisteminin sınırları ve dinamik keşif analizi

- Mevcut liste küratörlüdür: keşif "arama" değil, "aday değerlendirme"
  yapar. Kapsam, listeye eklenen kaynak kadar geniştir.
- Google araması gibi serbest tarama CI'da bot korumasına takılır ve
  telif riski taşır; önerilmez.
- Güvenilir dinamik genişleme seçenekleri:
  1. GitHub code-search / awesome-list gibi **küratörlü endeksler**
     (raw.githubusercontent üzerinden JSON/YAML okuma, whitelist domain
     kontrolüyle).
  2. Bilinen açık platform API'lerinin **katalog uçları** (ör. PeerTube
     instance listesi, Archive.org koleksiyon listesi).
  3. Topluluk Issue'ları (`source-discovery` etiketi) → aday havuzuna
     elle ekleme → otomatik değerlendirme zinciri.
  Her durumda: aday → normalize → duplicate → ayna → probe → insan
  onayı sırası değişmez; telif ihlali barındıran kaynaklar asla
  eklenmez.

## 6. Yerel geliştirme

- Python tarafı yalnızca stdlib kullanır (pip gerekmez). Testler:
  `python3 scripts/test_registry.py` (offline, deterministik).
- Gradle tarafı düşük RAM'e göre ayarlıdır (`gradle.properties`:
  `-Xmx768m`, daemon ve parallel kapalı). Lokal derleme:
  `gradlew --no-daemon make makePluginsJson`.
