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
     * 包含本次请求生成的所有ID值
     * ID按生成顺序排列，保证单调递增（考虑奇偶分片规则）
     * 列表长度等于请求中的count参数值
     */
    private List<Long> ids;

    /**
     * 业务类型标识
     * 与请求中的businessType保持一致
     * 用于标识这批ID属于哪个业务场景
     */
    private String businessType;

    /**
     * 时间维度分片键
     * 与请求中的timeKey保持一致，如果请求未提供则为系统自动生成的值
     * 表示这批ID属于哪个时间段的分片
     */
    private String timeKey;

    /**
     * 实际使用的分片类型
     * 0 - 偶数分片：生成的ID为偶数
     * 1 - 奇数分片：生成的ID为奇数
     * 可能与请求中的forceShardType不同，取决于系统的负载均衡策略
     */
    private Integer shardType;

    /**
     * 处理本次请求的服务器ID
     * 用于追踪和调试，标识是哪台服务器生成了这批ID
     * 格式：主机名-IP地址-服务器类型
     */
    private String serverId;

    /**
     * 分库分表路由信息
     * 仅在请求中includeRouting=true时返回
     * 包含数据库索引、表索引等路由计算结果
     * 可用于应用层的分库分表路由决策
     */
    private RoutingInfo routingInfo;

    /**
     * ID生成时间戳（毫秒）
     * 记录ID生成的精确时间
     * 可用于性能监控、审计日志等场景
     */
    private Long timestamp;

    /**
     * 分库分表路由信息内部类
     * 提供基于生成ID的数据库和表路由计算结果
     * 帮助应用层进行分库分表的路由决策
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class RoutingInfo {
        /**
         * 计算得出的数据库索引
         * 取值范围：0 到 (shardDbCount - 1)
         * 计算公式：dbIndex = routingKey % shardDbCount
         * 应用可根据此索引确定数据应存储在哪个数据库
         */
        private Integer dbIndex;

        /**
         * 计算得出的数据表索引
         * 取值范围：0 到 (shardTableCount - 1)
         * 计算公式：tableIndex = routingKey % shardTableCount
         * 应用可根据此索引确定数据应存储在哪张表
         * 仅在请求提供shardTableCount时计算
         */
        private Integer tableIndex;

        /**
         * 分库总数量
         * 与请求中的shardDbCount保持一致
         * 用于验证路由计算的参数
         */
        private Integer shardDbCount;

        /**
         * 分表总数量
         * 与请求中的shardTableCount保持一致
         * 用于验证路由计算的参数，可能为null
         */
        private Integer shardTableCount;

        /**
         * 用于路由计算的键值
         * 通常是生成的第一个ID值
         * 作为分库分表路由算法的输入参数
         * 保证相同routingKey的数据路由到相同的库表
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