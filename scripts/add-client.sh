#!/bin/bash
# 为客户端生成令牌和启动脚本
# 用法: bash scripts/add-client.sh <客户端名> [令牌] [网关地址] [协议(http/https)]
#
# 省略令牌和地址时自动生成并默认本地地址
set -e

cd "$(dirname "$0")/.."

CLIENT_NAME="${1:?用法: add-client.sh <客户端名> [令牌] [网关地址] [协议]}"
CLIENT_TOKEN="${2:-$(openssl rand -hex 32)}"
GATEWAY_ADDR="${3:-localhost:8443}"
GATEWAY_SCHEME="${4:-http}"

CONFIG="application.yml"
CLIENTS_DIR="clients"
mkdir -p "$CLIENTS_DIR"

# 如果自动生成了令牌，追加到配置
if [[ -z "$2" ]]; then
  python3 -c "
import yaml, sys
with open('$CONFIG') as f:
    cfg = yaml.safe_load(f)
gateway = cfg.get('gateway', {})
tokens = gateway.get('auth', {}).get('tokens', [])
tokens.append({'name': '$CLIENT_NAME', 'token': '$CLIENT_TOKEN'})
if 'gateway' not in cfg: cfg['gateway'] = {}
if 'auth' not in cfg['gateway']: cfg['gateway']['auth'] = {}
cfg['gateway']['auth']['tokens'] = tokens
with open('$CONFIG', 'w') as f:
    yaml.dump(cfg, f, default_flow_style=False, sort_keys=False, allow_unicode=True)
" 2>/dev/null || echo "注意: 无法自动更新配置，请手动添加密钥"
  echo "✓ 令牌已添加到 application.yml（需重启网关生效）"
fi

# 生成客户端启动脚本
LAUNCHER="${CLIENTS_DIR}/cc-${CLIENT_NAME}"
cat > "$LAUNCHER" <<SCRIPT_HEAD
#!/bin/bash
# AI Gateway 客户端启动器
#
# 用法:
#   ./cc-<名称>                   启动 Claude Code 并路由到网关
#   ./cc-<名称> --print "hello"   单次执行模式
#   ./cc-<名称> 安装              安装为 'ccg' 系统命令
#   ./cc-<名称> 卸载              移除 'ccg' 恢复原生 claude
#   ./cc-<名称> 原生              绕过网关运行原生 claude（一次）
SCRIPT_HEAD

cat >> "$LAUNCHER" <<SCRIPT_VARS
GATEWAY_URL="${GATEWAY_SCHEME}://${GATEWAY_ADDR}"
CLIENT_TOKEN="${CLIENT_TOKEN}"
SCRIPT_VARS

cat >> "$LAUNCHER" <<'SCRIPT_BODY'

INSTALL_PATH="/usr/local/bin/ccg"
SELF_PATH="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"

case "$SHELL" in
  */zsh)  RC_FILE="${ZDOTDIR:-$HOME}/.zshrc" ;;
  */bash) RC_FILE="$HOME/.bashrc" ;;
  */fish) RC_FILE="${XDG_CONFIG_HOME:-$HOME/.config}/fish/config.fish" ;;
  *)      RC_FILE="$HOME/.profile" ;;
esac
ALIAS_TAG="# ai-gateway alias"

