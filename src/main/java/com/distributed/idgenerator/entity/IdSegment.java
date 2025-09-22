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

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_type", nullable = false, length = 64)
    private String businessType;

    @Column(name = "time_key", nullable = false, length = 32)
    private String timeKey;

    @Column(name = "shard_type", nullable = false)
    private Integer shardType; // 0-偶数, 1-奇数

    @Column(name = "max_value", nullable = false)
    private Long maxValue;

    @Column(name = "step_size", nullable = false)
    private Integer stepSize;

    @Column(name = "created_time")
    private LocalDateTime createdTime;

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