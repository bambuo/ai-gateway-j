#!/bin/bash
# 生产部署：提取凭据、生成配置、构建 Docker、启动网关
# 用法: bash scripts/admin-setup.sh
set -e

cd "$(dirname "$0")/.."

CONFIG="application.yml"

if [[ -f "$CONFIG" ]]; then
  echo "application.yml 已存在。启动网关..."
  if command -v docker &>/dev/null && docker info &>/dev/null 2>&1; then
    docker compose up -d --build
  else
    echo "Docker 不可用，使用 Java 启动..."
    java -jar target/gateway.jar
  fi
  echo ""
  echo "网关已运行。添加客户端:"
  echo "  bash scripts/add-client.sh <客户端名>"
  exit 0
fi

echo "=== AI 网关管理部署 ==="
echo ""

# ── 1. 提取 OAuth 凭据 ──
echo "步骤 1/4: 提取 OAuth 凭据..."

CREDS=$(security find-generic-password -a "$USER" -s "Claude Code-credentials" -w 2>/dev/null || true)
if [[ -z "$CREDS" ]]; then
  CRED_FILE="$HOME/.claude/.credentials.json"
  if [[ -f "$CRED_FILE" ]]; then
    CREDS=$(cat "$CRED_FILE")
  else
    echo "错误: 未找到 Claude Code 凭据。请先运行 claude 并完成浏览器登录。"
    exit 1
  fi
fi

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
echo "✓ OAuth 凭据已提取"
echo ""

# ── 2. 部署模式 ──
echo "步骤 2/4: 选择部署模式"
echo "  1) 公网/LAN — 客户端通过网络连接（HTTPS，自动生成自签名证书）"
echo "  2) Tailscale/VPN — 隧道已加密（HTTP，无需证书）"
echo ""
read -p "选择 [1/2]: " DEPLOY_MODE
DEPLOY_MODE="${DEPLOY_MODE:-1}"

# ── 3. 网关地址 ──
echo ""
echo "步骤 3/4: 配置网络"
DEFAULT_IP=$(ipconfig getifaddr en0 2>/dev/null || hostname -I 2>/dev/null | awk '{print $1}' || echo "0.0.0.0")
read -p "客户端连接的网关地址 [${DEFAULT_IP}]: " GATEWAY_HOST
GATEWAY_HOST="${GATEWAY_HOST:-${DEFAULT_IP}}"

GATEWAY_SCHEME="http"
GATEWAY_PORT="8443"
SSL_CONFIG=""

if [[ "$DEPLOY_MODE" == "1" ]]; then
  GATEWAY_SCHEME="https"
  mkdir -p certs

  if [[ -f certs/cert.pem && -f certs/key.pem ]]; then
    echo "✓ 已有 TLS 证书 certs/ 目录"
  else
    echo "生成自签名 TLS 证书..."
    openssl req -x509 -newkey rsa:2048 \
      -keyout certs/key.pem -out certs/cert.pem \
      -days 365 -nodes \
      -subj "/CN=${GATEWAY_HOST}" \
      -addext "subjectAltName=IP:${GATEWAY_HOST},DNS:${GATEWAY_HOST}" \
      2>/dev/null
    echo "✓ TLS 证书已生成（有效期 365 天）"
  fi

  SSL_CONFIG="
server:
  ssl:
    cert: certs/cert.pem
    cert-key: certs/key.pem"
fi

GATEWAY_URL="${GATEWAY_SCHEME}://${GATEWAY_HOST}:${GATEWAY_PORT}"

# ── 4. 生成配置 ──
echo ""
echo "步骤 4/4: 生成配置文件和客户端"
DEVICE_ID=$(openssl rand -hex 32)
CLIENT_TOKEN=$(openssl rand -hex 32)

cat > "$CONFIG" <<YAML
server:
  port: ${GATEWAY_PORT}
${SSL_CONFIG}

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
      - name: 管理员
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
    node_version: 22.0.0
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
    working_dir: /Users/jack/projects

  process:
    constrained_memory: 17179869184
    rss_range: [300000000, 600000000]
    heap_total_range: [400000000, 700000000]
    heap_used_range: [200000000, 500000000]

  logging:
    audit: true
YAML

echo "✓ 配置已生成: $CONFIG"
echo ""

# ── 5. 生成管理员客户端启动器 ──
bash scripts/add-client.sh "管理员" "${CLIENT_TOKEN}" "${GATEWAY_HOST}:${GATEWAY_PORT}" "${GATEWAY_SCHEME}"

echo ""
echo "╔══════════════════════════════════════════════════════╗"
echo "║              AI 网关部署完成                         ║"
echo "╠══════════════════════════════════════════════════════╣"
echo "║  网关地址:  ${GATEWAY_URL}                        "
echo "║  网关启动:  java -jar target/gateway.jar             "
echo "║  管理密钥:  cat clients/cc-管理员                   "
echo "║  添加客户端: bash scripts/add-client.sh <名称>      "
echo "╚══════════════════════════════════════════════════════╝"
echo ""
echo "多机部署拓扑:"
echo ""
echo "  机器A ──┐"
echo "  机器B ──┼──→ ${GATEWAY_URL} ──→ api.anthropic.com"
echo "  机器C ──┘"
echo ""
echo "重要: 所有机器（包括管理机）都应通过网关使用 Claude Code。"
echo "管理机直连会产生第二个设备指纹，被 Anthropic 可见。"
