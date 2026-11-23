#!/usr/bin/env python3
"""预生成压测账号池 + 预签发 token，写进 jmeter/tokens.csv。

    bin/seed-users.py <count> [baseUrl]

为什么要预生成（设计文档第 8 节「压测有效性的五个前提」中的 ① 和 ②）：

  ① 不能所有线程用同一个 userId —— 那测出来的是「去重」，不是「抢名额」。
     本脚本给每个线程一个不同用户。
  ② 不能现场注册/登录 —— 登录要跑 BCrypt，那是故意调慢的（默认 cost 10，
     单次数十到上百毫秒）。把认证开销混进报名接口，测的就不是报名了。
     本脚本在压测前一次性把 token 全部签好，压测时只带 token。

输出格式：userId,token（CSV Data Set Config 直接读，两个变量名 userId / token）
"""

import concurrent.futures
import csv
import json
import sys
import urllib.error
import urllib.parse
import urllib.request

TIMEOUT = 20
WORKERS = 32
PASSWORD = "bench-pass-123"


def check_base(base: str) -> str:
    """只接受 http/https——urllib 也支持 file://，不能让一个手滑的参数读到本地文件。"""
    parsed = urllib.parse.urlparse(base)
    if parsed.scheme not in ("http", "https") or not parsed.netloc:
        raise SystemExit(f"baseUrl 必须是 http(s)://host:port，收到: {base!r}")
    return base.rstrip("/")


def post(base: str, path: str, body: dict) -> dict:
    req = urllib.request.Request(
        f"{base}{path}",
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
            return json.load(resp)
    except (urllib.error.URLError, OSError, json.JSONDecodeError) as exc:
        raise RuntimeError(f"{path} 请求失败: {exc}") from exc


def ensure_user(index: int, base: str) -> tuple[int, str]:
    """注册（已存在则登录）第 index 个压测用户，返回 (userId, token)。

    可重跑：账号池里已经有这个用户时注册会返回「用户名已被占用」（code 1500），
    那就退回去登录。不然第二次跑 seed-users.py 会报一堆失败。
    """
    username = f"bench{index:06d}"
    payload = post(base, "/api/auth/register",
                   {"username": username, "password": PASSWORD})
    if payload["code"] != 0:
        payload = post(base, "/api/auth/login",
                       {"username": username, "password": PASSWORD})
    if payload["code"] != 0:
        raise RuntimeError(f"{username} 注册和登录都失败: {payload}")
    return payload["data"]["userId"], payload["data"]["token"]


def _bad_count(raw: str) -> int:
    raise SystemExit(f"count 必须是正整数，收到: {raw!r}")


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    count = int(sys.argv[1]) if sys.argv[1].isdigit() else _bad_count(sys.argv[1])
    base = check_base(sys.argv[2] if len(sys.argv) > 2 else "http://localhost:8081")
    out = "jmeter/tokens.csv"

    rows: list[tuple[int, str]] = []
    failures = 0
    with concurrent.futures.ThreadPoolExecutor(max_workers=WORKERS) as pool:
        futures = {pool.submit(ensure_user, i, base): i for i in range(1, count + 1)}
        for future in concurrent.futures.as_completed(futures):
            index = futures[future]
            try:
                rows.append(future.result())
            except Exception as exc:  # noqa: BLE001 - 每个失败都要报出来，不能吞
                failures += 1
                if failures <= 5:
                    print(f"  第 {index} 个失败: {exc}", file=sys.stderr)

    rows.sort()
    try:
        with open(out, "w", newline="") as fh:
            # lineterminator 用 \n 不用 csv 默认的 \r\n：CRLF 会让 awk/cut 取到的 token 带上
            # 回车，拼进 Authorization 头就是 RFC 7230 违规，Tomcat 直接回 400 连业务都进不去。
            writer = csv.writer(fh, lineterminator="\n")
            writer.writerow(["userId", "token"])
            writer.writerows(rows)
    except OSError as exc:
        print(f"写不了 {out}: {exc}", file=sys.stderr)
        return 1

    print(f"已写入 {out}：{len(rows)} 个用户，失败 {failures} 个")
    if failures:
        print("有失败，压测数据会不完整——先解决再压", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
