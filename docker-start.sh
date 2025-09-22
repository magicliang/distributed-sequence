#!/bin/bash

# 分布式ID生成器 Docker 快速启动脚本
# 使用方法: ./docker-start.sh [选项]
# 选项:
#   dev     - 开发模式 (H2数据库)
#   prod    - 生产模式 (MySQL数据库)
#   cluster - 集群模式 (多实例 + 负载均衡)
#   stop    - 停止所有服务
#   clean   - 清理所有容器和镜像

set -e

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# 打印带颜色的消息
print_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

print_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# 检查Docker是否安装
check_docker() {
    if ! command -v docker &> /dev/null; then
        print_error "Docker 未安装，请先安装 Docker"
        exit 1
    fi
    
    if ! command -v docker-compose &> /dev/null; then
        print_error "Docker Compose 未安装，请先安装 Docker Compose"
        exit 1
    fi
}

# 开发模式启动
start_dev() {
    print_info "启动开发模式 (H2数据库)..."
    docker build -t id-generator:latest .
    docker run -d \
        --name id-generator-dev \
        -p 8080:8080 \
        -e SPRING_PROFILES_ACTIVE=h2 \
        id-generator:latest
    
    print_success "开发模式启动成功!"
    print_info "访问地址: http://localhost:8080"
    print_info "H2控制台: http://localhost:8080/h2-console"
}

# 生产模式启动
start_prod() {
    print_info "启动生产模式 (MySQL数据库)..."
    docker-compose up -d
    
    print_success "生产模式启动成功!"
    print_info "访问地址: http://localhost:8080"
    print_info "查看日志: docker-compose logs -f"
}

# 集群模式启动
start_cluster() {
    print_info "启动集群模式 (多实例 + 负载均衡)..."
    docker-compose --profile loadbalancer up --scale id-generator=3 -d
    
    print_success "集群模式启动成功!"
    print_info "访问地址: http://localhost (通过Nginx负载均衡)"
    print_info "直接访问: http://localhost:8080"
    print_info "查看服务状态: docker-compose ps"
}

# 停止服务
stop_services() {
    print_info "停止所有服务..."
    
    # 停止docker-compose服务
    if [ -f "docker-compose.yml" ]; then
        docker-compose down
    fi
    
    # 停止单独的开发容器
    if docker ps -q --filter "name=id-generator-dev" | grep -q .; then
        docker stop id-generator-dev
        docker rm id-generator-dev
    fi
    
    print_success "所有服务已停止"
}

# 清理资源
clean_resources() {
    print_warning "这将删除所有相关的容器、镜像和数据卷，确定要继续吗? (y/N)"
    read -r response
    if [[ "$response" =~ ^([yY][eE][sS]|[yY])$ ]]; then
        print_info "清理资源中..."
        
        # 停止服务
        stop_services
        
        # 删除镜像
        if docker images -q id-generator | grep -q .; then
            docker rmi id-generator:latest
        fi
        
        # 删除数据卷
        if docker volume ls -q --filter name=distributed-id-generator | grep -q .; then
            docker volume rm $(docker volume ls -q --filter name=distributed-id-generator)
        fi
        
        print_success "资源清理完成"
    else
        print_info "取消清理操作"
    fi
}

# 显示帮助信息
show_help() {
    echo "分布式ID生成器 Docker 部署脚本"
    echo ""
    echo "使用方法:"
    echo "  $0 [选项]"
    echo ""
    echo "选项:"
    echo "  dev     - 开发模式 (H2数据库)"
    echo "  prod    - 生产模式 (MySQL数据库)"
    echo "  cluster - 集群模式 (多实例 + 负载均衡)"
    echo "  stop    - 停止所有服务"
    echo "  clean   - 清理所有容器和镜像"
    echo "  help    - 显示此帮助信息"
    echo ""
    echo "示例:"
    echo "  $0 dev      # 启动开发环境"
    echo "  $0 prod     # 启动生产环境"
    echo "  $0 cluster  # 启动集群环境"
    echo "  $0 stop     # 停止所有服务"
}

# 主函数
main() {
    check_docker
    
    case "${1:-help}" in
        "dev")
            start_dev
            ;;
        "prod")
            start_prod
            ;;
        "cluster")
            start_cluster
            ;;
        "stop")
            stop_services
            ;;
        "clean")
            clean_resources
            ;;
        "help"|*)
            show_help
            ;;
    esac
}

# 执行主函数
main "$@"