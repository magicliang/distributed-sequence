-- 分布式ID生成器数据库初始化脚本

-- 创建数据库（如果不存在）
CREATE DATABASE IF NOT EXISTS czyll8wg CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE czyll8wg;

-- 创建ID段表
CREATE TABLE IF NOT EXISTS id_segment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    business_type VARCHAR(64) NOT NULL COMMENT '业务类型',
    time_key VARCHAR(32) DEFAULT '' COMMENT '时间分片键',
    max_value BIGINT NOT NULL DEFAULT 0 COMMENT '当前最大值',
    step_size INT NOT NULL DEFAULT 1000 COMMENT '步长',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '版本号，用于乐观锁',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_business_time (business_type, time_key),
    INDEX idx_business_type (business_type),
    INDEX idx_updated_at (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ID段表';

-- 创建服务器注册表
CREATE TABLE IF NOT EXISTS server_registry (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    server_id VARCHAR(64) NOT NULL COMMENT '服务器ID',
    server_type ENUM('ODD', 'EVEN') NOT NULL COMMENT '服务器类型：奇数或偶数',
    host_name VARCHAR(255) NOT NULL COMMENT '主机名',
    ip_address VARCHAR(45) NOT NULL COMMENT 'IP地址',
    port INT NOT NULL COMMENT '端口号',
    status ENUM('ACTIVE', 'INACTIVE') NOT NULL DEFAULT 'ACTIVE' COMMENT '状态',
    last_heartbeat TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '最后心跳时间',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_server_id (server_id),
    INDEX idx_server_type (server_type),
    INDEX idx_status (status),
    INDEX idx_last_heartbeat (last_heartbeat)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='服务器注册表';

-- 插入初始测试数据
INSERT IGNORE INTO id_segment (business_type, time_key, max_value, step_size, version) VALUES
('order', '', 0, 1000, 0),
('user', '', 0, 1000, 0),
('product', '', 0, 1000, 0),
('payment', '', 0, 1000, 0);

-- 插入服务器注册信息（示例）
INSERT IGNORE INTO server_registry (server_id, server_type, host_name, ip_address, port, status) VALUES
('server-1', 'ODD', 'id-generator-1', '127.0.0.1', 8080, 'ACTIVE'),
('server-2', 'EVEN', 'id-generator-2', '127.0.0.1', 8081, 'ACTIVE');

-- 创建索引优化查询性能
CREATE INDEX IF NOT EXISTS idx_id_segment_business_time ON id_segment(business_type, time_key);
CREATE INDEX IF NOT EXISTS idx_server_registry_type_status ON server_registry(server_type, status);

COMMIT;