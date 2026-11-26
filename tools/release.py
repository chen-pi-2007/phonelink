"""一键发版：编译正式包 -> 生成 update.json -> 推送 GitHub 和 Gitee -> 建 GitHub Release。

用法（在项目根目录）：
    1. 改 app/build.gradle 里的 versionCode（+1）和 versionName
    2. python tools/release.py "这次更新的说明"

前提：
    - 签名证书在 D:\\tool\\keys\\phonelink（或环境变量 PHONELINK_KEYS_DIR）
    - git 远程：origin = GitHub，gitee = Gitee
    - gh 已登录
"""
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GITHUB = "chen-pi-2007/phonelink"
JAVA_HOME = os.environ.get("JAVA_HOME") or r"D:\22490\jdk17\jdk-17.0.15+6"


def run(*cmd, **kw):
    print("$", " ".join(cmd))
    subprocess.run(cmd, cwd=ROOT, check=True, **kw)


def gitee_repo():
    """从 gitee 远程地址解析出 owner/repo。"""
    try:
        url = subprocess.run(["git", "remote", "get-url", "gitee"], cwd=ROOT,
                             capture_output=True, text=True, check=True).stdout.strip()
    except subprocess.CalledProcessError:
        return None
    m = re.search(r"gitee\.com[:/](.+?)(?:\.git)?$", url)
    return m.group(1) if m else None


def main():
    notes = sys.argv[1] if len(sys.argv) > 1 else ""
    gradle = (ROOT / "app" / "build.gradle").read_text(encoding="utf-8")
    code = int(re.search(r"versionCode\s+(\d+)", gradle).group(1))
    name = re.search(r"versionName\s+'([^']+)'", gradle).group(1)

    old = json.loads((ROOT / "update.json").read_text(encoding="utf-8")) if (ROOT / "update.json").exists() else {}
    if old.get("versionCode", 0) >= code:
        sys.exit(f"versionCode {code} 不大于已发布的 {old.get('versionCode')}，请先在 app/build.gradle 里 +1")

    env = dict(os.environ, JAVA_HOME=JAVA_HOME)
    gradlew = str(ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew"))
    run(gradlew, "assembleRelease", "-q", env=env)

    dist = ROOT / "dist"
    dist.mkdir(exist_ok=True)
    for f in dist.glob("PhoneLink-*.apk"):
        f.unlink()
    apk_name = f"PhoneLink-v{name}.apk"
    apk = dist / apk_name
    shutil.copy(ROOT / "app/build/outputs/apk/release/app-release.apk", apk)
    sha = hashlib.sha256(apk.read_bytes()).hexdigest()

    gh_release = f"https://github.com/{GITHUB}/releases/download/v{name}/{apk_name}"
    urls = []
    gr = gitee_repo()
    if gr:
        urls.append(f"https://gitee.com/{gr}/raw/main/dist/{apk_name}")
    urls += [
        f"https://raw.githubusercontent.com/{GITHUB}/main/dist/{apk_name}",
        gh_release,
        f"https://ghfast.top/{gh_release}",
    ]
    manifest = {"versionCode": code, "versionName": name, "notes": notes,
                "sha256": sha, "size": apk.stat().st_size, "apk": urls}
    (ROOT / "update.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    run("git", "add", "-A")
    run("git", "commit", "-m", f"发布 v{name}" + (f"：{notes}" if notes else ""))
    run("git", "push", "origin", "HEAD:main")
    if gr:
        run("git", "push", "gitee", "HEAD:main")
    else:
        print("!! 没有配置 gitee 远程，只推送了 GitHub")
    run("gh", "release", "create", f"v{name}", str(apk), "--repo", GITHUB,
        "--title", f"v{name}", "--notes", notes or f"v{name}")
    print(f"\n发布完成 v{name}（versionCode {code}），sha256 {sha}")


if __name__ == "__main__":
    main()
