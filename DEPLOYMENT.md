# 分布式ID生成器部署指南

## 🚀 快速部署

### 1. 本地开发环境 (H2数据库)

最简单的启动方式，适合开发和测试：

```bash
# 克隆项目
git clone <repository-url>
cd distributed-id-generator

# 直接启动 (使用H2内存数据库)
mvn spring-boot:run
```

访问地址：
- Web界面: http://localhost:8080
- API文档: http://localhost:8080/api/docs
- H2控制台: http://localhost:8080/h2-console

### 2. 生产环境 (MySQL数据库)

#### 方式一：使用启动脚本 (推荐)

```bash
# Linux/Mac
chmod +x start.sh
SPRING_PROFILES_ACTIVE=mysql ./start.sh

# Windows
set SPRING_PROFILES_ACTIVE=mysql
start.bat
```

#### 方式二：手动配置

```bash
# 设置环境变量
export SPRING_PROFILES_ACTIVE=mysql
export MYSQL_HOST=11.142.154.110
export MYSQL_PORT=3306
export MYSQL_DATABASE=czyll8wg
export MYSQL_USERNAME=with_ygpsfnsdmsasjvcz
export MYSQL_PASSWORD="9j4srZ)\$wavpqm"

# 构建并启动
mvn clean package -DskipTests
java -jar target/*.jar
```

### 3. Docker部署

#### 方式一：Docker Compose (推荐)

使用 Docker Compose 可以一键启动完整的服务栈，包括应用和数据库：

```bash
# 启动完整服务栈 (应用 + MySQL)
docker-compose up -d

# 查看服务状态
docker-compose ps

# 查看日志
docker-compose logs -f id-generator

# 停止服务
docker-compose down

# 启动时包含负载均衡器
docker-compose --profile loadbalancer up -d
```

#### 方式二：单独构建和运行

```bash
# 构建镜像
docker build -t id-generator:latest .

# 启动容器 (H2数据库)
docker run -p 8080:8080 id-generator:latest

# 启动容器 (MySQL数据库)
docker run -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=mysql \
  -e MYSQL_HOST=11.142.154.110 \
  -e MYSQL_PORT=3306 \
  -e MYSQL_DATABASE=czyll8wg \
  -e MYSQL_USERNAME=with_ygpsfnsdmsasjvcz \
  -e MYSQL_PASSWORD="9j4srZ)\$wavpqm" \
  id-generator:latest
```

#### 方式三：多实例部署

```bash
# 启动多个实例实现高可用
docker-compose up --scale id-generator=3 -d

# 使用不同端口启动多个实例
docker run -d -p 8080:8080 --name id-gen-1 id-generator:latest
docker run -d -p 8081:8080 --name id-gen-2 id-generator:latest
docker run -d -p 8082:8080 --name id-gen-3 id-generator:latest
```

### 4. Kubernetes部署

```bash
# 部署到K8s集群
kubectl apply -f k8s-deployment.yaml

# 查看部署状态
kubectl get pods -l app=id-generator

# 查看服务
kubectl get svc id-generator-service

# 端口转发 (本地测试)
kubectl port-forward svc/id-generator-service 8080:8080
```

## 🔧 配置说明

### 环境变量

| 变量名 | 说明 | 默认值 | 示例 |
|--------|------|--------|------|
| SPRING_PROFILES_ACTIVE | 运行环境 | h2 | mysql |
| ID_SERVER_TYPE | 服务器类型 | 0 | 0(偶数) / 1(奇数) |
| ID_STEP_SIZE | 号段步长 | 1000 | 1000 |
| ID_SEGMENT_THRESHOLD | 刷新阈值 | 0.1 | 0.1 |
| MYSQL_HOST | MySQL主机 | - | 11.142.154.110 |
| MYSQL_PORT | MySQL端口 | - | 3306 |
| MYSQL_DATABASE | 数据库名 | - | czyll8wg |
| MYSQL_USERNAME | 用户名 | - | with_ygpsfnsdmsasjvcz |
| MYSQL_PASSWORD | 密码 | - | 9j4srZ)$wavpqm |

### 服务器类型说明

- **偶数服务器** (ID_SERVER_TYPE=0): 负责生成偶数号段的ID
- **奇数服务器** (ID_SERVER_TYPE=1): 负责生成奇数号段的ID

### 高可用部署

为了实现高可用，建议部署架构：

```
┌─────────────────┐
│   Load Balancer │
└─────────┬───────┘
          │
    ┌─────┴─────┐
    │           │
┌───▼───┐   ┌───▼───┐
│Even-1 │   │Odd-1  │
│Even-2 │   │Odd-2  │
└───────┘   └───────┘
```

部署步骤：
1. 部署2个偶数服务器实例 (ID_SERVER_TYPE=0)
2. 部署2个奇数服务器实例 (ID_SERVER_TYPE=1)
3. 配置负载均衡器
4. 配置健康检查

## 🔍 验证部署

### 健康检查

```bash
curl http://localhost:8080/api/id/health
```

预期响应：
```json
{
  "status": "UP",
  "serverType": 0,
  "timestamp": "2024-01-01T12:00:00Z"
}
```

### 获取ID

```bash
curl -X POST http://localhost:8080/api/id/generate \
  -H "Content-Type: application/json" \
  -d '{
    "businessType": "order",
    "timeKey": "20240101",
    "count": 10
  }'
```

预期响应：
```json
{
  "success": true,
  "data": {
    "ids": [1001, 1002, 1003, 1004, 1005, 1006, 1007, 1008, 1009, 1010],
    "businessType": "order",
    "timeKey": "20240101",
    "serverType": 0,
    "count": 10
  }
}
```

## 🚨 故障排查

### 常见问题

1. **数据库连接失败**
   - 检查MySQL服务是否启动
   - 验证连接参数是否正确
   - 检查网络连通性

2. **端口占用**
   ```bash
   # 检查端口占用
   netstat -tlnp | grep 8080
   
   # 修改端口
   export SERVER_PORT=8081
   ```

3. **内存不足**
   ```bash
   # 调整JVM参数
   export JAVA_OPTS="-Xms512m -Xmx1024m"
   java $JAVA_OPTS -jar target/*.jar
   ```

### 日志查看

```bash
# 查看应用日志
tail -f logs/application.log

# Docker容器日志
docker logs -f <container-id>

# K8s Pod日志
kubectl logs -f <pod-name>
```

## 📊 监控指标

访问监控端点：
- 健康检查: `/actuator/health`
- 指标信息: `/actuator/metrics`
- 应用信息: `/actuator/info`

## 🔄 扩容操作

### 水平扩容

```bash
# K8s扩容
kubectl scale deployment id-generator-even --replicas=4
kubectl scale deployment id-generator-odd --replicas=4

# Docker Compose扩容
docker-compose up --scale id-generator=4
```

### 垂直扩容

修改资源配置：
```yaml
resources:
  requests:
    memory: "1Gi"
    cpu: "500m"
  limits:
    memory: "2Gi"
    cpu: "1000m"
```

## 📞 技术支持

如遇到部署问题，请检查：
1. Java版本 (需要Java 8+)
2. Maven版本 (需要Maven 3.6+)
3. 数据库连接
4. 网络配置
5. 防火墙设置