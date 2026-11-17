#!/usr/bin/env bash
# 采集真实运行截图：真跑一次复算 -> 保存终端回显 -> 从屏幕截取真实 Swing 窗口
# 可重复执行；产物落 docs/screenshots/，回显文本落 out/offline-sample/terminal-session.txt
set -euo pipefail

APP_HOME="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$APP_HOME"

if [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME}/bin/java" ]]; then
  for candidate in /opt/homebrew/opt/openjdk@17 /usr/libexec/java_home; do
    if [[ -x "${candidate}/bin/java" ]]; then
      export JAVA_HOME="${candidate}"
      break
    fi
  done
fi
if [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME}/bin/java" ]]; then
  echo "[capture] 未找到 JDK 17，请设置 JAVA_HOME" >&2
  exit 1
fi

JAR="target/infection-metric-impact.jar"
mkdir -p out/offline-sample docs/screenshots

echo "[capture] 1/3 离线构建 fat-jar"
mvn -o -q package -DskipTests

echo "[capture] 2/3 真跑复算并保存终端回显"
./run.sh --offline-sample --headless 2>&1 | tee out/offline-sample/terminal-session.txt

echo "[capture] 3/3 截取真实窗口与终端会话"
"${JAVA_HOME}/bin/java" -Dfile.encoding=UTF-8 -Duser.language=zh -Duser.country=CN \
  -cp "$JAR" com.infection.metric.app.ScreenshotCapture

echo "[capture] 完成："
ls -l docs/screenshots/
