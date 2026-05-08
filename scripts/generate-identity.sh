#!/bin/bash
# 生成标准设备身份（64 位十六进制设备 ID）
# 用法: bash scripts/generate-identity.sh

echo "生成的标准设备身份:"
echo ""
echo "gateway:"
echo "  identity:"
echo "    device_id: \"$(openssl rand -hex 32)\""
echo ""
echo "将此配置放入 application.yml 中。所有客户端经过网关后将显示为此设备。"
