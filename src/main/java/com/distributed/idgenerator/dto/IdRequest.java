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
     * 业务类型标识，用于区分不同业务场景的ID生成
     * 例如：order（订单）、user（用户）、product（商品）等
     * 必填字段，不能为空或空字符串
     * 与数据库中id_segment表的business_type字段对应
     */
    @NotBlank(message = "业务类型不能为空")
    private String businessType;

    /**
     * 时间维度分片键，用于按时间段隔离ID号段
     * 可选字段，如果不提供则自动使用当前日期（格式：yyyyMMdd）
     * 支持自定义时间格式，如：20231101、202311、2023Q1等
     * 用于实现时间维度的数据分片和历史数据清理
     */
    private String timeKey;

    /**
     * 单次请求生成的ID数量
     * 默认值为1，最小值为1
     * 支持批量ID生成，提高生成效率
     * 注意：批量生成的ID是连续的（考虑奇偶分片规则）
     */
    @Min(value = 1, message = "请求数量必须大于0")
    private Integer count = 1;

    /**
     * 是否在响应中包含分库分表路由信息
     * 默认为false，不包含路由信息
     * 设置为true时，会根据生成的ID计算对应的数据库和表索引
     * 需要配合shardDbCount和shardTableCount参数使用
     */
    private Boolean includeRouting = false;

    /**
     * 分库数量，用于计算数据库路由索引
     * 仅在includeRouting=true时有效
     * 路由算法：dbIndex = id % shardDbCount
     * 必须大于0才能进行路由计算
     */
    private Integer shardDbCount;

    /**
     * 分表数量，用于计算数据表路由索引
     * 仅在includeRouting=true时有效
     * 路由算法：tableIndex = id % shardTableCount
     * 可选参数，不设置时不计算表路由
     */
    private Integer shardTableCount;

    /**
     * 自定义号段步长，覆盖系统默认步长配置
     * 可选参数，不设置时使用系统配置的默认步长
     * 较大的步长可以减少数据库访问，但可能造成ID浪费
     * 较小的步长节约ID空间，但会增加数据库访问频率
     * 建议根据业务QPS和ID使用模式进行调优
     */
    private Integer customStepSize;

    /**
     * 强制指定分片类型，用于特殊场景下的分片控制
     * 可选参数，不设置时由系统根据负载均衡策略自动选择
     * 0 - 强制使用偶数分片
     * 1 - 强制使用奇数分片
     * 注意：强制指定可能影响负载均衡效果，请谨慎使用
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