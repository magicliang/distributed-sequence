#!/bin/bash

# 设置脚本执行权限
echo "设置脚本执行权限..."

# 为启动脚本添加执行权限
chmod +x start.sh
chmod +x docker-start.sh

echo "权限设置完成！"
echo ""
echo "现在可以使用以下命令："
echo "  ./start.sh          - 传统方式启动"
echo "  ./docker-start.sh   - Docker方式启动"
echo ""
echo "Docker启动选项："
echo "  ./docker-start.sh dev      - 开发模式"
echo "  ./docker-start.sh prod     - 生产模式"
echo "  ./docker-start.sh cluster  - 集群模式"
echo "  ./docker-start.sh stop     - 停止服务"