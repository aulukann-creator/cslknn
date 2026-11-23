<p align="center">
  <img src="icons/cslknn.png" width="120" alt="cslknn">
</p>

<h1 align="center">cslknn</h1>

<p align="center">
  Yasal ve açık kaynaklardan film, dizi, belgesel ve canlı yayınları tek yerde toplayan CloudStream eklenti deposu.
</p>

<p align="center">
  <a href="https://github.com/aulukann-creator/cslknn/actions/workflows/build.yml"><img src="https://github.com/aulukann-creator/cslknn/actions/workflows/build.yml/badge.svg" alt="Derleme"></a>
</p>

---

## Kurulum

1. [CloudStream](https://github.com/recloudstream/cloudstream/releases) uygulamasını kurun.
2. **Ayarlar → Eklentiler → Depo ekle** yolunu izleyin.
3. Ad olarak `cslknn` yazın, adres olarak şunu yapıştırın:

```
https://raw.githubusercontent.com/aulukann-creator/cslknn/main/repo.json
```

4. Depoyu açıp istediğiniz eklentileri indirin.
5. Ana sayfada sağ üstten sağlayıcıyı seçin.

## Eklentiler

| | Eklenti | İçerik | Durum |
|---|---|---|---|
| <img src="icons/internetarchive.png" width="32"> | **Internet Archive** | Kamu malı filmler, klasik TV dizileri, çizgi filmler, sessiz sinema | ✅ |

## İlke

Bu depodaki her eklenti yalnızca **hak sahibinin kendisinin yayınladığı** ya da **kamu malı / açık lisanslı** içeriklere bağlanır. Lisanssız yayın yapan sitelere yönelik eklenti eklenmez.

## Geliştirme

- Her klasör bir eklentidir. İçinde `build.gradle.kts` olan klasör otomatik olarak derlemeye katılır.
- `main` dalına yapılan her gönderimde GitHub Actions eklentileri derler ve `builds` dalına `.cs3` paketlerini ve `plugins.json` dosyasını yazar.
- Bir eklentiyi güncellediğinizde o eklentinin `build.gradle.kts` dosyasındaki `version` değerini bir artırın; CloudStream güncellemeyi buradan anlar.
