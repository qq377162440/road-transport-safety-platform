#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
C1 线上学习并发压测：1000 并发读业务接口，验证 4GB 单机规格下的服务稳定性。
在宿主机运行（需全栈已拉起）：python3 stress_test.py
"""
import concurrent.futures
import json
import statistics
import subprocess
import sys
import time
import urllib.request

BASE = "http://localhost:8080"
N = 1000
WORKERS = 200


def login():
    cap = json.load(urllib.request.urlopen(BASE + "/api/auth/captcha", timeout=10))
    cap_id = cap["data"]["captchaId"]
    code = subprocess.check_output(
        ["docker", "exec", "safetrans-redis", "redis-cli", "get", "captcha:" + cap_id]
    ).decode().strip()
    body = json.dumps({
        "username": "aa_gaozhou", "password": "123456",
        "captchaId": cap_id, "captcha": code,
    }).encode()
    req = urllib.request.Request(
        BASE + "/api/auth/login", data=body,
        headers={"Content-Type": "application/json"}, method="POST")
    return json.load(urllib.request.urlopen(req, timeout=10))["data"]["accessToken"]


def one(token, i):
    t0 = time.time()
    try:
        req = urllib.request.Request(
            BASE + "/api/biz/training_video?pageSize=1",
            headers={"Authorization": "Bearer " + token})
        with urllib.request.urlopen(req, timeout=20) as resp:
            d = json.load(resp)
            ok = resp.status == 200 and d.get("code") == 0
            return ok, time.time() - t0, ""
    except Exception as e:
        return False, time.time() - t0, str(e)[:80]


def main():
    token = login()
    print(f"C1 并发压测：{N} 请求 / {WORKERS} 并发 → /api/biz/training_video")
    t0 = time.time()
    with concurrent.futures.ThreadPoolExecutor(max_workers=WORKERS) as ex:
        results = list(ex.map(lambda i: one(token, i), range(N)))
    elapsed = time.time() - t0

    ok = sum(1 for r in results if r[0])
    lat = sorted(r[1] for r in results)
    p95 = lat[int(N * 0.95)]
    errs = [r[2] for r in results if not r[0]][:3]
    rate = ok * 100.0 / N
    print(f"成功 {ok}/{N}（{rate:.1f}%）  总耗时 {elapsed:.2f}s  QPS {N / elapsed:.0f}")
    print(f"延迟 平均 {statistics.mean(lat)*1000:.0f}ms  P95 {p95*1000:.0f}ms  最大 {lat[-1]*1000:.0f}ms")
    if errs:
        print("错误样本：", errs)
    passed = rate >= 99.0 and p95 < 5.0
    print("✅ 压测通过（成功率≥99% 且 P95<5s）" if passed else "❌ 压测未达标")
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
