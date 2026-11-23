#!/usr/bin/env python3
"""
Kaynak Ekle - kalıp algılayıcı.

Bir adresi inceler, sitenin hangi kalıpla yayın yaptığını bulur ve sources/<id>.json tarifini üretir.
Ardından tarifi engine.py ile canlı olarak dener.

Algılanan kalıplar (öncelik sırasıyla):
  youtube    YouTube kanalı veya oynatma listesi  -> RSS + YouTube çözücüsü
  m3u        M3U / M3U8 kanal listesi              -> canlı kanallar
  feed       RSS / Atom video akışı                -> akıştaki videolar
  wordpress  WordPress sitesi (wp-json API)        -> yazılar/videolar + sayfadaki oynatıcı
  json       Doğrudan JSON API adresi              -> sezgisel alan eşleştirme
  next_data  Next.js sayfası (__NEXT_DATA__)       -> sezgisel alan eşleştirme
  html       Sıradan HTML sayfası                  -> tekrar eden kart yapısı + og etiketleri

Kullanım:
  python3 tools/detect.py <adres> [--name AD] [--kind film|dizi|belgesel|canli|cizgi|video] [--lang tr]
                                  [--id KIMLIK] [--out sources] [--dry]
"""
from __future__ import annotations

import argparse
import collections
import json
import os
import re
import sys
import urllib.parse

import requests
from bs4 import BeautifulSoup

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import engine  # noqa: E402

S = requests.Session()
S.headers["User-Agent"] = engine.UA
S.headers["Accept-Language"] = "tr-TR,tr;q=0.9,en;q=0.8"

KIND_MAP = {"film": "movie", "dizi": "series", "belgesel": "documentary", "canli": "live",
            "cizgi": "cartoon", "video": "video", "otomatik": None, "": None}
TYPE_FOR_KIND = {"movie": "Movie", "series": "TvSeries", "documentary": "Documentary", "live": "Live",
                 "cartoon": "Cartoon", "video": "Others"}

TITLE_KEYS = ["title", "name", "headline", "label", "baslik", "ad"]
IMAGE_HINTS = ["poster", "thumbnail", "thumb", "image", "img", "cover", "logo", "picture", "artwork", "featured"]
URL_KEYS = ["url", "link", "href", "permalink", "slug", "path", "id"]
MEDIA_KEYS = ["m3u8", "hls", "stream", "video", "media", "file", "src", "source", "contenturl", "embedurl", "url"]


def slugify(s: str) -> str:
    tr = str.maketrans("çğıöşüÇĞİÖŞÜ", "cgiosuCGIOSU")
    s = re.sub(r"[^a-z0-9]+", "-", s.translate(tr).lower()).strip("-")
    return s or "kaynak"


def origin(url: str) -> str:
    p = urllib.parse.urlparse(url)
    return f"{p.scheme}://{p.netloc}"


def get(url: str, **kw) -> requests.Response:
    r = S.get(url, timeout=25, **kw)
    return r


# ---------------------------------------------------------------- YouTube


def detect_youtube(url: str) -> dict | None:
    p = urllib.parse.urlparse(url)
    if not re.search(r"(^|\.)youtube\.com$|(^|\.)youtu\.be$", p.netloc):
        return None
    qs = urllib.parse.parse_qs(p.query)
    feed, title = None, None
    if "list" in qs:
        feed = f"https://www.youtube.com/feeds/videos.xml?playlist_id={qs['list'][0]}"
    else:
        m = re.search(r"/channel/(UC[\w-]{20,})", p.path)
        cid = m.group(1) if m else None
        if not cid:
            html = get(url, cookies={"CONSENT": "YES+1"}).text
            m = re.search(r'"(?:channelId|externalId)":"(UC[\w-]{20,})"', html) or \
                re.search(r'<link rel="canonical" href="https://www\.youtube\.com/channel/(UC[\w-]+)"', html)
            cid = m.group(1) if m else None
            t = re.search(r'<meta property="og:title" content="([^"]+)"', html)
            title = t.group(1) if t else None
        if not cid:
            raise RuntimeError("YouTube kanal kimliği bulunamadı")
        feed = f"https://www.youtube.com/feeds/videos.xml?channel_id={cid}"
    if not title:
        try:
            soup = BeautifulSoup(get(feed).text, "xml")
            title = soup.find("title").get_text(strip=True)
        except Exception:
            title = "YouTube"
    return {
        "_pattern": "youtube",
        "name": title,
        "mainUrl": "https://www.youtube.com",
        "types": ["Others"],
        "home": [{"title": "Son Videolar", "url": feed, "horizontal": True}],
        "list": {"mode": "xml", "items": "entry", "title": "title", "url": "link@href",
                 "poster": "media|thumbnail@url || group thumbnail@url",
                 "plot": "media|description || group description",
                 "year": "published | year", "kind": "'video'", "data": "link@href"},
        "search": {"local": True},
        "links": {"mode": "extractor", "referer": "https://www.youtube.com/"},
    }


