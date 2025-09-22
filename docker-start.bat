@echo off
setlocal enabledelayedexpansion

REM 分布式ID生成器 Docker 快速启动脚本 (Windows版)
REM 使用方法: docker-start.bat [选项]

set "RED=[91m"
set "GREEN=[92m"
set "YELLOW=[93m"
set "BLUE=[94m"
set "NC=[0m"

REM 打印带颜色的消息
:print_info
echo %BLUE%[INFO]%NC% %~1
goto :eof

:print_success
echo %GREEN%[SUCCESS]%NC% %~1
goto :eof

:print_warning
echo %YELLOW%[WARNING]%NC% %~1
goto :eof

:print_error
echo %RED%[ERROR]%NC% %~1
goto :eof

REM 检查Docker是否安装
:check_docker
docker --version >nul 2>&1
if errorlevel 1 (
    call :print_error "Docker 未安装，请先安装 Docker Desktop"
    exit /b 1
)

docker-compose --version >nul 2>&1
if errorlevel 1 (
    call :print_error "Docker Compose 未安装，请先安装 Docker Compose"
    exit /b 1
)
goto :eof

REM 开发模式启动
:start_dev
call :print_info "启动开发模式 (H2数据库)..."
docker build -t id-generator:latest .
docker run -d --name id-generator-dev -p 8080:8080 -e SPRING_PROFILES_ACTIVE=h2 id-generator:latest

call :print_success "开发模式启动成功!"
call :print_info "访问地址: http://localhost:8080"
call :print_info "H2控制台: http://localhost:8080/h2-console"
goto :eof

REM 生产模式启动
:start_prod
call :print_info "启动生产模式 (MySQL数据库)..."
docker-compose up -d

call :print_success "生产模式启动成功!"
call :print_info "访问地址: http://localhost:8080"
call :print_info "查看日志: docker-compose logs -f"
goto :eof

REM 集群模式启动
:start_cluster
call :print_info "启动集群模式 (多实例 + 负载均衡)..."
docker-compose --profile loadbalancer up --scale id-generator=3 -d

call :print_success "集群模式启动成功!"
call :print_info "访问地址: http://localhost (通过Nginx负载均衡)"
call :print_info "直接访问: http://localhost:8080"
call :print_info "查看服务状态: docker-compose ps"
goto :eof

REM 停止服务
:stop_services
call :print_info "停止所有服务..."

REM 停止docker-compose服务
if exist "docker-compose.yml" (
    docker-compose down
)

REM 停止单独的开发容器
docker ps -q --filter "name=id-generator-dev" >nul 2>&1
if not errorlevel 1 (
    docker stop id-generator-dev
    docker rm id-generator-dev
)

call :print_success "所有服务已停止"
goto :eof

REM 清理资源
:clean_resources
call :print_warning "这将删除所有相关的容器、镜像和数据卷，确定要继续吗? (y/N)"
set /p response="请输入选择: "
if /i "!response!"=="y" (
    call :print_info "清理资源中..."
    
    REM 停止服务
    call :stop_services
    
    REM 删除镜像
    docker images -q id-generator >nul 2>&1
    if not errorlevel 1 (
        docker rmi id-generator:latest
    )
    
    REM 删除数据卷
    for /f %%i in ('docker volume ls -q --filter name=distributed-id-generator 2^>nul') do (
        docker volume rm %%i
    )
    
    call :print_success "资源清理完成"
) else (
    call :print_info "取消清理操作"
)
goto :eof

REM 显示帮助信息
:show_help
echo 分布式ID生成器 Docker 部署脚本 (Windows版)
echo.
echo 使用方法:
echo   %~nx0 [选项]
echo.
echo 选项:
echo   dev     - 开发模式 (H2数据库)
echo   prod    - 生产模式 (MySQL数据库)
echo   cluster - 集群模式 (多实例 + 负载均衡)
echo   stop    - 停止所有服务
echo   clean   - 清理所有容器和镜像
echo   help    - 显示此帮助信息
echo.
echo 示例:
echo   %~nx0 dev      # 启动开发环境
echo   %~nx0 prod     # 启动生产环境
echo   %~nx0 cluster  # 启动集群环境
echo   %~nx0 stop     # 停止所有服务
goto :eof

REM 主函数
:main
call :check_docker

set "action=%~1"
if "%action%"=="" set "action=help"

if "%action%"=="dev" (
    call :start_dev
) else if "%action%"=="prod" (
    call :start_prod
) else if "%action%"=="cluster" (
    call :start_cluster
) else if "%action%"=="stop" (
    call :stop_services
) else if "%action%"=="clean" (
    call :clean_resources
) else (
    call :show_help
)
goto :eof

REM 执行主函数
call :main %*