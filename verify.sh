#!/usr/bin/env bash
# 发布前可重复验证：安装检查 -> 配置与凭据位检查 -> 全量测试 -> 打包 -> 环境自检
# 一条命令跑通即视为 infection-metric-impact/ 可直接作为仓库内容发布。
# 全程离线；令牌只显示「已配置 / 未配置」，不打印明文。
set -euo pipefail

APP_HOME="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$APP_HOME"

STEP=0
step() { STEP=$((STEP + 1)); echo "[verify] ${STEP}/5 $1"; }

# ---- 1. 安装检查 ----
step "安装检查（JDK 17 + Maven）"
if [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME}/bin/java" ]]; then
  for candidate in /opt/homebrew/opt/openjdk@17 /usr/libexec/java_home; do
    if [[ -x "${candidate}/bin/java" ]]; then
      export JAVA_HOME="${candidate}"
      break
    fi
  done
fi
if [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME}/bin/java" ]]; then
  echo "[verify] 失败：未找到 JDK 17，请安装或设置 JAVA_HOME" >&2
  exit 1
fi
"${JAVA_HOME}/bin/java" -version 2>&1 | head -1
if ! command -v mvn >/dev/null 2>&1; then
  echo "[verify] 失败：未找到 mvn" >&2
  exit 1
fi
mvn -version 2>&1 | head -1
for f in pom.xml run.sh README.md .env.example qrcode.jpg; do
  test -f "$f" || { echo "[verify] 失败：缺少交付文件 $f" >&2; exit 1; }
done
echo "[verify] 交付外壳文件齐全"

# ---- 2. 配置与凭据位检查 ----
step "配置与凭据位检查"
VARS=(EMR_BASE_URL EMR_API_TOKEN HAI_BASE_URL HAI_API_TOKEN \
      QC_METRICS_BASE_URL QC_METRICS_TOKEN REGIONAL_PLATFORM_BASE_URL REGIONAL_TOKEN \
      LLM_BASE_URL LLM_API_KEY LLM_MODEL LLM_TIMEOUT LLM_MAX_RETRIES)
missing=0
for v in "${VARS[@]}"; do
  grep -q "^${v}=" .env.example || { echo "[verify] .env.example 缺变量：${v}" >&2; missing=1; }
done
[[ "$missing" -eq 0 ]] || { echo "[verify] 失败：.env.example 不完整" >&2; exit 1; }
echo "[verify] .env.example 共 ${#VARS[@]} 项变量名齐全"
for v in "${VARS[@]}"; do
  if [[ -n "${!v:-}" ]]; then
    echo "      ${v}：已配置（值不回显）"
  else
    echo "      ${v}：未配置（离线验证与演示不依赖真实端点）"
  fi
done
if [[ -f .env ]]; then
  echo "[verify] 注意：存在本地 .env，发布前应删除（只保留 .env.example）"
fi

# ---- 3. 全量测试 ----
step "全量单元测试（离线）"
mvn -o -q test
echo "[verify] mvn -o -q test 通过"

# ---- 4. 打包 ----
step "打包可运行 fat-jar"
mvn -o -q package -DskipTests
JAR="target/infection-metric-impact.jar"
test -f "$JAR" || { echo "[verify] 失败：未生成 $JAR" >&2; exit 1; }
echo "[verify] 生成 $JAR ($(wc -c < "$JAR" | tr -d ' ') 字节)"

# ---- 5. 环境自检 ----
step "配置与端点自检"
./run.sh --doctor

echo "[verify] 五段全部通过：安装 / 配置 / 测试 / 打包 / 自检"
