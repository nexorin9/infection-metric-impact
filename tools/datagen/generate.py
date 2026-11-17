#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
规则化合成数据生成器（自带 oracle，脱敏，确定性可重跑）。

按官方 27 项指标口径 + 12 条脱敏院感口径的场景规则生成：
  - 4 个季度 × 约 1200 条出院记录（每季度 300 条，共 1200 条）
  - 4 个季度 × 约 45 条院感明细（共 180 条）
  - 39 条口径（27 条官方基准 + 12 条脱敏院感草稿）
  - oracle：每条口径每个季度独立算出的期望分子/分母（规则化计算，非手写随机 JSON）

字段一律脱敏，不含患者可识别明细。输出确定性：同种子重复执行逐字节一致。
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import random
from pathlib import Path

SEED = 20261109
QUARTERS = ["2026Q1", "2026Q2", "2026Q3", "2026Q4"]
PER_QUARTER_ENCOUNTERS = 300
PER_QUARTER_INFECTIONS = 45
DEPTS = ["0301", "0302", "0303", "0304"]
DIAG_CODES = ["A09", "J18", "K35", "I10", "N39"]
PROC_CODES = ["47.01", "51.23", "81.08", "00.00"]
WOUND_CLASSES = ["I", "II", "III"]

# 官方 27 项（与 benchmarks/mrq_control_2021.json 一致）
OFFICIAL = [
    ("MER-HR-01", "住院病案管理人员月均负担出院患者病历数"),
    ("MER-HR-02", "门诊病案管理人员月均负担门诊患者病历数"),
    ("MER-HR-03", "病案编码人员月均负担出院患者病历数"),
    ("MER-TL-01", "入院记录24小时内完成率"),
    ("MER-TL-02", "手术记录24小时内完成率"),
    ("MER-TL-03", "出院记录24小时内完成率"),
    ("MER-TL-04", "病案首页24小时内完成率"),
    ("MER-ME-01", "CT/MRI检查记录符合率"),
    ("MER-ME-02", "病理检查记录符合率"),
    ("MER-ME-03", "细菌培养检查记录符合率"),
    ("MER-D&T-01", "抗菌药物使用记录符合率"),
    ("MER-D&T-02", "恶性肿瘤化学治疗记录符合率"),
    ("MER-D&T-03", "恶性肿瘤放射治疗记录符合率"),
    ("MER-D&T-04", "手术相关记录完整率"),
    ("MER-D&T-05", "植入物相关记录符合率"),
    ("MER-D&T-06", "临床用血相关记录符合率"),
    ("MER-D&T-07", "医师查房记录完整率"),
    ("MER-D&T-08", "患者抢救记录及时完成率"),
    ("MER-TQ-01", "出院患者病历2日归档率"),
    ("MER-TQ-02", "出院患者病历归档完整率"),
    ("MER-TQ-03", "主要诊断填写正确率"),
    ("MER-TQ-04", "主要诊断编码正确率"),
    ("MER-TQ-05", "主要手术填写正确率"),
    ("MER-TQ-06", "主要手术编码正确率"),
    ("MER-TQ-07", "不合理复制病历发生率"),
    ("MER-TQ-08", "知情同意书规范签署率"),
    ("MER-TQ-09", "甲级病历率"),
]

# 本院 12 条脱敏院感口径（confidence: draft，不冒充官方口径）
HAI = [
    ("HAI-INC-01", "住院患者医院感染发病率"),
    ("HAI-INC-02", "手术部位感染发生率"),
    ("HAI-INC-03", "ICU 导管相关血流感染发生率"),
    ("HAI-INC-04", "导尿管相关尿路感染发生率"),
    ("HAI-INC-05", "呼吸机相关肺炎发生率"),
    ("HAI-MDR-01", "多重耐药菌检出率"),
    ("HAI-MDR-02", "多重耐药菌医院感染构成比"),
    ("HAI-SSI-01", "Ⅰ类切口手术部位感染率"),
    ("HAI-SSI-02", "Ⅲ类切口手术部位感染率"),
    ("HAI-ABX-01", "抗菌药物治疗前病原学送检率"),
    ("HAI-ABX-02", "围手术期抗菌药物预防使用率"),
    ("HAI-SUR-01", "院感病例漏报率"),
]


