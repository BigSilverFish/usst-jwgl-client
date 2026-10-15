import json
import urllib.request
import re
import sys
sys.path.append("scratch")
from fetch_buildings import opener, headers, lapp_entry

# Buildings to query
target_buildings = ["一教", "三教", "四教", "卓越", "国合", "综合楼"]

found_classrooms = {b: set() for b in target_buildings}

terms = [("2026-2027", "1"), ("2025-2026", "2"), ("2025-2026", "1")]

for xn, xq in terms:
    print(f"Fetching {xn} term {xq}...")
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
                    if not js:
                        continue
                    for b in target_buildings:
                        if b in js:
                            found_classrooms[b].add(js)
                if not items or (page + 1) * rows >= total or page >= 10:
                    break
                page += 1
        except Exception as e:
            print(f"Error on {xn} {xq}:", e)
            break

output = {}
for b in target_buildings:
    output[b] = sorted(found_classrooms[b])
    print(f"\n=== {b} ({len(output[b])} 个) ===")
    print(", ".join(output[b]))

with open("scratch/all_buildings_classrooms.json", "w", encoding="utf-8") as f:
    json.dump(output, f, ensure_ascii=False, indent=2)

print("\nSaved all classrooms to scratch/all_buildings_classrooms.json")