# ---------------------------------------------------------------- M3U ve akışlar


def detect_m3u(url: str, text: str) -> dict | None:
    if "#EXTM3U" not in text[:200] or "#EXTINF" not in text:
        return None
    if "#EXT-X-STREAM-INF" in text or "#EXT-X-TARGETDURATION" in text:
        return None  # bu bir yayın akışı, kanal listesi değil
    entries = engine.parse_m3u(text)
    groups = [g for g, _ in collections.Counter(e["group"] for e in entries if e["group"]).most_common(12)]
    home = [{"title": g, "url": url, "list": {"items": f"[?group={g}]"}} for g in groups] or \
           [{"title": "Kanallar", "url": url}]
    return {
        "_pattern": "m3u",
        "mainUrl": origin(url),
        "types": ["Live"],
        "home": home,
        "list": {"mode": "m3u", "items": "[*]", "title": "name", "poster": "logo", "kind": "'live'", "data": "url"},
        "search": {"local": True},
        "links": {"mode": "direct"},
    }


def detect_feed(url: str, text: str) -> dict | None:
    head = text[:500].lower()
    if "<rss" not in head and "<feed" not in head:
        return None
    soup = BeautifulSoup(text, "xml")
    is_atom = soup.find("feed") is not None
    item = "entry" if is_atom else "item"
    first = soup.find(item)
    if not first:
        return None
    has_enclosure = first.find("enclosure") is not None
    has_media = first.find("content") is not None and first.find("content").get("url")
    data = "enclosure@url" if has_enclosure else "content@url" if has_media else ("link@href" if is_atom else "link")
    title = soup.find("title").get_text(strip=True) if soup.find("title") else origin(url)
    return {
        "_pattern": "feed",
        "name": title,
        "mainUrl": origin(url),
        "types": ["Others"],
        "home": [{"title": "Son Eklenenler", "url": url, "horizontal": True}],
        "list": {"mode": "xml", "items": item, "title": "title",
                 "poster": "thumbnail@url || image@href || image url || enclosure[type^=image]@url",
                 "plot": "description | text || summary | text",
                 "year": "pubDate | year || published | year", "kind": "'video'", "data": data},
        "search": {"local": True},
        "links": {"mode": "direct"},
    }


# ---------------------------------------------------------------- WordPress