case "$1" in
  安装|install)
    cp "$0" "$INSTALL_PATH" 2>/dev/null || sudo cp "$0" "$INSTALL_PATH"
    chmod +x "$INSTALL_PATH"
    echo "已安装为 'ccg' 命令。"
    echo ""
    echo "  ccg           启动 Claude Code（路由到网关）"
    echo "  ccg 劫持      让 'claude' 也走网关"
    echo "  ccg 释放      恢复 'claude' 为原生"
    echo "  ccg 状态      查看网关连接状态"
    echo "  ccg 帮助      显示帮助"
    exit 0
    ;;

  卸载|uninstall)
    rm "$INSTALL_PATH" 2>/dev/null || sudo rm "$INSTALL_PATH"
    if grep -q "$ALIAS_TAG" "$RC_FILE" 2>/dev/null; then
      sed -i.bak "/$ALIAS_TAG/d" "$RC_FILE"
      rm -f "${RC_FILE}.bak"
    fi
    echo "已卸载。'claude' 已恢复为原生版本。"
    exit 0
    ;;

  劫持|hijack)
    if grep -q "$ALIAS_TAG" "$RC_FILE" 2>/dev/null; then
      echo "劫持已启用。运行 'ccg 释放' 恢复。"
    else
      if [[ "$SHELL" == */fish ]]; then
        echo "alias claude 'ccg' $ALIAS_TAG" >> "$RC_FILE"
      else
        echo "alias claude='ccg' $ALIAS_TAG" >> "$RC_FILE"
      fi
      echo "完成。现在输入 'claude' 将走网关。"
      echo "  新建终端: 自动生效"
      echo "  当前终端: 重新打开或运行: source $RC_FILE"
      echo "  随时恢复: ccg 释放"
    fi
    exit 0
    ;;

  释放|release)
    if grep -q "$ALIAS_TAG" "$RC_FILE" 2>/dev/null; then
      sed -i.bak "/$ALIAS_TAG/d" "$RC_FILE"
      rm -f "${RC_FILE}.bak"
      unalias claude 2>/dev/null
      echo "完成。'claude' 已恢复为原生。"
    else
      echo "无需恢复 — 'claude' 已是原生。"
    fi
    exit 0
    ;;

  原生|native)
    shift
    exec command claude "$@"
    ;;

  状态|status)
    echo "网关地址:  $GATEWAY_URL"
    if grep -q "$ALIAS_TAG" "$RC_FILE" 2>/dev/null; then
      echo "劫持状态:  已启用  (claude → 网关)"
    else
      echo "劫持状态:  已关闭  (claude = 原生)"
    fi
    HEALTH=$(curl -sk --max-time 3 "${GATEWAY_URL}/_health" 2>/dev/null)
    if [[ -n "$HEALTH" ]]; then
      echo "网关状态:  OK"
    else
      echo "网关状态:  不可达"
    fi
    exit 0
    ;;

  帮助|help|--help|-h)
    echo "ccg — AI 网关客户端"
    echo ""
    echo "用法:"
    echo "  ccg                   启动 Claude Code（路由到网关）"
    echo "  ccg [claude 参数]     传递任意参数给 Claude Code"
    echo "  ccg --print \"你好\"    单次执行模式"
    echo ""
    echo "设置:"
    echo "  ccg 安装              安装为 'ccg' 系统命令"
    echo "  ccg 卸载              移除 'ccg' 恢复原生 claude"
    echo ""
    echo "路由:"
    echo "  ccg 劫持              让 'claude' 走网关"
    echo "  ccg 释放              恢复 'claude' 为原生"
    echo "  ccg 原生 [参数]       绕过网关运行一次原生 claude"
    echo ""
    echo "信息:"
    echo "  ccg 状态              查看网关和劫持状态"
    echo "  ccg 帮助              显示此帮助"
    exit 0
    ;;
esac

# ── 主逻辑: 通过网关启动 ──

if ! command -v claude &>/dev/null; then
  echo "错误: 未找到 'claude' 命令。请先安装 Claude Code:"
  echo "  npm install -g @anthropic-ai/claude-code"
  exit 1
fi

export ANTHROPIC_API_KEY="$CLIENT_TOKEN"
export ANTHROPIC_BASE_URL="$GATEWAY_URL"
export CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1
export CLAUDE_CODE_ATTRIBUTION_HEADER=false

HEALTH=$(curl -sk --max-time 3 "${GATEWAY_URL}/_health" 2>/dev/null)
if [[ -z "$HEALTH" ]]; then
  echo "警告: 网关 ${GATEWAY_URL} 不可达，请确保网关正在运行。"
  echo ""
fi

exec claude "$@"
SCRIPT_BODY

chmod +x "$LAUNCHER"
echo ""
echo "客户端启动器已生成: $LAUNCHER"
echo "  用法: $LAUNCHER"
echo "  或发给客户端，让对方执行: $LAUNCHER 安装"
