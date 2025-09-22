@echo off
chcp 65001 >nul

echo =================================
echo 分布式ID生成器启动脚本 (Windows)
echo =================================

REM 检查Java环境
java -version >nul 2>&1
if %errorlevel% neq 0 (
    echo 错误: 未找到Java环境，请安装Java 8或更高版本
    pause
    exit /b 1
)

REM 检查Maven环境
mvn -version >nul 2>&1
if %errorlevel% neq 0 (
    echo 错误: 未找到Maven环境，请安装Maven
    pause
    exit /b 1
)

REM 设置环境变量
if "%SPRING_PROFILES_ACTIVE%"=="" set SPRING_PROFILES_ACTIVE=h2
if "%ID_SERVER_TYPE%"=="" set ID_SERVER_TYPE=0
if "%ID_STEP_SIZE%"=="" set ID_STEP_SIZE=1000

REM 如果使用MySQL，设置数据库连接信息
if "%SPRING_PROFILES_ACTIVE%"=="mysql" (
    if "%MYSQL_HOST%"=="" set MYSQL_HOST=11.142.154.110
    if "%MYSQL_PORT%"=="" set MYSQL_PORT=3306
    if "%MYSQL_DATABASE%"=="" set MYSQL_DATABASE=czyll8wg
    if "%MYSQL_USERNAME%"=="" set MYSQL_USERNAME=with_ygpsfnsdmsasjvcz
    if "%MYSQL_PASSWORD%"=="" set MYSQL_PASSWORD=9j4srZ)$wavpqm
    echo 使用MySQL数据库: %MYSQL_HOST%:%MYSQL_PORT%/%MYSQL_DATABASE%
) else (
    echo 使用H2内存数据库
)

echo 服务器类型: %ID_SERVER_TYPE% (0-偶数服务器, 1-奇数服务器)
echo 步长大小: %ID_STEP_SIZE%

REM 清理并构建项目
echo 正在构建项目...
call mvn clean package -DskipTests

if %errorlevel% neq 0 (
    echo 构建失败，请检查错误信息
    pause
    exit /b 1
)

echo 构建成功，正在启动应用...

REM 启动应用
java -Djava.security.egd=file:/dev/./urandom ^
     -Dspring.profiles.active=%SPRING_PROFILES_ACTIVE% ^
     -Did.generator.server.type=%ID_SERVER_TYPE% ^
     -Did.generator.step.size=%ID_STEP_SIZE% ^
     -jar target/*.jar

echo 应用已停止
pause