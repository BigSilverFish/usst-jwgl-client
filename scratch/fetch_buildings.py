import urllib.request
import urllib.parse
import http.cookiejar
import re
import json
import ssl
import sys

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

class HTTPSRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if newurl.startswith("http://") and "usst.edu.cn" in newurl:
            newurl = "https://" + newurl[7:]
        return super().redirect_request(req, fp, code, msg, headers, newurl)

cj = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(HTTPSRedirectHandler, urllib.request.HTTPCookieProcessor(cj), urllib.request.HTTPSHandler(context=ctx))
urllib.request.install_opener(opener)

headers = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"}

# 1. Login via CAS
ENTRY_URL = "https://jwgl.usst.edu.cn/sso/jziotlogin"
resp = opener.open(urllib.request.Request(ENTRY_URL, headers=headers))
cas_html = resp.read().decode('utf-8', errors='ignore')

lt_match = re.search(r'name=["\']lt["\']\s+value=["\']([^"\']+)["\']', cas_html)
exec_match = re.search(r'name=["\']execution["\']\s+value=["\']([^"\']+)["\']', cas_html)

if lt_match and exec_match:
    lt = lt_match.group(1)
    execution = exec_match.group(1)
    login_data = urllib.parse.urlencode({
        "username": "2435055230",
        "password": "ZHOU2006usst",
        "lt": lt,
        "dllt": "userNamePasswordLogin",
        "execution": execution,
        "_eventId": "submit",
        "rmShown": "1"
    }).encode('utf-8')
    login_resp = opener.open(urllib.request.Request(resp.geturl(), data=login_data, headers={**headers, "Content-Type": "application/x-www-form-urlencoded"}))
    print("Logged into CAS, landed on:", login_resp.geturl())

# 2. Access lapp entry to transfer session
lapp_entry = "https://lapp.usst.edu.cn/_web/_customizes/usst/schoolTimetable/mobile/permit/pc/pub/index.html"
lapp_resp = opener.open(urllib.request.Request(lapp_entry, headers=headers))
print("Lapp entry URL:", lapp_resp.geturl())
lapp_html = lapp_resp.read().decode('utf-8', errors='ignore')

if "authserver/login" in lapp_resp.geturl():
    # If Lapp has its own CAS redirect, post credentials again to Lapp CAS
    lt_lapp = re.search(r'name=["\']lt["\']\s+value=["\']([^"\']+)["\']', lapp_html).group(1)
    exec_lapp = re.search(r'name=["\']execution["\']\s+value=["\']([^"\']+)["\']', lapp_html).group(1)
    login_data_lapp = urllib.parse.urlencode({
        "username": "2435055230",
        "password": "ZHOU2006usst",
        "lt": lt_lapp,
        "dllt": "userNamePasswordLogin",
        "execution": exec_lapp,
        "_eventId": "submit",
        "rmShown": "1"
    }).encode('utf-8')
    opener.open(urllib.request.Request(lapp_resp.geturl(), data=login_data_lapp, headers={**headers, "Content-Type": "application/x-www-form-urlencoded"}))
    print("Logged into Lapp CAS")

# 3. Query all pages from queryClassSchedule.rst
all_courses = []
page = 0
rows = 1000
total = 0

while True:
    sched_url = f"https://lapp.usst.edu.cn/_web/_lightapp/schedule/mobile/school/queryClassSchedule.rst?xn=2026-2027&xq=1&week=-1&zj=-1&jc=-1&rows={rows}&page={page}"
    req = urllib.request.Request(sched_url, headers={**headers, "Referer": lapp_entry})
    with opener.open(req) as resp:
        content = resp.read().decode('utf-8')
        try:
            data = json.loads(content)
        except Exception as e:
            print("Failed to parse JSON, content:", content[:200])
            break
            
        if data.get("resultCode") != 0:
            print("API error:", data.get("errorMsg"))
            break
            
        result = data.get("result", {})
        total = result.get("total", 0)
        items = result.get("data", [])
        all_courses.extend(items)
        print(f"Fetched page {page}: {len(items)} items, accumulated {len(all_courses)}/{total}")
        
        if not items or len(all_courses) >= total or page >= 10:
            break
        page += 1

print(f"\nTotal courses fetched: {len(all_courses)}")

# Extract all classrooms
all_classrooms = set()
boxue_classrooms = set()
lexue_classrooms = set()

for c in all_courses:
    jsdd = c.get("jsdd", "").strip()
    if not jsdd:
        continue
    all_classrooms.add(jsdd)
    if "博学" in jsdd:
        boxue_classrooms.add(jsdd)
    if "乐学" in jsdd:
        lexue_classrooms.add(jsdd)

print(f"\n=== 博学楼门牌号 ({len(boxue_classrooms)} 个) ===")
for r in sorted(boxue_classrooms):
    print(" -", r)

print(f"\n=== 乐学楼门牌号 ({len(lexue_classrooms)} 个) ===")
for r in sorted(lexue_classrooms):
    print(" -", r)

with open("scratch/boxue_lexue_result.json", "w", encoding="utf-8") as f:
    json.dump({
        "boxue": sorted(boxue_classrooms),
        "lexue": sorted(lexue_classrooms),
        "total_courses": len(all_courses)
    }, f, ensure_ascii=False, indent=2)

print("\nDone! Saved to scratch/boxue_lexue_result.json")
