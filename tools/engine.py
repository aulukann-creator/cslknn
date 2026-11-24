#!/usr/bin/env python3
"""
cslknn kaynak motoru - Python referans uygulaması.

Kotlin motoruyla (Kaynaklar eklentisi) aynı kuralları uygular. İki amaçla kullanılır:
  1. Kaynak tariflerini (sources/*.json) canlı siteye karşı test etmek
  2. "Kaynak Ekle" akışında üretilen tarifi commit'lemeden önce doğrulamak

Kullanım:
  python3 tools/engine.py test sources/nasa-plus.json      # tüm adımları dener
  python3 tools/engine.py test sources/nasa-plus.json -v   # ayrıntılı çıktı
"""
from __future__ import annotations

import html as htmlmod
import json
import re
import sys
import urllib.parse
from dataclasses import dataclass, field
from typing import Any

import requests
from bs4 import BeautifulSoup, Tag

UA = ("Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36")
MEDIA_RE = re.compile(r"\.(m3u8|mpd|mp4|webm|mkv|mov|ogv|ogg|m4v|ts)(\?|#|$)", re.I)

# ---------------------------------------------------------------- belge türleri


class JNode:
    """JSON tabanlı belge düğümü (json, next_data, ld_json, m3u modları)."""

    def __init__(self, value: Any, root: Any):
        self.value = value
        self.root = root


class HNode:
    """HTML/XML tabanlı belge düğümü (html, xml modları)."""

    def __init__(self, el: Tag, root: Tag):
        self.el = el
        self.root = root


# ---------------------------------------------------------------- JSON yolları

_TOKEN_RE = re.compile(r"([^.\[\]]*)((?:\[[^\]]*\])*)")


def _split_path(path: str) -> list[str]:
    """'a.b[0].c[?k=v]' -> ['a','b[0]','c[?k=v]'] (köşeli parantez içindeki noktaları korur)."""
    parts, buf, depth = [], "", 0
    for ch in path:
        if ch == "[":
            depth += 1
        elif ch == "]":
            depth -= 1
        if ch == "." and depth == 0:
            parts.append(buf)
            buf = ""
        else:
            buf += ch
    parts.append(buf)
    return [p for p in parts if p != ""]


def _as_text(v: Any) -> str | None:
    if v is None:
        return None
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, (int, float)):
        return str(int(v)) if isinstance(v, float) and v.is_integer() else str(v)
    if isinstance(v, str):
        return v
    return json.dumps(v, ensure_ascii=False, separators=(",", ":"))


def _child(values: list[Any], name: str) -> list[Any]:
    out = []
    for v in values:
        if isinstance(v, dict):
            if name in v:
                out.append(v[name])
        elif isinstance(v, list):
            if name.isdigit():
                i = int(name)
                if i < len(v):
                    out.append(v[i])
            else:  # dizilerde alan adı -> her elemana uygula
                out.extend(_child(v, name))
    return out


def _bracket(values: list[Any], expr: str) -> list[Any]:
    expr = expr.strip()
    out: list[Any] = []
    if expr == "*":
        for v in values:
            if isinstance(v, list):
                out.extend(v)
            elif isinstance(v, dict):
                out.extend(v.values())
        return out
    if expr.lstrip("-").isdigit():
        i = int(expr)
        for v in values:
            if isinstance(v, list) and -len(v) <= i < len(v):
                out.append(v[i])
        return out
    if expr.startswith("?"):
        m = re.match(r"\?\s*([^!=~]+?)\s*(!=|~|=)\s*(.*)$", expr)
        if not m:
            return out
        key, op, want = m.group(1), m.group(2), m.group(3).strip().strip("'\"")
        for v in values:
            items = v if isinstance(v, list) else [v]
            for it in items:
                got = _as_text(_json_path([it], key)[0]) if _json_path([it], key) else None
                ok = (got == want) if op == "=" else (got != want) if op == "!=" else (
                    got is not None and want.lower() in got.lower())
                if ok:
                    out.append(it)
        return out
    return _child(values, expr.strip("'\""))


def _json_path(values: list[Any], path: str) -> list[Any]:
    cur = values
    for part in _split_path(path):
        m = _TOKEN_RE.fullmatch(part)
        if not m:
            return []
        name, brackets = m.group(1), m.group(2)
        if name:
            cur = _child(cur, name)
        for b in re.findall(r"\[([^\]]*)\]", brackets):
            cur = _bracket(cur, b)
    return cur