def detect_wordpress(url: str, html: str) -> dict | None:
    base = origin(url)
    if "wp-json" not in html and "wp-content" not in html:
        return None
    try:
        types = get(f"{base}/wp-json/wp/v2/types").json()
    except Exception:
        return None
    if not isinstance(types, dict):
        return None
    # video içeren özel içerik türü varsa onu kullan
    rest = "posts"
    for key, t in types.items():
        if any(w in key.lower() for w in ("video", "movie", "film", "episode", "dizi", "program")):
            rest = t.get("rest_base") or key
            break
    api = f"{base}/wp-json/wp/v2/{rest}?per_page=24&page={{page}}&_embed=1"
    sample = get(api.replace("{page}", "1")).json()
    if not isinstance(sample, list) or not sample:
        return None
    s0 = sample[0]
    poster = "featured_image" if isinstance(s0.get("featured_image"), str) else \
        "_embedded.wp:featuredmedia[0].source_url"
    # özel alanlarda doğrudan video adresi var mı?
    direct = None
    for container in ("meta", "acf"):
        block = s0.get(container)
        if isinstance(block, dict):
            for k, v in block.items():
                if isinstance(v, str) and engine.MEDIA_RE.search(v):
                    direct = f"{container}.{k}"
                    break
        if direct:
            break
    # kategoriler -> ana sayfa satırları
    home = [{"title": "Yeni Eklenenler", "url": api, "horizontal": True}]
    try:
        tax = "categories"
        for key in ("topic", "genre", "category", "categories"):
            if key in s0 and isinstance(s0[key], list):
                tax = key
                break
        terms = get(f"{base}/wp-json/wp/v2/{tax}?per_page=20&orderby=count&order=desc").json()
        for t in terms[:10]:
            if t.get("count", 0) >= 4:
                home.append({"title": BeautifulSoup(t["name"], "html.parser").get_text(),
                             "url": api.replace("?", f"?{tax}={t['id']}&"), "horizontal": True})
    except Exception:
        pass
    name = BeautifulSoup(html, "html.parser").find("meta", property="og:site_name")
    cfg = {
        "_pattern": "wordpress",
        "name": name["content"] if name else urllib.parse.urlparse(url).netloc,
        "mainUrl": base,
        "types": ["Movie"],
        "home": home,
        "list": {"mode": "json", "items": "[*]", "title": "title.rendered | unescape | text",
                 "url": "_links.self[0].href", "poster": poster, "year": "date | year", "pageSize": 24},
        "search": {"url": api.replace("?", "?search={query}&")},
        "detail": {"mode": "json", "url": "{url}?_embed=1", "title": "title.rendered | unescape | text",
                   "plot": "excerpt.rendered | text || content.rendered | text",
                   "poster": poster, "background": poster, "year": "date | year",
                   "data": direct + " || link" if direct else "link"},
        "links": {"mode": "direct"} if direct else _html_links(),
    }
    return cfg


def _html_links() -> dict:
    """Bir sayfadaki oynatıcıyı bulan genel bağlantı tarifi."""
    return {
        "mode": "html",
        "items": "",
        "src": ("video source@src || video@src || meta[property=\"og:video:secure_url\"]@content || "
                "meta[property=\"og:video\"]@content || meta[property=\"og:video:url\"]@content || "
                "iframe[src*=youtube]@src || iframe[src*=vimeo]@src || iframe[src*=dailymotion]@src || "
                "iframe[allowfullscreen]@src || iframe@src"),
        "label": "'Oynatıcı'",
    }


# ---------------------------------------------------------------- JSON sezgileri


def _paths_of_arrays(v, path=""):
    if isinstance(v, list):
        if v and all(isinstance(x, dict) for x in v[:5]):
            yield path, v
        for i, x in enumerate(v[:3]):
            yield from _paths_of_arrays(x, f"{path}[{i}]")
    elif isinstance(v, dict):
        for k, x in v.items():
            if re.fullmatch(r"[\w-]+", str(k)):
                yield from _paths_of_arrays(x, f"{path}.{k}" if path else k)


def _find_key(obj: dict, keys: list[str], want=None, depth=0) -> str | None:
    """Nesnede istenen türde değeri olan anahtarın yolunu bulur."""
    if depth > 2 or not isinstance(obj, dict):
        return None
    for hint in keys:
        for k, v in obj.items():
            if hint in str(k).lower() and (want is None or want(v)):
                return k
    for k, v in obj.items():
        if isinstance(v, dict):
            sub = _find_key(v, keys, want, depth + 1)
            if sub:
                return f"{k}.{sub}"
        if isinstance(v, list) and v and isinstance(v[0], dict):
            sub = _find_key(v[0], keys, want, depth + 1)
            if sub:
                return f"{k}[0].{sub}"
    return None


def _is_str(v):
    return isinstance(v, str) and 0 < len(v) < 300


def _is_img(v):
    return isinstance(v, str) and (re.search(r"\.(jpe?g|png|webp|avif)(\?|$)", v, re.I) or "image" in v)


def _is_media(v):
    return isinstance(v, str) and bool(engine.MEDIA_RE.search(v))


