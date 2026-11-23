#!/usr/bin/env python3
"""sources/*.json dosyalarını tek bir sources.json paketinde birleştirir (eklenti bunu uzaktan okur)."""
import glob
import json
import sys

out = sys.argv[1] if len(sys.argv) > 1 else "sources.json"
items = [json.load(open(f, encoding="utf-8")) for f in sorted(glob.glob("sources/*.json"))]
with open(out, "w", encoding="utf-8") as f:
    json.dump(items, f, ensure_ascii=False, indent=1)
print(f"{len(items)} kaynak -> {out}")
