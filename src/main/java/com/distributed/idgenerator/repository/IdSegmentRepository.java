package com.distributed.idgenerator.repository;

import com.distributed.idgenerator.entity.IdSegment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import javax.transaction.Transactional;
import java.util.List;
import java.util.Optional;

/**
 * ID号段数据访问层
 * 
 * @author System
 * @version 1.0.0
 */
@Repository
public interface IdSegmentRepository extends JpaRepository<IdSegment, Long> {

    /**
     * 根据业务类型、时间键和分片类型查找号段
     */
    Optional<IdSegment> findByBusinessTypeAndTimeKeyAndShardType(
            String businessType, String timeKey, Integer shardType);

    /**
     * 根据业务类型和时间键查找所有号段
     */
    List<IdSegment> findByBusinessTypeAndTimeKey(String businessType, String timeKey);

    /**
     * 根据业务类型查找所有号段
     */
    List<IdSegment> findByBusinessType(String businessType);

    /**
     * 根据分片类型查找所有号段
     */
    List<IdSegment> findByShardType(Integer shardType);

    /**
     * 原子性更新最大值
     */
    @Modifying
    @Transactional
    @Query("UPDATE IdSegment s SET s.maxValue = s.maxValue + :stepSize, s.updatedTime = CURRENT_TIMESTAMP " +
           "WHERE s.businessType = :businessType AND s.timeKey = :timeKey AND s.shardType = :shardType")
    int updateMaxValueAtomically(@Param("businessType") String businessType,
                                 @Param("timeKey") String timeKey,
                                 @Param("shardType") Integer shardType,
                                 @Param("stepSize") Integer stepSize);

    /**
     * 原子性更新最大值和步长（支持步长变更）
     */
    @Modifying
    @Transactional
    @Query("UPDATE IdSegment s SET s.maxValue = s.maxValue + :stepSize, s.stepSize = :stepSize, s.updatedTime = CURRENT_TIMESTAMP " +
           "WHERE s.businessType = :businessType AND s.timeKey = :timeKey AND s.shardType = :shardType")
    int updateMaxValueAndStepSizeAtomically(@Param("businessType") String businessType,
                                           @Param("timeKey") String timeKey,
                                           @Param("shardType") Integer shardType,
                                           @Param("stepSize") Integer stepSize);

    /**
     * 获取指定条件下的号段信息（包含步长）
     */
    @Query("SELECT s FROM IdSegment s " +
           "WHERE s.businessType = :businessType AND s.timeKey = :timeKey AND s.shardType = :shardType")
    Optional<IdSegment> getSegmentInfo(@Param("businessType") String businessType,
                                      @Param("timeKey") String timeKey,
                                      @Param("shardType") Integer shardType);

    /**
     * 获取指定条件下的当前最大值
     */
    @Query("SELECT s.maxValue FROM IdSegment s " +
           "WHERE s.businessType = :businessType AND s.timeKey = :timeKey AND s.shardType = :shardType")
    Optional<Long> getCurrentMaxValue(@Param("businessType") String businessType,
                                      @Param("timeKey") String timeKey,
                                      @Param("shardType") Integer shardType);

    /**
     * 检查号段是否存在
     */
    boolean existsByBusinessTypeAndTimeKeyAndShardType(String businessType, String timeKey, Integer shardType);

    /**
     * 获取所有不同的业务类型
     */
    @Query("SELECT DISTINCT s.businessType FROM IdSegment s ORDER BY s.businessType")
    List<String> findAllDistinctBusinessTypes();

    /**
     * 获取指定业务类型的所有时间键
     */
    @Query("SELECT DISTINCT s.timeKey FROM IdSegment s WHERE s.businessType = :businessType ORDER BY s.timeKey DESC")
    List<String> findTimeKeysByBusinessType(@Param("businessType") String businessType);

    /**
     * 删除过期的号段记录
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM IdSegment s WHERE s.timeKey < :expiredTimeKey")
    int deleteExpiredSegments(@Param("expiredTimeKey") String expiredTimeKey);
    
    /**
     * 原子性更新最大值到指定值（奇偶区间错开模式）
     */
    @Modifying
    @Transactional
    @Query("UPDATE IdSegment s SET s.maxValue = :maxValue, s.updatedTime = CURRENT_TIMESTAMP " +
           "WHERE s.businessType = :businessType AND s.timeKey = :timeKey AND s.shardType = :shardType")
    int updateMaxValueAtomicallyWithValue(@Param("businessType") String businessType,
                                         @Param("timeKey") String timeKey,
                                         @Param("shardType") Integer shardType,
                                         @Param("maxValue") Long maxValue);
    
    /**
     * 原子性更新最大值和步长到指定值（奇偶区间错开模式，支持步长变更）
     */
    @Modifying
    @Transactional
    @Query("UPDATE IdSegment s SET s.maxValue = :maxValue, s.stepSize = :stepSize, s.updatedTime = CURRENT_TIMESTAMP " +
           "WHERE s.businessType = :businessType AND s.timeKey = :timeKey AND s.shardType = :shardType")
    int updateMaxValueAndStepSizeAtomicallyWithValue(@Param("businessType") String businessType,
                                                    @Param("timeKey") String timeKey,
                                                    @Param("shardType") Integer shardType,
                                                    @Param("maxValue") Long maxValue,
                                                    @Param("stepSize") Integer stepSize);
    
    /**
     * 获取指定分片类型的总负载（所有maxValue的总和）
     * 用于负载均衡计算
     */
    @Query("SELECT COALESCE(SUM(s.maxValue), 0) FROM IdSegment s WHERE s.shardType = :shardType")
    Long getTotalMaxValueByShardType(@Param("shardType") Integer shardType);
    
    /**
     * 根据业务类型和时间键删除号段（测试用）
     */
    @Modifying
    @Transactional
    void deleteByBusinessTypeAndTimeKey(String businessType, String timeKey);
    
    /**
     * 批量更新指定业务类型的所有号段的步长
     * 用于强制所有服务器使用相同的新步长
     */
    @Modifying
    @Transactional
    @Query("UPDATE IdSegment s SET s.stepSize = :newStepSize, s.updatedTime = CURRENT_TIMESTAMP " +
           "WHERE s.businessType = :businessType")
    int updateStepSizeForAllShards(@Param("businessType") String businessType,
                                  @Param("newStepSize") Integer newStepSize);
    
    /**
     * 批量更新所有号段的步长（全局步长同步）
     * 用于强制所有服务器使用相同的新步长
     */
    @Modifying
    @Transactional
    @Query("UPDATE IdSegment s SET s.stepSize = :newStepSize, s.updatedTime = CURRENT_TIMESTAMP")
    int updateStepSizeForAllSegments(@Param("newStepSize") Integer newStepSize);
    
    /**
     * 获取指定业务类型下所有分片的步长信息
     * 用于检查步长一致性
     */
    @Query("SELECT s.shardType, s.stepSize FROM IdSegment s WHERE s.businessType = :businessType")
    List<Object[]> getStepSizesByBusinessType(@Param("businessType") String businessType);
    
    /**
     * 检查指定业务类型下是否所有分片都使用相同步长
     */
    @Query("SELECT COUNT(DISTINCT s.stepSize) FROM IdSegment s WHERE s.businessType = :businessType")
    Long countDistinctStepSizesByBusinessType(@Param("businessType") String businessType);
}