def json_heuristic(doc) -> dict | None:
    best = None
    for path, arr in _paths_of_arrays(doc):
        sample = arr[0]
        tkey = _find_key(sample, TITLE_KEYS, _is_str)
        if not tkey:
            continue
        score = len(arr) + (20 if _find_key(sample, IMAGE_HINTS, _is_img) else 0) + \
            (30 if _find_key(sample, MEDIA_KEYS, _is_media) else 0)
        if not best or score > best[0]:
            best = (score, path, sample, tkey)
    if not best:
        return None
    _, path, sample, tkey = best
    spec = {"items": (path + "[*]") if path else "[*]", "title": tkey}
    img = _find_key(sample, IMAGE_HINTS, _is_img)
    if img:
        spec["poster"] = img
    media = _find_key(sample, MEDIA_KEYS, _is_media)
    if media:
        spec["data"] = media.replace("[0]", "[*]")  # birden fazla kaynak varsa hepsini al
    ukey = _find_key(sample, URL_KEYS, lambda v: isinstance(v, (str, int)))
    if ukey:
        spec["url"] = ukey
    return spec


def detect_json(url: str, text: str) -> dict | None:
    try:
        doc = json.loads(text)
    except Exception:
        return None
    spec = json_heuristic(doc)
    if not spec:
        return None
    return _from_heuristic("json", url, spec, url)


def detect_next_data(url: str, html: str) -> dict | None:
    soup = BeautifulSoup(html, "html.parser")
    s = soup.select_one("script#__NEXT_DATA__")
    if not s:
        return None
    doc = json.loads(s.string or s.get_text())
    spec = json_heuristic(doc)
    if not spec:
        return None
    return _from_heuristic("next_data", url, spec, url)


def _from_heuristic(mode: str, url: str, spec: dict, page_url: str) -> dict:
    base = origin(url)
    lst = {"mode": mode, **spec}
    cfg = {"_pattern": mode, "mainUrl": base, "types": ["Movie"],
           "home": [{"title": "Öne Çıkanlar", "url": page_url, "horizontal": True}],
           "list": lst, "search": {"local": True}}
    if "data" in spec:
        # oynatma adresi listede var: ayrı detay sayfasına gerek yok
        lst.pop("url", None)
        cfg["links"] = {"mode": "direct"}
    else:
        u = spec.get("url", "")
        lst["url"] = f"{u} | absurl"
        cfg["detail"] = _og_detail()
        cfg["links"] = _html_links()
    return cfg


# ---------------------------------------------------------------- HTML sezgileri


def _og_detail() -> dict:
    return {
        "mode": "html",
        "title": "meta[property=\"og:title\"]@content || h1 || title",
        "plot": "meta[property=\"og:description\"]@content || meta[name=\"description\"]@content",
        "poster": "meta[property=\"og:image\"]@content | absurl",
        "background": "meta[property=\"og:image\"]@content | absurl",
        "year": "meta[property=\"video:release_date\"]@content | year || time@datetime | year",
        "tags": "meta[property=\"video:tag\"]@content || meta[name=\"keywords\"]@content | split:,",
        "duration": "meta[property=\"video:duration\"]@content | sec2min",
    }


def _signature(el) -> str:
    cls = ".".join(sorted(el.get("class", [])[:2]))
    return f"{el.name}.{cls}" if cls else el.name


def detect_html(url: str, html: str) -> dict | None:
    soup = BeautifulSoup(html, "html.parser")
    # resim içeren bağlantıların ebeveyn yapısına göre gruplanması
    cards = collections.defaultdict(list)
    for a in soup.select("a[href]"):
        img = a.find("img")
        if not img:
            continue
        parent = a.parent
        for _ in range(3):  # kartın kendisi genelde bağlantının 0-2 üst seviyesidir
            if parent is None or parent.name in ("body", "html"):
                break
            sig = _signature(parent)
            if parent.get("class"):
                cards[sig].append(parent)
                break
            parent = parent.parent
    if not cards:
        return None
    sig, els = max(cards.items(), key=lambda kv: len(kv[1]))
    if len(els) < 4:
        return None
    tag, _, cls = sig.partition(".")
    item_sel = tag + ("." + ".".join(cls.split(".")) if cls else "")
    title = "img@alt || a@title || h2 || h3 || h4 || .title || a"
    poster = "img@data-src | absurl || img@data-lazy-src | absurl || img@srcset | regex:^(\\S+) | absurl || img@src | absurl"
    name = soup.find("meta", property="og:site_name")
    return {
        "_pattern": "html",
        "name": name["content"] if name else urllib.parse.urlparse(url).netloc,
        "mainUrl": origin(url),
        "types": ["Movie"],
        "home": [{"title": "Öne Çıkanlar", "url": url, "horizontal": True}],
        "list": {"mode": "html", "items": item_sel, "title": title, "url": "a@href | absurl", "poster": poster},
        "search": {"local": True},
        "detail": _og_detail(),
        "links": _html_links(),
    }


