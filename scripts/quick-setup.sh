#!/bin/bash
# 一键初始化：提取 OAuth 凭据、生成身份、创建启动配置
# 用法: bash scripts/quick-setup.sh
set -e

cd "$(dirname "$0")/.."

CONFIG="application.yml"

if [[ -f "$CONFIG" ]]; then
  echo "application.yml 已存在。启动网关..."
  exec java -jar target/gateway.jar
fi

echo "=== AI 网关快速设置 ==="
echo ""

# 1. 生成身份和令牌
DEVICE_ID=$(openssl rand -hex 32)
CLIENT_TOKEN=$(openssl rand -hex 32)
CLIENT_NAME="${1:-$(whoami)}"

# 2. 提取 OAuth 凭据
CREDS=$(security find-generic-password -a "$USER" -s "Claude Code-credentials" -w 2>/dev/null || true)
if [[ -z "$CREDS" ]]; then
  CRED_FILE="$HOME/.claude/.credentials.json"
  if [[ -f "$CRED_FILE" ]]; then
    CREDS=$(cat "$CRED_FILE")
  else
    echo "错误: 未找到 Claude Code 凭据。"
    echo "请先运行 claude 并完成浏览器 OAuth 登录，然后重新运行此脚本。"
    exit 1
  fi
fi

# 提取字段
eval "$(echo "$CREDS" | python3 -c "
import sys, json
d = json.load(sys.stdin)['claudeAiOauth']
print(f'ACCESS_TOKEN=\"{d[\"accessToken\"]}\"')
print(f'REFRESH_TOKEN=\"{d[\"refreshToken\"]}\"')
print(f'EXPIRES_AT={d.get(\"expiresAt\", 0)}')
print(f'EMAIL=\"{d.get(\"email\", \"user@example.com\")}\"')
")"

if [[ -z "$REFRESH_TOKEN" ]]; then
  echo "错误: 未能提取令牌。"
  exit 1
fi

# 3. 写入 application.yml
cat > "$CONFIG" <<YAML
server:
  port: 8443

spring:
  threads:
    virtual:
      enabled: true
  jackson:
    default-property-inclusion: non_null

logging:
  level:
    ai.gateway: INFO

gateway:
  upstream: https://api.anthropic.com

  auth:
    tokens:
      - name: ${CLIENT_NAME}
        token: ${CLIENT_TOKEN}

  oauth:
    access_token: "${ACCESS_TOKEN}"
    refresh_token: "${REFRESH_TOKEN}"
    expires_at: ${EXPIRES_AT}

  identity:
    device_id: "${DEVICE_ID}"
    email: "${EMAIL}"

  env:
    platform: darwin
    platform_raw: darwin
    arch: arm64
    node_version: $(node -v 2>/dev/null || echo "22.0.0")
    terminal: xterm-256color
    version: 2.1.81
    build_time: "2026-03-20T21:26:18Z"
    vcs: git
    deployment_environment: external
    is_ci: false
    is_claude_ai_auth: true
    is_claude_code_remote: false

  prompt_env:
    platform: darwin
    shell: zsh
    os_version: "Darwin $(uname -r)"
    working_dir: /Users/$(whoami)/projects

  process:
    constrained_memory: 17179869184
    rss_range: [300000000, 600000000]
    heap_total_range: [400000000, 700000000]
    heap_used_range: [200000000, 500000000]

  logging:
    audit: true
YAML

echo ""
echo "✓ application.yml 已生成"
echo ""

# 4. 生成客户端启动器
bash scripts/add-client.sh "${CLIENT_NAME}" "${CLIENT_TOKEN}" "localhost:8443"

echo ""
echo "启动网关..."
echo ""

exec java -jar target/gateway.jar
