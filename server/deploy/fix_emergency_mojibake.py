#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
B15 应急数据 mojibake 幂等还原工具。

背景：开发期手动预建 V24 种子时 MySQL 连接 character_set_connection=latin1，
UTF-8 中文被按 CP1252 解码后再按 UTF-8 重编码入库（"一、" -> "ä¸€ã€"）。
此前已修复 emergency_plan_tpl，但仍残留一批「模板修复前创建的」预案实例、
演练记录与流程日志；且变量替换后的正常中文会与乱码混在同一段文本里。

做法：
  * 仅对命中乱码特征的文本列动手，正常中文一律跳过（幂等，可反复运行）；
  * 读写全部走 HEX()/UNHEX()，绕开连接字符集，杜绝二次污染；
  * 逐字符把 mojibake 字符编码回 CP1252 原始字节，真 Unicode 字符（正常中文）
    保留 UTF-8 字节，最后整体按 UTF-8 解码，因此能处理「乱码+正常中文」混合文本。

运行：python3 fix_emergency_mojibake.py
"""
import subprocess
import sys

MYSQL = ["docker", "exec", "safetrans-mysql", "mysql", "-uroot", "-proot123",
         "--default-character-set=utf8mb4", "safetrans", "-N", "-e"]

# 典型双重编码特征（用 LIKE BINARY 精确匹配，避开 utf8mb4_0900_ai_ci 的 'Â'≈'a' 误报）
GARBLE = ["ä¸€", "ã€", "Â", "ï¿½"]


def sql(q):
    r = subprocess.run(MYSQL + [q], capture_output=True, text=True)
    if r.returncode != 0:
        print(f"  SQL 失败：{r.stderr.strip()[:160]}")
    return r.stdout.strip()


def restore(s):
    out = bytearray()
    for ch in s:
        o = ord(ch)
        if o < 0x80:
            out.append(o)
        elif 0x80 <= o <= 0x9F:
            # CP1252 未定义该区间，字节原样保留
            out.append(o)
        else:
            try:
                out.extend(ch.encode("cp1252"))   # mojibake 字符 -> 原始字节
            except UnicodeEncodeError:
                out.extend(ch.encode("utf-8"))    # 真 Unicode 字符（正常中文）原样保留
    return bytes(out).decode("utf-8")


def main():
    cols = sql("SELECT table_name,column_name FROM information_schema.columns "
               "WHERE table_schema='safetrans' AND table_name LIKE 'emergency%' "
               "AND data_type IN ('varchar','char','text','mediumtext','longtext')")
    cond_g = " OR ".join([f"`{{c}}` LIKE BINARY '%{g}%'" for g in GARBLE])
    fixed = 0
    skipped = 0
    for line in cols.splitlines():
        if "\t" not in line:
            continue
        t, c = line.split("\t")[:2]
        has_id = sql("SELECT COUNT(*) FROM information_schema.columns WHERE "
                     f"table_schema='safetrans' AND table_name='{t}' AND column_name='id'")
        if has_id != "1":
            continue
        cond = cond_g.replace("{c}", c)
        hits = sql(f"SELECT id, HEX(`{c}`) FROM `{t}` WHERE {cond}")
        for h in hits.splitlines():
            if "\t" not in h:
                continue
            rid, hx = h.split("\t", 1)
            if not hx or hx == "NULL":
                continue
            try:
                s = bytes.fromhex(hx).decode("utf-8")
            except Exception:
                skipped += 1
                continue
            if not any(g in s for g in GARBLE):
                continue
            try:
                out = restore(s)
            except Exception:
                skipped += 1
                continue
            # 结果必须仍是合法可读文本，且不再含乱码特征
            if out == s or any(g in out for g in GARBLE):
                skipped += 1
                continue
            sql(f"UPDATE `{t}` SET `{c}`=UNHEX('{out.encode('utf-8').hex()}') WHERE `id`={rid}")
            fixed += 1
            print(f"  ✓ {t}.{c} id={rid}: {s[:32]} -> {out[:32]}")
    print(f"\n共还原 {fixed} 处，跳过 {skipped} 处（无法安全还原）")
    return 0 if skipped == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
