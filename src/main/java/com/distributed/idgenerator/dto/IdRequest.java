package com.distributed.idgenerator.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Min;

/**
 * ID请求DTO
 * 
 * @author System
 * @version 1.0.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IdRequest {

    /**
     * 业务类型
     */
    @NotBlank(message = "业务类型不能为空")
    private String businessType;

    /**
     * 时间键（可选，默认使用当前日期）
     */
    private String timeKey;

    /**
     * 请求数量（默认为1）
     */
    @Min(value = 1, message = "请求数量必须大于0")
    private Integer count = 1;

    /**
     * 是否包含分库分表路由信息
     */
    private Boolean includeRouting = false;

    /**
     * 分库数量（用于路由计算）
     */
    private Integer shardDbCount;

    /**
     * 分表数量（用于路由计算）
     */
    private Integer shardTableCount;

    /**
     * 自定义步长（可选）
     */
    private Integer customStepSize;

    /**
     * 是否强制使用指定分片类型（0-偶数，1-奇数）
     */
    private Integer forceShardType;

    /**
     * 获取有效的时间键
     */
    public String getEffectiveTimeKey() {
        if (timeKey == null || timeKey.trim().isEmpty()) {
            return java.time.LocalDate.now().toString().replace("-", "");
        }
        return timeKey;
    }

    /**
     * 获取有效的请求数量
     */
    public Integer getEffectiveCount() {
        return count == null || count <= 0 ? 1 : count;
    }

    /**
     * 是否需要路由信息
     */
    public boolean needRouting() {
        return includeRouting != null && includeRouting && 
               shardDbCount != null && shardDbCount > 0;
    }
}