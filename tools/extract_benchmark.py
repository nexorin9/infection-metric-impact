#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
官方基准口径集抽取器（可重跑、幂等、带 sha256 校验）。

来源：《病案管理质量控制指标（2021 年版）》（国卫办医函〔2021〕28 号附件）
      公开官方文件，本地语料 domain/corpus/records/mrq_control_2021_*.pdf

纪律：
  - 校验源文件 sha256 前 12 位为 757d26e50ee5，不一致立即报错中止。
  - 纯标准库解析 PDF（Identity-H + ToUnicode CMap），不依赖 pdftotext/pypdf。
  - 只抽取官方文件里真实存在的文字；分式排版在文本流中无法还原为分数式，
    故 formula 一律留空并标 missing_formula，不编造公式。
  - 分子/分母口径从官方「定义：」句抽取；抽不到的显式标注缺失。
  - 输出不含时间戳，重复执行逐字节一致。
"""
from __future__ import annotations

import hashlib
import json
import re
import sys
import zlib
from pathlib import Path

DOC_NO = "国卫办医函〔2021〕28号附件"
BASIS_PREFIX = "《病案管理质量控制指标（2021年版）》（国卫办医函〔2021〕28号附件）"
EXPECTED_SHA12 = "757d26e50ee5"
PDF_NAME = "mrq_control_2021_病案管理质量控制指标2021年版.pdf"

# 官方 27 项编号清单（来自语料 meta.json 与官方文件正文，用于差集校验）
OFFICIAL_CODES = [
    "MER-HR-01", "MER-HR-02", "MER-HR-03",
    "MER-TL-01", "MER-TL-02", "MER-TL-03", "MER-TL-04",
    "MER-ME-01", "MER-ME-02", "MER-ME-03",
    "MER-D&T-01", "MER-D&T-02", "MER-D&T-03", "MER-D&T-04",
    "MER-D&T-05", "MER-D&T-06", "MER-D&T-07", "MER-D&T-08",
    "MER-TQ-01", "MER-TQ-02", "MER-TQ-03", "MER-TQ-04",
    "MER-TQ-05", "MER-TQ-06", "MER-TQ-07", "MER-TQ-08", "MER-TQ-09",
]

# 官方四个复用分母的归属（中文序号），用于与抽取结果交叉校验
# 依据：spec.md「领域权威资产依据」+ 官方文件各项定义的分母表述
DENOM_GROUPS = {
    "同期出院患者病历总数": ["六", "七", "十九", "二十", "二十一", "二十二", "二十五", "二十七"],
    "同期住院患者病历总数": ["四", "十七"],
    "同期住院手术患者病历总数": ["五", "十四"],
    "同期出院手术患者病历总数": ["二十三", "二十四"],
}


def find_pdf() -> Path:
    here = Path(__file__).resolve()
    candidates = []
    for base in [here.parent, *here.parents]:
        candidates.append(base / "domain" / "corpus" / "records" / PDF_NAME)
    for c in candidates:
        if c.is_file():
            return c
    raise SystemExit("[抽取中止] 找不到官方语料 PDF：" + PDF_NAME)


def load_objects(data: bytes) -> dict[int, bytes]:
    objs: dict[int, bytes] = {}
    for m in re.finditer(rb"(\d+)\s+0\s+obj(.*?)\s+endobj", data, re.S):
        objs[int(m.group(1))] = m.group(2)
    return objs


def stream_bytes(body: bytes) -> bytes:
    sm = re.search(rb"stream\r?\n(.*?)\r?\nendstream", body, re.S)
    if not sm:
        return b""
    raw = sm.group(1)
    try:
        return zlib.decompress(raw)
    except zlib.error:
        return raw


def parse_cmap(body: bytes) -> dict[int, str]:
    s = stream_bytes(body).decode("latin1")
    mp: dict[int, str] = {}
    for blk in re.findall(r"beginbfchar(.*?)endbfchar", s, re.S):
        for m in re.finditer(r"<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>", blk):
            cid, u = int(m.group(1), 16), m.group(2)
            mp[cid] = "".join(chr(int(u[i:i + 4], 16)) for i in range(0, len(u), 4))
    for blk in re.findall(r"beginbfrange(.*?)endbfrange", s, re.S):
        for m in re.finditer(
                r"<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>\s*(<([0-9A-Fa-f]+)>|\[(.*?)\])",
                blk, re.S):
            lo, hi = int(m.group(1), 16), int(m.group(2), 16)
            if m.group(4):
                st = int(m.group(4), 16)
                for c in range(lo, hi + 1):
                    mp[c] = chr(st + c - lo)
            else:
                units = re.findall(r"<([0-9A-Fa-f]+)>", m.group(5))
                for i, c in enumerate(range(lo, hi + 1)):
                    if i < len(units):
                        u = units[i]
                        mp[c] = "".join(chr(int(u[k:k + 4], 16)) for k in range(0, len(u), 4))
    return mp


def decode_cids(hexstr: str, cmap: dict[int, str]) -> str:
    out = []
    for i in range(0, len(hexstr), 4):
        cid = int(hexstr[i:i + 4], 16)
        out.append(cmap.get(cid, ""))
    return "".join(out)


def extract_text(data: bytes) -> str:
    objs = load_objects(data)
    font_cmap: dict[int, dict[int, str]] = {}
    for num, body in objs.items():
        m = re.search(rb"/ToUnicode\s+(\d+)\s+0\s+R", body)
        if m and int(m.group(1)) in objs:
            font_cmap[num] = parse_cmap(objs[int(m.group(1))])
    pages = sorted(n for n, b in objs.items()
                   if b"/Type /Page" in b and b"/Type /Pages" not in b)
    chunks = []
    for pn in pages:
        page = objs[pn]
        fmap: dict[str, int] = {}
        rm = re.search(rb"/Font\s*<<(.*?)>>", page, re.S)
        if rm:
            fmap = {k.decode(): int(v) for k, v in
                    re.findall(rb"/(FT\d+)\s+(\d+)\s+0\s+R", rm.group(1))}
        cm = re.search(rb"/Contents\s+(\d+)\s+0\s+R", page)
        if not cm or int(cm.group(1)) not in objs:
            continue
        content = stream_bytes(objs[int(cm.group(1))]).decode("latin1")
        cur: dict[int, str] = {}
        out: list[str] = []
        pattern = (r"/(FT\d+)\s+[\d.]+\s+Tf|<([0-9A-Fa-f]+)>\s*Tj|"
                   r"\[(.*?)\]\s*TJ|(T\*)")
        for tok in re.finditer(pattern, content, re.S):
            if tok.group(1):
                cur = font_cmap.get(fmap.get(tok.group(1), -1), {})
            elif tok.group(2):
                out.append(decode_cids(tok.group(2), cur))
            elif tok.group(3) is not None:
                for h in re.findall(r"<([0-9A-Fa-f]+)>", tok.group(3)):
                    out.append(decode_cids(h, cur))
            elif tok.group(4):
                out.append("\n")
        chunks.append("".join(out))
    return "\n".join(chunks)


def parse_indicators(text: str) -> list[dict]:
    # 标题形如「指标六、出院记录24小时内完成率（MER-TL-03）」
    head = re.compile(r"指标([一二三四五六七八九十]+)[、，]\s*([^（\n]+?)（(MER-[A-Z&]+-\d+)）")
    matches = list(head.finditer(text))
    records = []
    for i, m in enumerate(matches):
        no, title, code = m.group(1), m.group(2).strip(), m.group(3)
        seg = text[m.end(): matches[i + 1].start() if i + 1 < len(matches) else len(text)]
        dm = re.search(r"定义：(.+?)(?:计算公式：|说明：|$)", seg, re.S)
        definition = re.sub(r"\s+", "", dm.group(1)) if dm else ""
        nm = re.search(r"说明：(.+?)$", seg, re.S)
        notes = re.sub(r"\s+", "", nm.group(1)) if nm else ""
        # 分子/分母只从官方定义句抽取，不推断、不编造
        numerator, denominator = "", ""
        pm = re.search(r"单位时间内[，,](.+?)占(.+?)的比例", definition)
        if pm:
            numerator = pm.group(1).strip()
            denominator = pm.group(2).strip()
        elif "月均负担" in title:
            wm = re.search(r"实际工作总月数|工作总月数", seg)
            denominator = wm.group(0) if wm else ""
            numerator = "同期" + title.replace("月均负担", "")
        records.append({
            "indicator_no": "指标" + no,
            "indicator_code": code,
            "title": title,
            "definition": definition,
            "numerator_text": numerator,
            "denominator_text": denominator,
            "denominator_key": denominator if denominator in DENOM_GROUPS else None,
            "formula": None,
            "missing_formula": True,  # PDF 分式为图形排版，文本流不能还原分数式
            "notes": notes,
            "basis": BASIS_PREFIX + " " + "指标" + no,
            "confidence": "official",
            "_no": no,
        })
    return records


def main() -> int:
    pdf = find_pdf()
    data = pdf.read_bytes()
    sha12 = hashlib.sha256(data).hexdigest()[:12]
    if sha12 != EXPECTED_SHA12:
        print(f"[抽取中止] 源文件 sha256 前 12 位不符：期望 {EXPECTED_SHA12}，实际 {sha12}",
              file=sys.stderr)
        return 1

    meta_path = pdf.with_suffix("").with_suffix(".meta.json") if False else \
        Path(str(pdf)[:-4] + ".meta.json")
    meta = {}
    if meta_path.is_file():
        meta = json.loads(meta_path.read_text(encoding="utf-8"))

    text = extract_text(data)
    records = parse_indicators(text)
    codes = [r["indicator_code"] for r in records]

    missing_codes = [c for c in OFFICIAL_CODES if c not in codes]
    extra_codes = [c for c in codes if c not in OFFICIAL_CODES]

    # 与官方四个复用分母的归属清单交叉校验
    group_check = {}
    for denom, nos in DENOM_GROUPS.items():
        actual = sorted(r["indicator_code"] for r in records if r["_no"] in nos
                        and r["denominator_key"] == denom)
        expected = sorted(r["indicator_code"] for r in records if r["_no"] in nos)
        group_check[denom] = {
            "expected_count": len(nos),
            "actual_count": len(actual),
            "codes": expected,
            "matched": len(actual) == len(expected),
        }

    for r in records:
        r.pop("_no", None)

    out = {
        "source": {
            "title": meta.get("title", "病案管理质量控制指标(2021年版)"),
            "doc_no": meta.get("doc_no", DOC_NO),
            "issuer": meta.get("issuer", "国家卫生健康委办公厅"),
            "source_url": meta.get("source_url", ""),
            "file": pdf.name,
            "sha256_12": sha12,
            "license_note": meta.get("license_note", "官方公开文件，仅用于内部领域知识库建设"),
            "extraction": "纯标准库解析 PDF Identity-H + ToUnicode CMap；分式版式不可还原为分数式，故不产出公式",
        },
        "denominator_groups": group_check,
        "indicators": records,
    }

    dest = Path(__file__).resolve().parents[1] / "src/main/resources/benchmarks/mrq_control_2021.json"
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_text(json.dumps(out, ensure_ascii=False, indent=2, sort_keys=False) + "\n",
                    encoding="utf-8")

    missing_formula = sum(1 for r in records if r["missing_formula"])
    print(f"抽出项数：{len(records)}")
    print(f"缺公式项数：{missing_formula}（官方分式为图形排版，文本流不可还原，一律不编造）")
    print(f"与官方编号清单的差集：缺失 {missing_codes} / 多余 {extra_codes}")
    for denom, g in group_check.items():
        print(f"分母「{denom}」：期望 {g['expected_count']} 项，实际匹配 {g['actual_count']} 项，"
              f"对齐={'是' if g['matched'] else '否'}")
    print(f"落盘：{dest}")
    if len(records) != 27 or missing_codes or extra_codes:
        print("[抽取中止] 27 项编号不齐或差集非空", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
