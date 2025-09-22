# Docker 部署指南

## 📋 概述

本项目提供了完整的 Docker 容器化解决方案，支持多种部署模式：
- **开发模式**：使用 H2 内存数据库，快速启动
- **生产模式**：使用 MySQL 数据库，数据持久化
- **集群模式**：多实例 + Nginx 负载均衡，高可用部署

## 🚀 快速开始

### 前置要求
- Docker 20.10+
- Docker Compose 2.0+

### 一键启动
```bash
# Linux/Mac
chmod +x docker-start.sh
./docker-start.sh prod

# Windows
docker-start.bat prod
```

## 📦 部署模式详解

### 1. 开发模式 (H2数据库)

适用于：本地开发、功能测试、演示

```bash
# 启动
./docker-start.sh dev

# 或手动启动
docker build -t id-generator:latest .
docker run -d \
  --name id-generator-dev \
  -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=h2 \
  id-generator:latest
```

**特点：**
- 🚀 启动快速，无需外部数据库
- 💾 数据存储在内存中，重启后丢失
- 🔧 适合开发调试

**访问地址：**
- 应用主页：http://localhost:8080
- H2 控制台：http://localhost:8080/h2-console
- API 文档：http://localhost:8080/api/docs

### 2. 生产模式 (MySQL数据库)

适用于：生产环境、数据持久化需求

```bash
# 启动
./docker-start.sh prod

# 或使用 docker-compose
docker-compose up -d
```

**特点：**
- 🗄️ MySQL 数据库，数据持久化
- 🔒 生产级配置和安全设置
- 📊 完整的监控和日志

**服务组件：**
- `id-generator`：主应用服务
- `mysql`：MySQL 8.0 数据库
- `mysql_data`：数据持久化卷

### 3. 集群模式 (高可用)

适用于：高并发、高可用场景

```bash
# 启动集群
./docker-start.sh cluster

# 或手动启动
docker-compose --profile loadbalancer up --scale id-generator=3 -d
```

**特点：**
- ⚖️ Nginx 负载均衡
- 🔄 多实例自动扩缩容
- 🛡️ 故障自动转移

**服务组件：**
- `nginx`：负载均衡器 (端口 80)
- `id-generator` x3：应用实例
- `mysql`：共享数据库

## 🔧 配置说明

### 环境变量

| 变量名 | 默认值 | 说明 |
|--------|--------|------|
| `SPRING_PROFILES_ACTIVE` | `mysql` | 激活的配置文件 |
| `MYSQL_HOST` | `mysql` | MySQL 主机地址 |
| `MYSQL_PORT` | `3306` | MySQL 端口 |
| `MYSQL_DATABASE` | `czyll8wg` | 数据库名称 |
| `MYSQL_USERNAME` | `idgen_user` | 数据库用户名 |
| `MYSQL_PASSWORD` | `idgen_password` | 数据库密码 |

### 端口映射

| 服务 | 容器端口 | 主机端口 | 说明 |
|------|----------|----------|------|
| id-generator | 8080 | 8080 | 主应用服务 |
| mysql | 3306 | 3306 | MySQL 数据库 |
| nginx | 80 | 80 | 负载均衡器 |

### 数据卷

| 卷名 | 挂载点 | 说明 |
|------|--------|------|
| `mysql_data` | `/var/lib/mysql` | MySQL 数据持久化 |

## 🛠️ 运维操作

### 查看服务状态
```bash
# 查看所有服务
docker-compose ps

# 查看特定服务
docker-compose ps id-generator
```

### 查看日志
```bash
# 查看所有日志
docker-compose logs

# 实时查看应用日志
docker-compose logs -f id-generator

# 查看最近100行日志
docker-compose logs --tail=100 id-generator
```

### 扩缩容操作
```bash
# 扩容到5个实例
docker-compose up --scale id-generator=5 -d

# 缩容到2个实例
docker-compose up --scale id-generator=2 -d
```

### 重启服务
```bash
# 重启所有服务
docker-compose restart

# 重启特定服务
docker-compose restart id-generator
```

### 更新应用
```bash
# 重新构建并启动
docker-compose up --build -d

# 或者
docker build -t id-generator:latest .
docker-compose up -d
```

## 🔍 监控和健康检查

### 健康检查端点
- 应用健康：`GET /actuator/health`
- 数据库连接：`GET /actuator/health/db`
- 磁盘空间：`GET /actuator/health/diskSpace`

### 监控指标
```bash
# 查看容器资源使用
docker stats

# 查看特定容器
docker stats id-generator-app
```

## 🚨 故障排除

### 常见问题

#### 1. 端口冲突
```bash
# 查看端口占用
netstat -tulpn | grep :8080

# 修改端口映射
docker-compose up -d --scale id-generator=1 -p 8081:8080
```

#### 2. 数据库连接失败
```bash
# 检查数据库状态
docker-compose logs mysql

# 重启数据库
docker-compose restart mysql
```

#### 3. 内存不足
```bash
# 查看内存使用
docker system df

# 清理未使用的资源
docker system prune -f
```

### 日志分析
```bash
# 查看错误日志
docker-compose logs id-generator | grep ERROR

# 查看启动日志
docker-compose logs id-generator | grep "Started IdGeneratorApplication"
```

## 🔐 安全配置

### 生产环境建议
1. **修改默认密码**
   ```yaml
   environment:
     MYSQL_PASSWORD: your_secure_password
   ```

2. **限制网络访问**
   ```yaml
   networks:
     - internal
   ```

3. **使用 secrets 管理敏感信息**
   ```yaml
   secrets:
     mysql_password:
       file: ./mysql_password.txt
   ```

## 📈 性能优化

### JVM 调优
```dockerfile
ENV JAVA_OPTS="-Xms512m -Xmx1024m -XX:+UseG1GC"
```

### MySQL 调优
```yaml
command: --innodb-buffer-pool-size=256M --max-connections=200
```

### Nginx 调优
```nginx
worker_processes auto;
worker_connections 1024;
```

## 🔄 备份和恢复

### 数据备份
```bash
# 备份数据库
docker exec mysql mysqldump -u root -p czyll8wg > backup.sql

# 备份数据卷
docker run --rm -v mysql_data:/data -v $(pwd):/backup alpine tar czf /backup/mysql_backup.tar.gz /data
```

### 数据恢复
```bash
# 恢复数据库
docker exec -i mysql mysql -u root -p czyll8wg < backup.sql

# 恢复数据卷
docker run --rm -v mysql_data:/data -v $(pwd):/backup alpine tar xzf /backup/mysql_backup.tar.gz -C /
```

## 📚 参考资料

- [Docker 官方文档](https://docs.docker.com/)
- [Docker Compose 文档](https://docs.docker.com/compose/)
- [Spring Boot Docker 指南](https://spring.io/guides/gs/spring-boot-docker/)
- [MySQL Docker 镜像](https://hub.docker.com/_/mysql)
- [Nginx Docker 镜像](https://hub.docker.com/_/nginx)