def make_encounters(rng: random.Random):
    rows = []
    idx = 0
    for qi, q in enumerate(QUARTERS):
        for i in range(PER_QUARTER_ENCOUNTERS):
            idx += 1
            dept = DEPTS[i % len(DEPTS)]
            diag = DIAG_CODES[(i * 7 + qi) % len(DIAG_CODES)]
            proc = PROC_CODES[(i * 3 + qi) % len(PROC_CODES)]
            wound = WOUND_CLASSES[(i * 5 + qi) % len(WOUND_CLASSES)]
            had_surgery = (i % 3 != 0)
            rows.append({
                "encounter_id": f"ENC-{q}-{idx:05d}",
                "discharge_date": f"2026-{qi * 3 + 1 + (i % 3):02d}-{(i % 27) + 1:02d}",
                "dept_code": dept,
                "primary_diag_code": diag,
                "procedure_code": proc if had_surgery else "",
                "wound_class": wound if had_surgery else "",
                "had_surgery": "true" if had_surgery else "false",
                "quarter": q,
                "admission_record_ontime": "true" if (i + qi) % 5 != 0 else "false",
                "discharge_record_ontime": "true" if (i + qi) % 4 != 0 else "false",
                "first_page_ontime": "true" if (i + qi) % 6 != 0 else "false",
                "primary_diag_correct": "true" if (i + qi) % 7 != 0 else "false",
                "grade_a_record": "true" if (i + qi) % 3 != 0 else "false",
                "archived_2d": "true" if (i + qi) % 5 != 0 else "false",
            })
    return rows


def make_infections(rng: random.Random, encounters):
    rows = []
    idx = 0
    for qi, q in enumerate(QUARTERS):
        pool = [e for e in encounters if e["quarter"] == q]
        for i in range(PER_QUARTER_INFECTIONS):
            idx += 1
            e = pool[(i * 11 + qi) % len(pool)]
            rows.append({
                "report_id": f"HAI-{q}-{idx:05d}",
                "encounter_id": e["encounter_id"],
                "quarter": q,
                "dept_code": e["dept_code"],
                "infection_site": ["呼吸道", "手术部位", "泌尿道", "血流"][i % 4],
                "pathogen": ["大肠埃希菌", "金黄色葡萄球菌", "肺炎克雷伯菌", "鲍曼不动杆菌"][i % 4],
                "mdr_flag": "true" if i % 4 == 0 else "false",
                "related_surgery_date": e["discharge_date"] if e["had_surgery"] == "true" else "",
                "onset_date": f"2026-{qi * 3 + 1 + (i % 3):02d}-{(i % 27) + 1:02d}",
                "wound_class": e["wound_class"],
                "culture_before_abx": "true" if i % 3 != 0 else "false",
            })
    return rows


def make_indicators():
    items = []
    for code, title in OFFICIAL:
        # 官方 27 项的分母归属（来自官方文件定义），用于「改一处影响谁」的真实结构
        denominator_key = ""
        if code in ("MER-TL-03", "MER-TL-04", "MER-TQ-01", "MER-TQ-02",
                    "MER-TQ-03", "MER-TQ-04", "MER-TQ-07", "MER-TQ-09"):
            denominator_key = "同期出院患者病历总数"
        elif code in ("MER-TL-01", "MER-D&T-07"):
            denominator_key = "同期住院患者病历总数"
        elif code in ("MER-TL-02", "MER-D&T-04"):
            denominator_key = "同期住院手术患者病历总数"
        elif code in ("MER-TQ-05", "MER-TQ-06"):
            denominator_key = "同期出院手术患者病历总数"
        items.append({
            "indicator_code": code, "definition_version": "2026.1", "title": title,
            "numerator": {"op": "AND", "children": [
                {"field": "quarter", "cmp": "eq", "value": "@quarter"}]},
            "denominator": {"field": "quarter", "cmp": "eq", "value": "@quarter"},
            "denominator_key": denominator_key,
            "exclusions": [], "scope": {"deptCodes": DEPTS, "periods": QUARTERS},
            "confidence": "official",
            "basis": "《病案管理质量控制指标（2021年版）》" + code,
        })
    for code, title in HAI:
        items.append({
            "indicator_code": code, "definition_version": "2026.1", "title": title,
            "numerator": {"op": "AND", "children": [
                {"field": "quarter", "cmp": "eq", "value": "@quarter"},
                {"field": "mdr_flag", "cmp": "eq", "value": "true"}]},
            "denominator": {"field": "quarter", "cmp": "eq", "value": "@quarter"},
            "denominator_key": "同期出院患者病历总数",
            "exclusions": [], "scope": {"deptCodes": DEPTS, "periods": QUARTERS},
            "confidence": "draft",
            "basis": "本院院感口径草稿（脱敏，非官方口径）",
        })
    return items


