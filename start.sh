#!/bin/bash

# 分布式ID生成器启动脚本
echo "================================="
echo "分布式ID生成器启动脚本"
echo "================================="

# 检查Java环境
if ! command -v java &> /dev/null; then
    echo "错误: 未找到Java环境，请安装Java 8或更高版本"
    exit 1
fi

# 检查Maven环境
if ! command -v mvn &> /dev/null; then
    echo "错误: 未找到Maven环境，请安装Maven"
    exit 1
fi

# 设置环境变量
export SPRING_PROFILES_ACTIVE=${SPRING_PROFILES_ACTIVE:-h2}
export ID_SERVER_TYPE=${ID_SERVER_TYPE:-0}
export ID_STEP_SIZE=${ID_STEP_SIZE:-1000}

# 如果使用MySQL，设置数据库连接信息
if [ "$SPRING_PROFILES_ACTIVE" = "mysql" ]; then
    export MYSQL_HOST=${MYSQL_HOST:-11.142.154.110}
    export MYSQL_PORT=${MYSQL_PORT:-3306}
    export MYSQL_DATABASE=${MYSQL_DATABASE:-czyll8wg}
    export MYSQL_USERNAME=${MYSQL_USERNAME:-with_ygpsfnsdmsasjvcz}
    export MYSQL_PASSWORD=${MYSQL_PASSWORD:-"9j4srZ)\$wavpqm"}
    echo "使用MySQL数据库: $MYSQL_HOST:$MYSQL_PORT/$MYSQL_DATABASE"
else
    echo "使用H2内存数据库"
fi

echo "服务器类型: $ID_SERVER_TYPE (0-偶数服务器, 1-奇数服务器)"
echo "步长大小: $ID_STEP_SIZE"

# 清理并构建项目
echo "正在构建项目..."
mvn clean package -DskipTests

if [ $? -ne 0 ]; then
    echo "构建失败，请检查错误信息"
    exit 1
fi

echo "构建成功，正在启动应用..."

# 启动应用
java -Djava.security.egd=file:/dev/./urandom \
     -Dspring.profiles.active=$SPRING_PROFILES_ACTIVE \
     -Did.generator.server.type=$ID_SERVER_TYPE \
     -Did.generator.step.size=$ID_STEP_SIZE \
     -jar target/*.jar

echo "应用已停止"