# ---------------------------------------------------------------- seçiciler


def _html_select(node: HNode, sel: str) -> list[str]:
    css, _, attr = sel.partition("@")
    css = css.strip()
    attr = attr.strip() or "text"
    els = [node.el] if css == "" else node.el.select(css)
    out = []
    for el in els:
        if attr == "text":
            out.append(el.get_text(" ", strip=True))
        elif attr == "html":
            out.append(el.decode_contents())
        elif attr == "outer":
            out.append(str(el))
        elif attr == "own":
            out.append("".join(t for t in el.find_all(string=True, recursive=False)).strip())
        else:
            v = el.get(attr)
            if isinstance(v, list):
                v = " ".join(v)
            if v is not None:
                out.append(v)
    return out


def raw_select(node: JNode | HNode, sel: str) -> list[str]:
    sel = sel.strip()
    if sel.startswith("'") and sel.endswith("'") and len(sel) >= 2:
        return [sel[1:-1]]
    if isinstance(node, JNode):
        if sel in ("", "$"):
            vals = [node.value]
        elif sel.startswith("^"):
            vals = _json_path([node.root], sel[1:].lstrip("."))
        else:
            vals = _json_path([node.value], sel)
        return [t for t in (_as_text(v) for v in vals) if t is not None]
    if sel.startswith("^"):
        return _html_select(HNode(node.root, node.root), sel[1:])
    return _html_select(node, sel)


def _split_pipes(spec: str) -> list[str]:
    """' | ' ile ayır; tırnak içindeki ve regex içindeki tekil | karakterlerine dokunma."""
    return [p.strip() for p in re.split(r"\s+\|\s+", spec)]


# ---------------------------------------------------------------- dönüşümler


def _to_min_hms(v: str) -> str | None:
    parts = [p for p in v.strip().split(":")]
    try:
        nums = [float(p) for p in parts]
    except ValueError:
        return None
    if len(nums) == 3:
        return str(int(nums[0] * 60 + nums[1]))
    if len(nums) == 2:
        return str(int(nums[0]))
    return None


def _iso_min(v: str) -> str | None:
    m = re.match(r"P(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?(?:([\d.]+)S)?", v.strip(), re.I)
    if not m or not any(m.groups()):
        return None
    d, h, mi, s = (float(x) if x else 0 for x in m.groups())
    return str(int(d * 1440 + h * 60 + mi + s / 60))


def apply_transforms(values: list[str], steps: list[str], base: str) -> list[str]:
    for step in steps:
        name, _, arg = step.partition(":")
        name = name.strip().lower()
        if name == "first":
            values = values[:1]
        elif name == "last":
            values = values[-1:]
        elif name == "join":
            values = [arg.join(values)] if values else []
        elif name == "split":
            values = [p.strip() for v in values for p in v.split(arg or ",") if p.strip()]
        elif name == "default":
            values = values or [arg]
        elif name == "unique":
            seen, out = set(), []
            for v in values:
                if v not in seen:
                    seen.add(v)
                    out.append(v)
            values = out
        else:
            out = []
            for v in values:
                r = _transform_one(name, arg, v, base)
                if r is not None and r != "":
                    out.append(r)
            values = out
    return values


def _transform_one(name: str, arg: str, v: str, base: str) -> str | None:
    if name == "absurl":
        if not v:
            return None
        return urllib.parse.urljoin(base, v.strip())
    if name == "text":
        return BeautifulSoup(v, "html.parser").get_text(" ", strip=True)
    if name == "unescape":
        return htmlmod.unescape(v)
    if name == "trim":
        return v.strip()
    if name == "int":
        m = re.search(r"-?\d+", v.replace(",", "").replace(".", "") if re.fullmatch(r"[\d.,]+", v) else v)
        return m.group(0) if m else None
    if name == "year":
        m = re.search(r"(18|19|20)\d{2}", v)
        return m.group(0) if m else None
    if name == "regex":
        m = re.search(arg, v)
        if not m:
            return None
        return m.group(1) if m.groups() else m.group(0)
    if name == "replace":
        a, _, b = arg.partition("=>")
        return re.sub(a, b, v)
    if name == "fmt":
        return arg.replace("{}", v)
    if name == "prefix":
        return arg + v
    if name == "suffix":
        return v + arg
    if name == "sec2min":
        try:
            return str(int(float(v) / 60))
        except ValueError:
            return None
    if name == "hms2min":
        return _to_min_hms(v)
    if name == "iso2min":
        return _iso_min(v)
    if name == "lower":
        return v.lower()
    if name == "upper":
        return v.upper()
    if name == "urlencode":
        return urllib.parse.quote(v, safe="")
    if name == "urldecode":
        return urllib.parse.unquote(v)
    raise ValueError(f"Bilinmeyen dönüşüm: {name}")


