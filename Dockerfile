# 使用OpenJDK 8作为基础镜像
FROM openjdk:8-jdk-alpine

# 设置工作目录
WORKDIR /app

# 安装必要的工具
RUN apk add --no-cache curl

# 复制Maven构建文件
COPY pom.xml .
COPY src ./src

# 安装Maven
RUN apk add --no-cache maven

# 构建应用
RUN mvn clean package -DskipTests

# 复制构建好的jar文件
RUN cp target/*.jar app.jar

# 复制静态文件
COPY static ./static

# 创建非root用户
RUN addgroup -g 1001 -S appgroup && \
    adduser -u 1001 -S appuser -G appgroup

# 更改文件所有者
RUN chown -R appuser:appgroup /app

# 切换到非root用户
USER appuser

# 暴露端口
EXPOSE 8080

# 健康检查
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD curl -f http://localhost:8080/api/id/health || exit 1

# 启动应用
CMD ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]