# 使用官方的OpenJDK 17镜像作为基础镜像
FROM openjdk:17-jdk-slim

# 设置工作目录
WORKDIR /app

# 复制Maven配置文件
COPY pom.xml .

# 复制源代码
COPY src ./src

# 安装Maven
RUN apt-get update && apt-get install -y maven

# 构建应用
RUN mvn clean package -DskipTests

# 创建运行时镜像
FROM openjdk:17-jre-slim

# 设置工作目录
WORKDIR /app

# 从构建阶段复制jar文件
COPY --from=0 /app/target/*.jar app.jar

# 复制静态文件
COPY static ./static

# 设置环境变量
ENV SPRING_PROFILES_ACTIVE=mysql
ENV MYSQL_HOST=localhost
ENV MYSQL_PORT=3306
ENV MYSQL_DATABASE=czyll8wg
ENV MYSQL_USERNAME=root
ENV MYSQL_PASSWORD=password

# 暴露端口
EXPOSE 8080

# 健康检查
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

# 启动应用
ENTRYPOINT ["java", "-jar", "app.jar"]