def select(node: JNode | HNode, spec: str | None, base: str) -> list[str]:
    if not spec:
        return []
    alts = [a for a in re.split(r"\s+\|\|\s+", spec)]  # 'a || b' -> a boşsa b
    for alt in alts:
        parts = _split_pipes(alt)
        vals = [v for v in raw_select(node, parts[0]) if v.strip()]
        vals = apply_transforms(vals, parts[1:], base)
        if vals:
            return vals
    return []


def select_one(node, spec, base) -> str | None:
    v = select(node, spec, base)
    return v[0] if v else None


# ---------------------------------------------------------------- belge yükleme


def parse_m3u(text: str) -> list[dict]:
    out, cur = [], None
    for line in text.splitlines():
        line = line.strip()
        if line.startswith("#EXTINF"):
            attrs = dict(re.findall(r'([\w-]+)="([^"]*)"', line))
            name = line.rsplit(",", 1)[-1].strip() if "," in line else ""
            cur = {"name": name, "logo": attrs.get("tvg-logo", ""), "group": attrs.get("group-title", ""),
                   "id": attrs.get("tvg-id", ""), "attrs": attrs}
        elif line and not line.startswith("#") and cur is not None:
            cur["url"] = line
            out.append(cur)
            cur = None
    return out


def parse_doc(text: str, mode: str, url: str, extract: str | None = None):
    if extract:
        m = re.search(extract, text, re.S)
        if not m:
            raise ValueError("extract deseni eşleşmedi")
        text = m.group(1) if m.groups() else m.group(0)
    mode = (mode or "json").lower()
    if mode == "json":
        d = json.loads(text)
        return JNode(d, d)
    if mode == "next_data":
        soup = BeautifulSoup(text, "html.parser")
        s = soup.select_one("script#__NEXT_DATA__")
        if not s:
            raise ValueError("__NEXT_DATA__ bulunamadı")
        d = json.loads(s.string or s.get_text())
        return JNode(d, d)
    if mode == "ld_json":
        soup = BeautifulSoup(text, "html.parser")
        arr = []
        for s in soup.select('script[type="application/ld+json"]'):
            try:
                v = json.loads(s.string or s.get_text())
            except Exception:
                continue
            for x in (v if isinstance(v, list) else [v]):
                arr.extend(x["@graph"] if isinstance(x, dict) and "@graph" in x else [x])
        return JNode(arr, arr)
    if mode == "m3u":
        d = parse_m3u(text)
        return JNode(d, d)
    if mode == "html":
        soup = BeautifulSoup(text, "html.parser")
        return HNode(soup, soup)
    if mode == "xml":
        soup = BeautifulSoup(text, "xml")
        return HNode(soup, soup)
    raise ValueError(f"Bilinmeyen mod: {mode}")


def items_of(doc, spec: str | None) -> list:
    if isinstance(doc, JNode):
        if not spec or spec in ("$",):
            vals = doc.value if isinstance(doc.value, list) else [doc.value]
        else:
            vals = _json_path([doc.value], spec)
            if len(vals) == 1 and isinstance(vals[0], list) and not spec.endswith("]"):
                vals = vals[0]
        return [JNode(v, doc.root) for v in vals]
    els = doc.el.select(spec) if spec else [doc.el]
    return [HNode(e, doc.root) for e in els]


# ---------------------------------------------------------------- istekler


def fill(template: str, **vars) -> str:
    out = template
    for k, v in vars.items():
        if v is None:
            continue
        out = out.replace("{" + k + "}", str(v))
    return out


class Fetcher:
    def __init__(self, src: dict):
        self.src = src
        self.s = requests.Session()
        self.s.headers["User-Agent"] = src.get("userAgent") or UA
        self.s.headers.update(src.get("headers") or {})

    def get(self, url: str, spec: dict | None = None) -> str:
        spec = spec or {}
        method = (spec.get("method") or "GET").upper()
        headers = spec.get("headers") or {}
        if method == "POST":
            r = self.s.post(url, data=spec.get("body"), headers=headers, timeout=25)
        else:
            r = self.s.get(url, headers=headers, timeout=25)
        r.raise_for_status()
        return r.text


