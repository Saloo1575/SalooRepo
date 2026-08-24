# SalooRepo

SalooRepo, CloudStream uyumlu eklentileri yayınlamak için hazırlanmış başlangıç deposudur.

> Bu depo şu anda yalnızca altyapıyı içerir; yayınlanmış bir provider bulunmaz.

## CloudStream'e ekleme

CloudStream uygulamasında **Ayarlar → Eklentiler → Depo ekle** yolunu açın ve aşağıdaki bağlantıyı girin:

```
https://raw.githubusercontent.com/Saloo1575/SalooRepo/main/repo.json
```

İlk eklenti yayınlandığında uygulama bu depodaki listeyi otomatik olarak okuyacaktır.

## İçerik ve güvenlik ilkeleri

- Yalnızca sahibi tarafından yetkilendirilmiş, açık lisanslı veya açıkça izin verilmiş kaynaklarla çalışan eklentiler eklenir.
- Telif hakkını ihlal eden içerik, izinsiz yayın bağlantısı, kimlik bilgisi, erişim anahtarı veya kullanıcı verisi depolanmaz ya da dağıtılmaz.
- Her eklentinin kaynak kodu, lisansı, bakım sorumlusu ve desteklediği kaynaklar açıkça belirtilir.
- Güvenlik sorunu veya hak ihlali bildirimi için GitHub üzerinden bir Issue açılmalıdır. İnceleme tamamlanana kadar ilgili eklenti devre dışı bırakılabilir.

## Depo dosyaları

- `repo.json`: CloudStream'in kullandığı depo tanımıdır.
- `plugins.json`: Yayınlanmış eklentilerin listesidir. Başlangıçta boştur.
- `LICENSE`: Depo kodunun lisansıdır; üçüncü taraf kaynakların veya içeriklerin lisansını kapsamaz.

## Eklenti yayınlama notu

Her yeni eklenti için derlenmiş `.cs3` dosyası güvenilir bir yayın bağlantısında barındırılmalı ve `plugins.json` içine sürüm, durum, bütünlük özeti (`fileHash`) ve gerekli tanımlayıcı bilgilerle eklenmelidir. Eklenti, yalnızca izinli bir kaynağı hedeflediği doğrulandıktan sonra listelenmelidir.

## Lisans

Bu depodaki özgün kaynak kodu [MIT Lisansı](LICENSE) ile sunulur.
