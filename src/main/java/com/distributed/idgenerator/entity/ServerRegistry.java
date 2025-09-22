package com.distributed.idgenerator.entity;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * 服务器注册实体类
 * 
 * @author System
 * @version 1.0.0
 */
@Entity
@Table(name = "server_registry",
       uniqueConstraints = @UniqueConstraint(
           name = "uk_server_id", 
           columnNames = {"server_id"}
       ))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ServerRegistry {

    /**
     * 主键ID，数据库自增
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 服务器唯一标识符
     * 通常由主机名、IP地址和服务器类型组成
     * 格式：主机名-IP地址-服务器类型
     * 例如：server01-192.168.1.100-0
     * 最大长度64字符，不能为空，具有唯一性约束
     */
    @Column(name = "server_id", nullable = false, length = 64)
    private String serverId;

    /**
     * 服务器类型，用于奇偶分片策略
     * 0 - 偶数服务器：负责处理偶数分片的ID生成请求
     * 1 - 奇数服务器：负责处理奇数分片的ID生成请求
     * 通过服务器类型实现负载均衡和容错机制
     */
    @Column(name = "server_type", nullable = false)
    private Integer serverType;

    /**
     * 服务器运行状态
     * 0 - 下线状态：服务器已停止或不可用
     * 1 - 在线状态：服务器正常运行，可以处理请求
     * 用于服务发现和健康检查
     */
    @Column(name = "status", nullable = false)
    private Integer status;

    /**
     * 最后心跳时间
     * 记录服务器最后一次发送心跳的时间
     * 用于判断服务器是否存活，超过一定时间未更新则认为服务器下线
     * 在服务器启动和每次心跳时自动更新
     */
    @Column(name = "last_heartbeat")
    private LocalDateTime lastHeartbeat;

    /**
     * 服务器注册时间
     * 记录服务器首次注册到系统的时间
     * 在实体首次持久化时自动设置
     */
    @Column(name = "created_time")
    private LocalDateTime createdTime;

    @PrePersist
    protected void onCreate() {
        createdTime = LocalDateTime.now();
        lastHeartbeat = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        lastHeartbeat = LocalDateTime.now();
    }

    /**
     * 获取服务器类型描述
     */
    public String getServerTypeDesc() {
        return serverType == 0 ? "偶数服务器" : "奇数服务器";
    }

    /**
     * 获取状态描述
     */
    public String getStatusDesc() {
        return status == 1 ? "在线" : "下线";
    }

    /**
     * 判断是否在线
     */
    public boolean isOnline() {
        return status == 1;
    }

    /**
     * 判断是否为奇数服务器
     */
    public boolean isOddServer() {
        return serverType == 1;
    }

    /**
     * 判断是否为偶数服务器
     */
    public boolean isEvenServer() {
        return serverType == 0;
    }

    /**
     * 更新心跳时间
     */
    public void updateHeartbeat() {
        this.lastHeartbeat = LocalDateTime.now();
    }
}