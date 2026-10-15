import json
import urllib.request
import re
import sys
sys.path.append("scratch")
from fetch_buildings import opener, headers, lapp_entry

all_boxue = set()
all_lexue = set()

# Load current term
with open("scratch/boxue_lexue_result.json", "r", encoding="utf-8") as f:
    d = json.load(f)
    all_boxue.update(d["boxue"])
    all_lexue.update(d["lexue"])

terms = [("2025-2026", "2"), ("2025-2026", "1"), ("2024-2025", "2")]
for xn, xq in terms:
    print(f"Checking {xn} {xq}...")
    page = 0
    rows = 1000
    while True:
        url = f"https://lapp.usst.edu.cn/_web/_lightapp/schedule/mobile/school/queryClassSchedule.rst?xn={xn}&xq={xq}&week=-1&zj=-1&jc=-1&rows={rows}&page={page}"
        req = urllib.request.Request(url, headers={**headers, "Referer": lapp_entry})
        try:
            with opener.open(req) as resp:
                data = json.loads(resp.read().decode("utf-8"))
                if data.get("resultCode") != 0:
                    break
                result = data.get("result", {})
                items = result.get("data", [])
                total = result.get("total", 0)
                for c in items:
                    js = c.get("jsdd", "").strip()
                    if "博学" in js:
                        all_boxue.add(js)
                    if "乐学" in js:
                        all_lexue.add(js)
                if not items or (page + 1) * rows >= total or page >= 10:
                    break
                page += 1
        except Exception as e:
            print(f"Error on {xn} {xq}:", e)
            break

print(f"\n=== 跨学期全量累计：博学楼 ({len(all_boxue)} 个) ===")
for r in sorted(all_boxue):
    print(r)

print(f"\n=== 跨学期全量累计：乐学楼 ({len(all_lexue)} 个) ===")
for r in sorted(all_lexue):
    print(r)

with open("scratch/boxue_lexue_all_terms.json", "w", encoding="utf-8") as f:
    json.dump({
        "boxue": sorted(all_boxue),
        "lexue": sorted(all_lexue)
    }, f, ensure_ascii=False, indent=2)

print("\nDone! Saved to scratch/boxue_lexue_all_terms.json")
