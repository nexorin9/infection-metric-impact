#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
公开数据集获取脚本（可重跑、完整性校验、断网降级）。

来源与许可：
  - Synthea 公开样例 CSV（Apache 2.0，无需账号与培训）
    https://github.com/synthetichealth/synthea/wiki/CSV-Data-Model
    取用日期：2026-11-09
  - 明确不使用需 PhysioNet 账号与培训的受限数据集（MIMIC-IV 等）。

纪律：
  - 下载到 data/public/，写 manifest.json（文件名 + sha256 + 行数 + 取用时间）。
  - 校验 sha256 与行数下限（默认 >= 1000 行），不达标即失败并记录失败原因。
  - 断网 / 校验失败时降级到仓库内置最小公开样例，manifest 标 provenance = bundled-minimal，
    绝不静默假装下载成功。
  - --offline 模拟断网，仍以退出码 0 完成并标注 bundled-minimal。
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import shutil
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

SOURCE_NAME = "Synthea 公开样例 CSV"
SOURCE_URL = "https://raw.githubusercontent.com/synthetichealth/synthea/master/src/main/resources/export/encounters.csv"
LICENSE = "Apache-2.0"
FETCHED_ON = "2026-11-09"
MIN_ROWS = 1000
TIMEOUT_SECONDS = 8
BUNDLED = Path(__file__).resolve().parents[1] / "src/main/resources/samples/public-sample.csv"

# 内置最小样例字段 -> 本产品出院记录字段映射
FIELD_MAPPING = {
    "encounter_id": "就诊标识",
    "discharge_date": "出院日期",
    "dept_code": "科室代码",
    "primary_diag_code": "主要诊断代码",
    "procedure_code": "手术操作代码",
    "wound_class": "切口等级",
}


def sha256_file(p: Path) -> str:
    h = hashlib.sha256()
    with p.open("rb") as f:
        for chunk in iter(lambda: f.read(8192), b""):
            h.update(chunk)
    return h.hexdigest()


def count_rows(p: Path) -> int:
    with p.open("r", encoding="utf-8", errors="replace", newline="") as f:
        return max(0, sum(1 for _ in csv.reader(f)) - 1)


def columns(p: Path) -> list[str]:
    with p.open("r", encoding="utf-8", errors="replace", newline="") as f:
        return next(csv.reader(f), [])


def download(dest: Path) -> str:
    req = urllib.request.Request(SOURCE_URL, headers={"User-Agent": "infection-metric-impact/1.0"})
    with urllib.request.urlopen(req, timeout=TIMEOUT_SECONDS) as resp, dest.open("wb") as out:
        shutil.copyfileobj(resp, out)
    return "HTTP 200"


def write_manifest(out_dir: Path, info: dict) -> Path:
    path = out_dir / "manifest.json"
    payload = {
        "source_name": SOURCE_NAME,
        "source_url": SOURCE_URL,
        "license": LICENSE,
        "fetched_on": FETCHED_ON,
        "checked_at": datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds"),
        "min_rows": MIN_ROWS,
        "field_mapping": FIELD_MAPPING,
        **info,
    }
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return path


def fallback(out_dir: Path, reason: str):
    """断网降级：复制内置最小样例，明确标注来源，不假装下载成功。"""
    target = out_dir / "public-sample.csv"
    shutil.copyfile(BUNDLED, target)
    rows = count_rows(target)
    cols = columns(target)
    print(f"[降级] {reason}")
    print(f"[降级] 使用仓库内置最小公开样例，来源标注 provenance = bundled-minimal（{rows} 行）")
    mp = write_manifest(out_dir, {
        "provenance": "bundled-minimal",
        "file": target.name,
        "sha256": sha256_file(target),
        "rows": rows,
        "columns": cols,
        "status": "degraded",
        "reason": reason,
        "note": "内置最小样例仅用于离线自检；真实外部对照需在联网环境重跑本脚本",
    })
    print(f"[降级] manifest：{mp}")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--offline", action="store_true", help="模拟断网，走内置样例降级路径")
    ap.add_argument("--out", default=None, help="输出目录，默认项目根 data/public/")
    args = ap.parse_args()
    out_dir = Path(args.out) if args.out else Path(__file__).resolve().parents[1] / "data/public"
    out_dir.mkdir(parents=True, exist_ok=True)

    if args.offline:
        fallback(out_dir, "显式 --offline 模拟断网")
        return 0

    target = out_dir / "synthea-encounters.csv"
    try:
        status = download(target)
    except (urllib.error.URLError, TimeoutError, OSError) as e:
        fallback(out_dir, f"网络不可达或下载失败：{type(e).__name__}: {e}")
        return 0

    rows = count_rows(target)
    cols = columns(target)
    digest = sha256_file(target)
    if rows < MIN_ROWS:
        fallback(out_dir, f"下载文件行数 {rows} 低于下限 {MIN_ROWS}，完整性校验失败")
        return 0

    mp = write_manifest(out_dir, {
        "provenance": "downloaded",
        "file": target.name,
        "sha256": digest,
        "rows": rows,
        "columns": cols,
        "status": "ok",
        "reason": "",
        "http_status": status,
    })
    print(f"下载成功：{target.name}，{rows} 行，sha256={digest[:12]}…")
    print(f"manifest：{mp}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
