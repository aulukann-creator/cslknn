#!/usr/bin/env python3
"""
Erişim analizi: bir sitenin hangi istek başlıklarını beklediğini otomatik bulur.

Tarayıcıda F12 → Ağ sekmesinde elle yapılan incelemenin otomatik hâlidir:
  1. Farklı başlık kombinasyonlarıyla (User-Agent, Referer, Origin, tarayıcı başlıkları) istek atar
  2. Çalışan en sade kombinasyonu seçer
  3. Çalışmıyorsa sebebini teşhis eder: bölge kısıtı, giriş gerekli, bot koruması, sunucu hatası

Bot koruması (Cloudflare doğrulaması, CAPTCHA vb.) tespit edilirse durur; aşmaya çalışmaz.

Kullanım:
  python3 tools/probe.py https://ornek.com/sayfa
"""
from __future__ import annotations

import sys
import urllib.parse
from dataclasses import dataclass, field

import requests

UA = ("Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36")
DESKTOP_UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
              "(KHTML, like Gecko) Chrome/131.0 Safari/537.36")

BOT_MARKERS = [
    "cf-chl", "challenge-platform", "just a moment", "checking your browser", "attention required",
    "cf-turnstile", "g-recaptcha", "h-captcha", "hcaptcha", "ddos-guard", "please enable javascript and cookies",
    "tarayıcınız kontrol ediliyor", "datadome", "perimeterx", "px-captcha", "incapsula", "_incapsula_",
    "sucuri website firewall", "access denied | ", "bot verification",
]
LOGIN_MARKERS = ["giriş yap", "oturum aç", "sign in", "log in", "login", "üye ol", "subscribe", "abone ol"]
REGION_MARKERS = ["bölgenizde", "ülkenizde", "not available in your country", "not available in your region",
                  "geo", "unavailable for legal reasons", "bulunduğunuz konum"]


@dataclass
class ProbeResult:
    ok: bool
    status: int | None
    headers: dict = field(default_factory=dict)  # tarife yazılacak başlıklar
    reason: str = ""                               # insan için açıklama
    kind: str = ""                                 # ok | bot | login | region | notfound | error
    tried: list = field(default_factory=list)      # (etiket, durum kodu)


def _origin(url: str) -> str:
    p = urllib.parse.urlparse(url)
    return f"{p.scheme}://{p.netloc}"


def _diagnose(r: requests.Response | None, err: Exception | None) -> tuple[str, str]:
    if err is not None:
        msg = str(err)
        if any(x in msg for x in ("NameResolution", "Name or service", "No address", "getaddrinfo")):
            return "error", "Alan adı bulunamadı; site kapanmış ya da adres yanlış olabilir."
        if "SSL" in msg:
            return "error", "Güvenli bağlantı (SSL) kurulamadı."
        return "error", f"Siteye bağlanılamadı: {msg[:160]}"
    body = (r.text or "")[:20000].lower()
    hdrs = {k.lower(): v.lower() for k, v in r.headers.items()}
    if hdrs.get("cf-mitigated") == "challenge" or any(m in body for m in BOT_MARKERS):
        return "bot", ("Site bot koruması kullanıyor (Cloudflare doğrulaması, CAPTCHA vb.). "
                       "Otomatik erişime bilerek kapalı; bu site eklenmez.")
    if r.status_code == 451 or any(m in body for m in REGION_MARKERS[:4]):
        return "region", "Bölge kısıtlaması: içerik bulunduğunuz ülkeye kapalı."
    if r.status_code == 401 or (r.status_code == 403 and any(m in body for m in LOGIN_MARKERS)):
        return "login", "Site giriş/üyelik istiyor. Motor şu an giriş gerektiren siteleri desteklemiyor."
    if r.status_code == 404:
        return "notfound", "Sayfa bulunamadı (404). Adresi kontrol edin."
    if r.status_code == 429:
        return "error", "Site çok sık istek yapıldığını söylüyor (429). Biraz bekleyip tekrar deneyin."
    if r.status_code >= 500:
        return "error", f"Sitenin sunucusu hata veriyor ({r.status_code})."
    if r.status_code == 403:
        return "error", "Site erişimi reddetti (403) ve hiçbir standart başlık kombinasyonu işe yaramadı."
    return "error", f"Beklenmeyen yanıt: HTTP {r.status_code}"


def _variants(url: str, referer: str | None) -> list[tuple[str, dict]]:
    o = _origin(url)
    ref = referer or (o + "/")
    browser = {
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language": "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Sec-Fetch-Dest": "document", "Sec-Fetch-Mode": "navigate", "Sec-Fetch-Site": "same-origin",
        "Upgrade-Insecure-Requests": "1",
    }
    # en sadeden en kapsamlıya: ilk çalışan seçilir
    return [
        ("sade", {"User-Agent": UA}),
        ("referer", {"User-Agent": UA, "Referer": ref}),
        ("referer+origin", {"User-Agent": UA, "Referer": ref, "Origin": o}),
        ("masaüstü tarayıcı", {"User-Agent": DESKTOP_UA, **browser, "Referer": ref}),
        ("mobil tarayıcı", {"User-Agent": UA, **browser, "Referer": ref, "Origin": o}),
    ]


def probe(url: str, referer: str | None = None, timeout: int = 20) -> ProbeResult:
    tried = []
    last_r, last_err = None, None
    for label, headers in _variants(url, referer):
        try:
            r = requests.get(url, headers=headers, timeout=timeout, allow_redirects=True)
            last_r, last_err = r, None
        except requests.RequestException as e:
            tried.append((label, "bağlantı hatası"))
            last_err = e
            if label == "sade":  # bağlantı kurulamıyorsa diğer denemeler de kurulamaz
                break
            continue
        tried.append((label, r.status_code))
        kind, _ = _diagnose(r, None) if r.status_code < 400 else ("", "")
        if r.status_code < 400 and kind != "bot":
            keep = {k: v for k, v in headers.items() if k in ("Referer", "Origin", "User-Agent")}
            if label == "sade":
                keep = {}
            elif keep.get("User-Agent") == UA:
                keep.pop("User-Agent")  # motor zaten bu kimliği kullanıyor
            if label in ("masaüstü tarayıcı", "mobil tarayıcı"):
                keep.update({k: v for k, v in headers.items() if k.startswith(("Accept", "Sec-", "Upgrade"))})
            return ProbeResult(True, r.status_code, keep,
                               "Erişim sorunsuz." if label == "sade" else f"Site şu başlıkları bekliyor: {', '.join(keep)}",
                               "ok", tried)
        # bu durumlarda başka kombinasyon denemek anlamsız
        k, _ = _diagnose(r, None)
        if k in ("bot", "region", "login", "notfound"):
            break
    kind, reason = _diagnose(last_r, last_err)
    return ProbeResult(False, last_r.status_code if last_r is not None else None, {}, reason, kind, tried)


def report(res: ProbeResult) -> str:
    lines = ["Erişim analizi:"]
    for label, code in res.tried:
        lines.append(f"  · {label:<18} → {code}")
    lines.append(("  ✓ " if res.ok else "  ✗ ") + res.reason)
    return "\n".join(lines)


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    res = probe(sys.argv[1])
    print(report(res))
    if res.headers:
        import json
        print("\nTarife eklenecek başlıklar:")
        print(json.dumps({"headers": res.headers}, ensure_ascii=False, indent=2))
    sys.exit(0 if res.ok else 1)
