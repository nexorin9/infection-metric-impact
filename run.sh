#!/usr/bin/env bash
# 院感指标口径影响分析 · 一键入口
#
#   ./run.sh                       启动桌面窗口（默认形态）
#   ./run.sh --headless            跑一次复算并打印运行摘要（运维排障 / CI）
#   ./run.sh --doctor              配置项、凭据位、端点可达性自检
#   ./run.sh --offline-sample      离线演示（读脱敏样例；显式开关，非默认路径）
#
# 凭据一律从环境变量读取；本脚本不内嵌任何令牌。
set -euo pipefail

APP_HOME="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="${APP_HOME}/target/infection-metric-impact.jar"
MAIN_CLASS="com.infection.metric.app.AppLauncher"

# ---- 运行时 ----
if [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME}/bin/java" ]]; then
  for candidate in /opt/homebrew/opt/openjdk@17 /usr/libexec/java_home; do
    if [[ -x "${candidate}/bin/java" ]]; then
      export JAVA_HOME="${candidate}"
      break
    fi
  done
fi
if [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME}/bin/java" ]]; then
  echo "[run.sh] 未找到可用的 Java 运行时，请先安装 JDK 17 或设置 JAVA_HOME" >&2
  exit 1
fi
JAVA_BIN="${JAVA_HOME}/bin/java"

# ---- 构建（fat-jar 不存在时先构建） ----
if [[ ! -f "${JAR}" ]]; then
  echo "[run.sh] 未找到 ${JAR}，开始离线构建……" >&2
  if ! (cd "${APP_HOME}" && mvn -o -q package -DskipTests); then
    echo "[run.sh] 离线构建失败，改为带测试构建……" >&2
    (cd "${APP_HOME}" && mvn -o -q package)
  fi
fi

# ---- 装载 .env（若存在；不覆盖已有环境变量） ----
if [[ -f "${APP_HOME}/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "${APP_HOME}/.env"
  set +a
fi

exec "${JAVA_BIN}" \
  -Dfile.encoding=UTF-8 \
  -Duser.language=zh \
  -Duser.country=CN \
  -jar "${JAR}" \
  "$@"
