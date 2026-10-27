#!/usr/bin/env sh
# 在用户数据根重建 Fetch 环境；不复制其它位置的 venv，不修改用户模型配置。
set -eu

JADE_FETCH_SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
JADE_FETCH_DATA_DIR="$HOME/.jadecode"
JADE_FETCH_ENV_DIR="$JADE_FETCH_DATA_DIR/mcp/fetch"
umask 077
JADE_FETCH_PYTHON_BIN="${JADE_FETCH_PYTHON:-python3}"

# 这里只检查程序版本；缺少 Python/venv 时给出错误，不自动安装系统软件或请求 sudo。
"$JADE_FETCH_PYTHON_BIN" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 10) else "Fetch requires Python >= 3.10")'
for JADE_FETCH_CHECK_DIR in "$JADE_FETCH_DATA_DIR" "$JADE_FETCH_DATA_DIR/mcp" "$JADE_FETCH_ENV_DIR" "$JADE_FETCH_ENV_DIR/.jadecode-managed"; do
    if [ -L "$JADE_FETCH_CHECK_DIR" ]; then
        echo "Refusing to write through a symlink: $JADE_FETCH_CHECK_DIR" >&2
        exit 1
    fi
done

# 只复用本脚本曾创建的目标，不覆盖归属不明的既有目录；失败后可在原位置重新执行。
if [ -e "$JADE_FETCH_ENV_DIR" ] && [ ! -f "$JADE_FETCH_ENV_DIR/.jadecode-managed" ]; then
    echo "Fetch target exists but is not managed by this script; inspect it first: $JADE_FETCH_ENV_DIR" >&2
    exit 1
fi
mkdir -p "$JADE_FETCH_ENV_DIR"
printf '%s\n' 'JadeCode Fetch environment' > "$JADE_FETCH_ENV_DIR/.jadecode-managed"

# venv 的启动器保存绝对路径，因此必须直接在最终位置创建，不能先建临时 venv 再移动。
"$JADE_FETCH_PYTHON_BIN" -m venv "$JADE_FETCH_ENV_DIR"
"$JADE_FETCH_ENV_DIR/bin/python" -m pip install --disable-pip-version-check --quiet -r "$JADE_FETCH_SCRIPT_DIR/fetch-requirements.txt"
"$JADE_FETCH_ENV_DIR/bin/python" -m pip check
"$JADE_FETCH_ENV_DIR/bin/python" -c 'import importlib.metadata; print("Fetch installed:", importlib.metadata.version("mcp-server-fetch"))'
printf '%s\n' 'Fetch is installed in the user data directory and can be shared by different projects.'
