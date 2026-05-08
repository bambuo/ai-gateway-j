#!/bin/bash
# 提取 Claude Code OAuth 凭据（刷新令牌）
# 在已通过浏览器登录 Claude Code 的管理机器上运行
#
# 用法: bash scripts/extract-token.sh

set -e

echo "=== 提取 Claude Code OAuth 令牌 ==="
echo ""

# 尝试从 macOS Keychain 获取（macOS 默认）
CREDS=$(security find-generic-password -a "$USER" -s "Claude Code-credentials" -w 2>/dev/null || true)

if [[ -z "$CREDS" ]]; then
  # 回退：检查 .credentials.json
  CRED_FILE="$HOME/.claude/.credentials.json"
  if [[ -f "$CRED_FILE" ]]; then
    CREDS=$(cat "$CRED_FILE")
    echo "来源: ~/.claude/.credentials.json"
  else
    echo "错误: 未找到凭据文件"
    echo ""
    echo "请先在当前机器上登录 Claude Code:"
    echo "  1. 运行: claude"
    echo "  2. 在浏览器中完成 OAuth 登录"
    echo "  3. 重新运行此脚本"
    exit 1
  fi
else
  echo "来源: macOS Keychain"
fi

# 提取 4 个字段
eval "$(echo "$CREDS" | python3 -c "
import sys, json
d = json.load(sys.stdin)['claudeAiOauth']
print(f'ACCESS_TOKEN=\"{d[\"accessToken\"]}\"')
print(f'REFRESH_TOKEN=\"{d[\"refreshToken\"]}\"')
print(f'EXPIRES_AT={d.get(\"expiresAt\", 0)}')
print(f'EMAIL=\"{d.get(\"email\", \"user@example.com\")}\"')
")"

if [[ -z "$REFRESH_TOKEN" ]]; then
  echo "错误: 未能提取 refreshToken"
  exit 1
fi

# 显示脱敏令牌
MASKED="${REFRESH_TOKEN:0:20}...${REFRESH_TOKEN: -6}"
echo ""
echo "刷新令牌已提取: $MASKED"
echo ""

echo "将这些值添加到 application.yml 的 gateway 节点下:"
echo ""
echo "gateway:"
echo "  oauth:"
echo "    access_token: \"$ACCESS_TOKEN\""
echo "    refresh_token: \"$REFRESH_TOKEN\""
echo "    expires_at: $EXPIRES_AT"
echo ""
echo "  identity:"
echo "    email: \"$EMAIL\""
echo ""

cat <<WARN
╔══════════════════════════════════════════════════════╗
║  重要：此机器也应配置为通过网关使用 Claude Code     ║
║  不要继续在此机器上直连使用 claude 命令              ║
╚══════════════════════════════════════════════════════╝
WARN