# ---------------------------------------------------------------- ana akış


def detect(url: str) -> dict:
    cfg = detect_youtube(url)
    if cfg:
        return cfg
    r = get(url)
    r.raise_for_status()
    text = r.text
    ctype = r.headers.get("content-type", "").lower()
    for fn in (detect_m3u, detect_feed):
        cfg = fn(url, text)
        if cfg:
            return cfg
    if "json" in ctype or text.lstrip()[:1] in "[{":
        cfg = detect_json(url, text)
        if cfg:
            return cfg
    for fn in (detect_wordpress, detect_next_data, detect_html):
        try:
            cfg = fn(url, text)
        except Exception as e:  # bir algılayıcının hatası diğerlerini engellemesin
            print(f"  ({fn.__name__} atlandı: {e})")
            cfg = None
        if cfg:
            return cfg
    raise RuntimeError("Bu sayfada tanınan bir yapı bulunamadı. Tarif elle yazılmalı.")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("url")
    ap.add_argument("--name", default="")
    ap.add_argument("--kind", default="otomatik")
    ap.add_argument("--lang", default="tr")
    ap.add_argument("--id", default="")
    ap.add_argument("--out", default="sources")
    ap.add_argument("--dry", action="store_true")
    a = ap.parse_args()

    url = a.url.strip()
    if not re.match(r"https?://", url):
        url = "https://" + url
    print(f"▶ İnceleniyor: {url}")
    try:
        cfg = detect(url)
    except Exception as e:
        print(f"✗ Kaynak algılanamadı: {e}")
        sys.exit(1)
    pattern = cfg.pop("_pattern")
    print(f"✓ Algılanan kalıp: {pattern}")

    name = a.name.strip() or cfg.get("name") or urllib.parse.urlparse(url).netloc
    sid = slugify(a.id or name)
    kind = KIND_MAP.get(a.kind.lower(), a.kind.lower() or None)
    if kind:
        cfg["types"] = [TYPE_FOR_KIND.get(kind, "Movie")]
        cfg.setdefault("list", {})["kind"] = f"'{kind}'"
        if cfg.get("detail"):
            cfg["detail"]["kind"] = f"'{kind}'"

    src = {"id": sid, "name": name, "enabled": True, "lang": a.lang,
           "description": f"{name} ({pattern} kalıbıyla otomatik eklendi)", **cfg}
    src["name"] = name
    path = os.path.join(a.out, f"{sid}.json")
    os.makedirs(a.out, exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(src, f, ensure_ascii=False, indent=2)
        f.write("\n")

    print(f"✓ Tarif yazıldı: {path}\n")
    ok = engine.test(path, verbose=True)
    if not ok:
        src["enabled"] = False
        with open(path, "w", encoding="utf-8") as f:
            json.dump(src, f, ensure_ascii=False, indent=2)
            f.write("\n")
        print("\n! Test tam geçmedi: kaynak KAPALI (enabled=false) olarak eklendi. Tarifi düzenleyip açabilirsiniz.")
    if a.dry:
        os.remove(path)
    gh_out = os.environ.get("GITHUB_OUTPUT")
    if gh_out:
        with open(gh_out, "a") as f:
            f.write(f"id={sid}\nname={name}\npattern={pattern}\nok={'true' if ok else 'false'}\npath={path}\n")


if __name__ == "__main__":
    main()