# ---------------------------------------------------------------- motor


@dataclass
class Item:
    title: str
    url: str
    poster: str | None = None
    year: str | None = None
    kind: str | None = None


class Engine:
    def __init__(self, src: dict):
        self.src = src
        self.base = src["mainUrl"]
        self.f = Fetcher(src)

    def _list(self, spec: dict, url: str, page: int, query: str | None = None) -> tuple[list[Item], bool]:
        size = spec.get("pageSize") or 0
        u = fill(url, page=page, page0=page - 1, offset=(page - 1) * size,
                 query=urllib.parse.quote(query) if query else None, mainUrl=self.base)
        doc = parse_doc(self.f.get(u, spec), spec.get("mode", "json"), u, spec.get("extract"))
        out = []
        for it in items_of(doc, spec.get("items")):
            title = select_one(it, spec.get("title"), u)
            if not title:
                continue
            data_vals = select(it, spec.get("data"), u)
            link = select_one(it, spec.get("url"), u)
            if not self.src.get("detail"):
                # detay sayfası yok: öğe kendi bilgisini taşır; oynatılacak veri yoksa atlanır
                if not data_vals:
                    continue
                link = json.dumps({
                    "title": title,
                    "poster": select_one(it, spec.get("poster"), u),
                    "plot": select_one(it, spec.get("plot"), u),
                    "kind": select_one(it, spec.get("kind"), u),
                    "data": data_vals if len(data_vals) != 1 else data_vals[0],
                }, ensure_ascii=False)
            if not link:
                continue
            out.append(Item(title=title, url=link, poster=select_one(it, spec.get("poster"), u),
                            year=select_one(it, spec.get("year"), u), kind=select_one(it, spec.get("kind"), u)))
        if spec.get("next"):
            has_next = bool(select(doc, spec["next"], u))
        elif size:
            has_next = len(out) >= size
        else:
            has_next = bool(out) and ("{page" in url or "{offset" in url)
        return out, has_next

    def home(self, section: dict, page: int = 1):
        spec = {**self.src.get("list", {}), **(section.get("list") or {})}
        return self._list(spec, section["url"], page)

    def search(self, query: str, page: int = 1):
        s = self.src.get("search")
        if not s:
            return [], False
        if s.get("local"):
            q = query.lower()
            res = []
            for sec in self.src.get("home", []):
                items, _ = self.home(sec, 1)
                res += [i for i in items if q in i.title.lower() and i.url not in {r.url for r in res}]
            return res, False
        spec = {**self.src.get("list", {}), **(s.get("list") or {})}
        return self._list(spec, s["url"], page, query)

    def load(self, url: str) -> dict:
        det = self.src.get("detail")
        if not det:
            d = json.loads(url)
            return {"kind": d.get("kind") or "movie", "title": d["title"], "poster": d.get("poster"),
                    "plot": d.get("plot"), "data": d.get("data"), "episodes": []}
        u = fill(det.get("url") or "{url}", url=url, mainUrl=self.base)
        doc = parse_doc(self.f.get(u, det), det.get("mode", "json"), u, det.get("extract"))
        root = items_of(doc, det.get("root"))[0] if det.get("root") else doc
        g = lambda k: select_one(root, det.get(k), u)
        res = {
            "title": g("title"), "plot": g("plot"), "poster": g("poster"), "background": g("background"),
            "year": g("year"), "duration": g("duration"), "rating": g("rating"),
            "tags": select(root, det.get("tags"), u), "kind": g("kind") or "movie",
            "data": None, "episodes": [],
        }
        ep = det.get("episodes")
        if ep:
            for e in items_of(root, ep.get("items")):
                data = select(e, ep.get("data"), u)
                if not data:
                    continue
                res["episodes"].append({
                    "name": select_one(e, ep.get("name"), u), "season": select_one(e, ep.get("season"), u),
                    "episode": select_one(e, ep.get("episode"), u), "data": data[0] if len(data) == 1 else data,
                })
            if res["episodes"]:
                res["kind"] = "series"
        if not res["episodes"]:
            data = select(root, det.get("data"), u) if det.get("data") else [url]
            res["data"] = data[0] if len(data) == 1 else data
        return res

    def links(self, data) -> list[dict]:
        ln = self.src.get("links") or {"mode": "direct"}
        mode = ln.get("mode", "direct")
        datas = data if isinstance(data, list) else [data]
        out = []
        forced = (ln.get("type") or "auto").lower()
        if mode in ("direct", "extractor"):
            for d in datas:
                if mode == "extractor" or forced == "extractor":
                    kind = "extractor"
                elif forced in ("hls", "dash", "video") or MEDIA_RE.search(d or ""):
                    kind = "media"
                else:
                    kind = "extractor"
                out.append({"url": d, "via": kind})
            return out
        for d in datas:
            u = fill(ln.get("url") or "{data}", data=d, mainUrl=self.base)
            doc = parse_doc(self.f.get(u, ln), mode, u, ln.get("extract"))
            for it in items_of(doc, ln.get("items")):
                for src in select(it, ln.get("src"), u):
                    out.append({"url": src, "label": select_one(it, ln.get("label"), u),
                                "via": "media" if MEDIA_RE.search(src) else "extractor"})
        return out