def compute_oracle(encounters, infections, indicators):
    """独立按规则算出期望分子/分母，作为可复核 oracle（不复用求值器代码路径）。"""
    oracle = []
    for ind in indicators:
        code = ind["indicator_code"]
        for q in QUARTERS:
            enc_q = [e for e in encounters if e["quarter"] == q]
            inf_q = [r for r in infections if r["quarter"] == q]
            den = len(enc_q)
            if code.startswith("HAI-"):
                num = sum(1 for r in inf_q if r["mdr_flag"] == "true")
            else:
                field = {
                    "MER-TL-01": "admission_record_ontime",
                    "MER-TL-03": "discharge_record_ontime",
                    "MER-TL-04": "first_page_ontime",
                    "MER-TQ-03": "primary_diag_correct",
                    "MER-TQ-09": "grade_a_record",
                    "MER-TQ-01": "archived_2d",
                }.get(code)
                if field:
                    num = sum(1 for e in enc_q if e[field] == "true")
                elif "手术" in ind["title"]:
                    num = sum(1 for e in enc_q if e["had_surgery"] == "true")
                else:
                    num = sum(1 for e in enc_q if e["grade_a_record"] == "true")
            oracle.append({
                "indicator_code": code, "definition_version": "2026.1",
                "quarter": q, "expected_numerator": num, "expected_denominator": den,
                "oracle_rule": "规则化计数（与复算求值器独立实现）",
            })
    return oracle


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=None, help="输出目录，默认 src/main/resources/samples")
    ap.add_argument("--seed", type=int, default=SEED)
    args = ap.parse_args()
    rng = random.Random(args.seed)
    out = Path(args.out) if args.out else Path(__file__).resolve().parents[2] / "src/main/resources/samples"
    out.mkdir(parents=True, exist_ok=True)

    encounters = make_encounters(rng)
    infections = make_infections(rng, encounters)
    indicators = make_indicators()
    oracle = compute_oracle(encounters, infections, indicators)

    def write_csv(name, rows):
        if not rows:
            return
        with (out / name).open("w", newline="", encoding="utf-8") as f:
            w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
            w.writeheader()
            w.writerows(rows)

    write_csv("synthetic-encounters.csv", encounters)
    write_csv("synthetic-infection-reports.csv", infections)
    (out / "synthetic-indicators.json").write_text(
        json.dumps({"seed": args.seed, "indicators": indicators}, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8")
    (out / "synthetic-oracle.json").write_text(
        json.dumps({"seed": args.seed, "oracle": oracle}, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8")

    manifest = {
        "provenance": "synthetic-rule-based",
        "generator": "tools/datagen/generate.py",
        "seed": args.seed,
        "quarters": QUARTERS,
        "encounter_rows": len(encounters),
        "infection_rows": len(infections),
        "indicator_count": len(indicators),
        "official_count": sum(1 for i in indicators if i["confidence"] == "official"),
        "draft_count": sum(1 for i in indicators if i["confidence"] == "draft"),
        "oracle_rows": len(oracle),
        "privacy": "全部脱敏，仅聚合值与口径元数据，不含患者可识别明细",
        "columns": {
            "synthetic-encounters.csv": list(encounters[0].keys()),
            "synthetic-infection-reports.csv": list(infections[0].keys()),
        },
    }
    (out / "synthetic-manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"出院记录：{len(encounters)} 行（{len(QUARTERS)} 季度）")
    print(f"院感明细：{len(infections)} 行")
    print(f"口径：{len(indicators)} 条（官方 {manifest['official_count']} / 草稿 {manifest['draft_count']}）")
    print(f"oracle：{len(oracle)} 项（{len(indicators)} 口径 × {len(QUARTERS)} 季度）")
    print(f"落盘：{out}")


if __name__ == "__main__":
    main()
