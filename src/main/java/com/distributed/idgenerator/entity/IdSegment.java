package com.distributed.idgenerator.entity;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * ID号段实体类
 * 
 * @author System
 * @version 1.0.0
 */
@Entity
@Table(name = "id_segment", 
       uniqueConstraints = @UniqueConstraint(
           name = "uk_business_time_shard", 
           columnNames = {"business_type", "time_key", "shard_type"}
       ))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IdSegment {

    /**
     * 主键ID，数据库自增
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 业务类型标识，用于区分不同业务场景的ID生成
     * 例如：order（订单）、user（用户）、product（商品）等
     * 最大长度64字符，不能为空
     */
    @Column(name = "business_type", nullable = false, length = 64)
    private String businessType;

    /**
     * 时间维度分片键，用于按时间段隔离ID号段
     * 通常格式为日期字符串，如：20231101、202311等
     * 支持按日、按月或自定义时间段进行分片
     * 最大长度32字符，不能为空
     */
    @Column(name = "time_key", nullable = false, length = 32)
    private String timeKey;

    /**
     * 分片类型，用于奇偶分片策略
     * 0 - 偶数分片：处理偶数号段，生成偶数ID
     * 1 - 奇数分片：处理奇数号段，生成奇数ID
     * 通过奇偶分片实现负载均衡和高可用性
     */
    @Column(name = "shard_type", nullable = false)
    private Integer shardType;

    /**
     * 当前号段的最大值
     * 表示该号段可分配的最大ID值
     * 当达到此值时需要申请新的号段
     */
    @Column(name = "max_value", nullable = false)
    private Long maxValue;

    /**
     * 号段步长，每次申请号段时的增量大小
     * 决定了每个号段包含的ID数量
     * 较大的步长可以减少数据库访问频率，但可能造成ID浪费
     * 较小的步长可以节约ID空间，但会增加数据库访问频率
     */
    @Column(name = "step_size", nullable = false)
    private Integer stepSize;

    /**
     * 记录创建时间
     * 在实体首次持久化时自动设置
     */
    @Column(name = "created_time")
    private LocalDateTime createdTime;

    /**
     * 记录最后更新时间
     * 在实体每次更新时自动刷新
     */
    @Column(name = "updated_time")
    private LocalDateTime updatedTime;

    @PrePersist
    protected void onCreate() {
        createdTime = LocalDateTime.now();
        updatedTime = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedTime = LocalDateTime.now();
    }

    /**
     * 获取分片类型描述
     */
    public String getShardTypeDesc() {
        return shardType == 0 ? "偶数分片" : "奇数分片";
    }

    /**
     * 判断是否为奇数分片
     */
    public boolean isOddShard() {
        return shardType == 1;
    }

    /**
     * 判断是否为偶数分片
     */
    public boolean isEvenShard() {
        return shardType == 0;
    }
}