# ---------------------------------------------------------------- test aracı


def test(path: str, verbose: bool = False) -> bool:
    with open(path, encoding="utf-8") as f:
        src = json.load(f)
    eng = Engine(src)
    ok = bool(src.get("home"))
    if not ok:
        print("  ✗ Ana sayfa bölümü tanımlanmamış.")
    print(f"■ {src['name']} ({src['id']})")
    first_item = None
    for sec in src.get("home", []):
        try:
            items, nxt = eng.home(sec, 1)
            print(f"  ✓ Ana sayfa  {sec['title']:<28} {len(items):>3} öğe  sonraki={nxt}")
            if verbose:
                for i in items[:3]:
                    print(f"      - {i.title} | {i.poster} | {i.url[:90]}")
            first_item = first_item or (items[0] if items else None)
            if not items:
                ok = False
        except Exception as e:
            ok = False
            print(f"  ✗ Ana sayfa  {sec['title']}: {e}")
    if src.get("search"):
        q = src["search"].get("test") or (first_item.title.split()[0] if first_item else "a")
        try:
            items, _ = eng.search(q)
            print(f"  ✓ Arama      '{q}' -> {len(items)} sonuç")
        except Exception as e:
            ok = False
            print(f"  ✗ Arama: {e}")
    if first_item:
        try:
            d = eng.load(first_item.url)
            if not d.get("title"):
                raise ValueError("Detay başlığı bulunamadı; tarif doğrulanamadı.")
            if d.get("kind") in ("series", "tvseries", "dizi") and not d["episodes"]:
                raise ValueError("Dizi bulundu ancak sezon/bölüm listesi okunamadı. Bölüm tarifi gerekli.")
            print(f"  ✓ Detay      {d['title']} [{d['kind']}] süre={d.get('duration')} yıl={d.get('year')} "
                  f"bölüm={len(d['episodes'])}")
            if verbose:
                print(f"      plot: {(d.get('plot') or '')[:120]}")
                print(f"      poster: {d.get('poster')}  bg: {d.get('background')}")
            data = d["episodes"][0]["data"] if d["episodes"] else d["data"]
            links = eng.links(data)
            print(f"  {'✓' if links else '✗'} Bağlantı   {len(links)} adet")
            for l in links[:4]:
                print(f"      - [{l['via']}] {l['url'][:100]}")
            if links and links[0]["via"] == "media":
                headers = dict(src.get("links", {}).get("headers") or {})
                headers["Referer"] = src.get("links", {}).get("referer") or eng.base.rstrip("/") + "/"
                with eng.f.s.get(links[0]["url"], headers=headers, timeout=20, stream=True) as r:
                    print(f"  {'✓' if r.ok else '✗'} Oynatma    HTTP {r.status_code}")
                    ok = ok and r.ok
            elif links:
                print("  ! Oynatma    Gömülü oynatıcı bulundu; çözümleme CloudStream cihazında doğrulanmalı.")
            ok = ok and bool(links)
        except Exception as e:
            ok = False
            print(f"  ✗ Detay/Bağlantı: {e}")
    if not first_item:
        ok = False
    print("  SONUÇ:", "TARİF TESTİ BAŞARILI" if ok else "SORUNLU")
    return ok


if __name__ == "__main__":
    if len(sys.argv) >= 3 and sys.argv[1] == "test":
        results = [test(p, "-v" in sys.argv) for p in sys.argv[2:] if not p.startswith("-")]
        sys.exit(0 if all(results) else 1)
    print(__doc__)
