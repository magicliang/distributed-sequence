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

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "server_id", nullable = false, length = 64)
    private String serverId;

    @Column(name = "server_type", nullable = false)
    private Integer serverType; // 0-偶数服务器, 1-奇数服务器

    @Column(name = "status", nullable = false)
    private Integer status; // 0-下线, 1-在线

    @Column(name = "last_heartbeat")
    private LocalDateTime lastHeartbeat;

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