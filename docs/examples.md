# 输出样例 · 完整版

本文件收录各产物的完整回显与片段。README 只保留代表性摘录。

## 环境自检（`./run.sh --doctor`）

```text
=== 院感指标口径影响分析 · 环境自检 ===

[1/3] 配置项与凭据位
      EMR_BASE_URL                 未配置
      EMR_API_TOKEN                未配置
      HAI_BASE_URL                 未配置
      HAI_API_TOKEN                未配置
      QC_METRICS_BASE_URL          未配置
      QC_METRICS_TOKEN             未配置
      REGIONAL_PLATFORM_BASE_URL   未配置
      REGIONAL_TOKEN               未配置
      LLM_BASE_URL                 未配置
      LLM_API_KEY                  未配置
      LLM_MODEL                    未配置
      LLM_TIMEOUT                  未配置
      LLM_MAX_RETRIES              未配置

[2/3] 端点可达性（不携带凭据）
      EMR_BASE_URL                 未配置地址，跳过探测
      HAI_BASE_URL                 未配置地址，跳过探测
      QC_METRICS_BASE_URL          未配置地址，跳过探测
      REGIONAL_PLATFORM_BASE_URL   未配置地址，跳过探测

[3/3] 本地口径库可读写
      data/library                 尚未创建（首次运行时自动生成）
      data/runs                    尚未创建（首次运行时自动生成）
      out/impact                   尚未创建（首次运行时自动生成）

自检结论：缺少 13 项配置 —— EMR_BASE_URL、EMR_API_TOKEN、HAI_BASE_URL、HAI_API_TOKEN、QC_METRICS_BASE_URL、QC_METRICS_TOKEN、REGIONAL_PLATFORM_BASE_URL、REGIONAL_TOKEN、LLM_BASE_URL、LLM_API_KEY、LLM_MODEL、LLM_TIMEOUT、LLM_MAX_RETRIES
           复制 .env.example 为 .env 后填写；令牌不会写入日志。
```

配置齐全时，令牌类配置项只显示「已配置（长度 N）」，不显示明文；端点探测按状态码区分「可达 / 可达但未授权 / 不可达」。

## 离线演示（`./run.sh --offline-sample`）

```text
=== 离线演示 · 脱敏最小样例 ===
样例标识：offline-sample-001
来源标注：bundled-minimal
口径条目：3 条
出院记录：4 条
院感明细：2 条

口径清单（编码 / 版本 / 状态 / 名称）：
      HAI-INC-01     2026.1     PUBLISHED        院感报告例数（演示口径）
      HAI-INC-01     2026.2     DRAFT            院感报告例数（演示口径 · 分子收窄版）
      HAI-MDR-01     2026.1     DRAFT            多重耐药菌感染例数（演示口径）

样例口径清单落盘：out/offline-sample/offline-sample-summary.txt
提示：此通道仅作离线演示；默认启动路径指向真实院内端点，不使用本地样例。
```

## 复算运行摘要（`./run.sh --headless`）

```text
=== 复算运行摘要 ===
运行标识：run-20261109-044107
已发布口径：0 条　草稿口径：0 条
失败步骤：1 项
摘要落盘：out/runs/2026/run-20261109-044107/summary.json
说明：本地口径库为空或无已发布口径，未产生任何复算数字（不按 0 充数）。
```

对应的 `summary.json` 结构：

```json
{
  "run_id": "run-20261109-044107",
  "started_at": "2026-11-09T04:41:07",
  "mode": "headless",
  "library_root": "data/library",
  "published_count": 0,
  "draft_count": 0,
  "indicators": [],
  "failed_steps": [
    "本地口径库中无已发布口径，本次复算未执行（0 项）；复算引擎待 M4 接入"
  ],
  "unpublished_indicators": [],
  "result_quarter": "未落库（无已发布口径）"
}
```

## 口径变更影响清单（JSON）

```json
{
  "indicator_code": "HAI-INC-01",
  "definition_version": "2026.2",
  "affected_indicators": [
    {
      "indicator_code": "HAI-MDR-01",
      "title": "多重耐药菌感染例数",
      "definition_version": "2026.1",
      "reason": "复用同期出院患者病历总数分母",
      "needs_recompute_quarters": ["2026-Q1", "2026-Q2"]
    }
  ],
  "change_note": "分子新增排除条件：切口等级不等于 Ⅲ"
}
```

同目录另有 CSV 导出，列名用中文业务名，便于 Excel 直接打开：

```csv
受影响指标编码,指标名称,需重算季度,说明,来源
HAI-MDR-01,多重耐药菌感染例数,2026-Q1;2026-Q2,复用同期出院患者病历总数分母,口径 HAI-INC-01 / 2026.2
```

## 重算差异说明（三段）

```text
【分子差】本期 42 例，上期口径 51 例，差 -9 例（来源：口径 HAI-INC-01 / 2026.2，复算运行 run-20261109-044107）
【分母差】本期 1180 例，上期口径 1204 例，差 -24 例（来源：口径 HAI-INC-01 / 2026.2，复算运行 run-20261109-044107）
【口径差】1 条：分子新增排除条件（切口等级不等于 Ⅲ）；0 条移除；0 条排除新增
```

三段各带数字与来源引用（口径编码 + 版本 + 变更条目）；缺任一段时报错而不是留空。同一份输入两次渲染逐字一致（不含时间戳类字段）。

## 上级口径包三清单

```text
新增：2 条
实质口径变更：3 条（本院例外：11 条）
逐条确认进度：已确认 0 / 5，未确认条目不得进入合并
```

## 发布回执

```text
回执编号：recv-20261109-044120
指标编码：HAI-INC-01　口径版本：2026.2
变更条目：2 条　确有变更：是　端点返回标识：qc-def-88231
幂等键：sha256(HAI-INC-01|2026.2)
```

回执按指标编码可反查；同一幂等键重复提交不会产生第二条回执。
