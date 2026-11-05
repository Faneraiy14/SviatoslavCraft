#!/usr/bin/env python3
"""Публічний список версій Voxelin (як version_manifest у Mojang): за ним хостинги, Docker і лаунчери
самі знаходять потрібну збірку, перевіряють sha256 і знають, як її запустити.

  tools/versions.py add ВЕРСІЯ АРХІВ.zip ФАЙЛ.json [--protocol N] [--time ISO] [--snapshot]   додати/оновити версію
  tools/versions.py check ФАЙЛ.json                                           перевірити формат

Формат (format 1) - лише додаємо поля, нічого не перейменовуємо:
  latest: {release, server}       остання версія і остання з сервером без вікна
  versions[]: id, type (release|snapshot), releaseTime, java, protocol (мережевий; гості й хост
    сумісні, якщо однаковий), download {url, sha256, size, dir - тека всередині архіву},
    client {mainClass, classpath}, server {mainClass, classpath, port} або null (до 1.2.0 сервера не було)
Шлях класів - відносно dir; '*' - усі jar-и теки (як у java -cp).
"""
import hashlib, json, os, re, sys, datetime

REPO = "Faneraiy14/voxelin-releases"


def vkey(v):
    return tuple(int(x) for x in re.findall(r"\d+", v)[:3])


def entry(ver, zpath, when, kind, protocol):
    data = open(zpath, "rb").read()
    name = os.path.basename(zpath)
    modern = vkey(ver) >= (0, 1, 0)  # з 0.1.0 гра - voxelin.client.Voxelin
    e = {
        "id": ver, "type": kind, "releaseTime": when,
        "java": 21,
        "protocol": protocol,
        "download": {"url": f"https://github.com/{REPO}/releases/download/v{ver}/{name}",
                     "sha256": hashlib.sha256(data).hexdigest(), "size": len(data), "dir": name[:-4]},
        "client": {"mainClass": "voxelin.client.Voxelin" if modern else "com.sviatoslav.craft.game.SviatoslavCraft", "classpath": ["out", "lib/*"]},
        "server": {"mainClass": "voxelin.server.DedicatedServer", "classpath": ["out", "lib/*"], "port": 24555} if vkey(ver) >= (1, 2, 0) else None,
    }
    return e


def check(m):
    assert m.get("format") == 1, "format"
    ids = [v["id"] for v in m["versions"]]
    assert len(ids) == len(set(ids)), "повтор версії"
    for v in m["versions"]:
        for k in ("id", "type", "releaseTime", "download", "client"):
            assert k in v, f"{v.get('id')}: немає {k}"
        assert re.fullmatch(r"[0-9a-f]{64}", v["download"]["sha256"]), v["id"]
    rel = [v for v in m["versions"] if v["type"] == "release"]
    assert m["latest"]["release"] == rel[0]["id"], "latest.release"
    srv = [v for v in rel if v.get("server")]
    assert m["latest"].get("server") == (srv[0]["id"] if srv else None), "latest.server"


def main(a):
    if len(a) >= 2 and a[0] == "check":
        check(json.load(open(a[1], encoding="utf-8")))
        print("ok")
        return
    if len(a) < 4 or a[0] != "add":
        print(__doc__)
        sys.exit(1)
    ver, zpath, out = a[1], a[2], a[3]
    when = a[a.index("--time") + 1] if "--time" in a else datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    kind = "snapshot" if "--snapshot" in a else "release"
    protocol = int(a[a.index("--protocol") + 1]) if "--protocol" in a else None
    m = json.load(open(out, encoding="utf-8")) if os.path.exists(out) else {"format": 1, "latest": {}, "versions": []}
    m["versions"] = [v for v in m["versions"] if v["id"] != ver] + [entry(ver, zpath, when, kind, protocol)]
    m["versions"].sort(key=lambda v: vkey(v["id"]), reverse=True)
    rel = [v for v in m["versions"] if v["type"] == "release"]
    m["latest"] = {"release": rel[0]["id"] if rel else None,
                   "server": next((v["id"] for v in rel if v.get("server")), None)}
    check(m)
    tmp = out + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(m, f, ensure_ascii=False, indent=1)
        f.write("\n")
    os.replace(tmp, out)
    print(f"{out}: {ver} ({m['latest']})")


if __name__ == "__main__":
    main(sys.argv[1:])
