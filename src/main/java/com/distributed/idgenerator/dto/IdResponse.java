package com.distributed.idgenerator.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.util.List;

/**
 * ID响应DTO
 * 
 * @author System
 * @version 1.0.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IdResponse {

    /**
     * 生成的ID列表
     */
    private List<Long> ids;

    /**
     * 业务类型
     */
    private String businessType;

    /**
     * 时间键
     */
    private String timeKey;

    /**
     * 分片类型（0-偶数，1-奇数）
     */
    private Integer shardType;

    /**
     * 服务器ID
     */
    private String serverId;

    /**
     * 路由信息
     */
    private RoutingInfo routingInfo;

    /**
     * 生成时间戳
     */
    private Long timestamp;

    /**
     * 路由信息内部类
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class RoutingInfo {
        /**
         * 数据库索引
         */
        private Integer dbIndex;

        /**
         * 表索引
         */
        private Integer tableIndex;

        /**
         * 分库数量
         */
        private Integer shardDbCount;

        /**
         * 分表数量
         */
        private Integer shardTableCount;

        /**
         * 路由键（用于计算的ID值）
         */
        private Long routingKey;
    }

    /**
     * 获取第一个ID
     */
    public Long getFirstId() {
        return ids != null && !ids.isEmpty() ? ids.get(0) : null;
    }

    /**
     * 获取ID数量
     */
    public int getIdCount() {
        return ids != null ? ids.size() : 0;
    }

    /**
     * 获取分片类型描述
     */
    public String getShardTypeDesc() {
        return shardType != null ? (shardType == 0 ? "偶数分片" : "奇数分片") : "未知";
    }

    /**
     * 是否包含路由信息
     */
    public boolean hasRoutingInfo() {
        return routingInfo != null;
    }
}