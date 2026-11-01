"""Download the game's Google Fonts and inline them so the app works offline."""
import base64, re, sys, urllib.request

www = sys.argv[1]
path = f"{www}/index.html"
html = open(path, encoding="utf-8").read()
m = re.search(r'<link rel="stylesheet" href="(https://fonts\.googleapis\.com/[^"]+)">', html)
if not m:
    sys.exit("no font link")
ua = {"User-Agent": "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36"}
css = urllib.request.urlopen(urllib.request.Request(m.group(1).replace("&amp;", "&"), headers=ua)).read().decode()
def inline(match):
    data = urllib.request.urlopen(urllib.request.Request(match.group(1), headers=ua)).read()
    return "url(data:font/woff2;base64," + base64.b64encode(data).decode() + ")"
css = re.sub(r"url\((https://fonts\.gstatic\.com/[^)]+)\)", inline, css)
html = html.replace(m.group(0), "<style>" + css + "</style>")
html = re.sub(r'<link rel="preconnect"[^>]*>\n?', "", html)
open(path, "w", encoding="utf-8").write(html)
print("fonts inlined:", css.count("@font-face"))
