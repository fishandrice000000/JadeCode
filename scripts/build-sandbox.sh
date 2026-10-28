#!/bin/sh
# 只把构建所需文件放入临时 context，避免上传工作区中的凭据或无关材料。
set -eu
task_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
task_context=$(mktemp -d)
trap 'rm -rf -- "$task_context"' EXIT HUP INT TERM
test -f "$task_root/target/jadecode.jar" || { echo 'Run mvn -q package first' >&2; exit 1; }
cp "$task_root/target/jadecode.jar" "$task_context/jadecode.jar"
cp "$task_root/docker/sandbox/Dockerfile" "$task_context/Dockerfile"
cp "$task_root/docker/sandbox/init.py" "$task_context/init.py"
cp "$task_root/scripts/fetch-requirements.txt" "$task_context/fetch-requirements.txt"
docker build --tag jadecode-sandbox:1 "$task_context"
