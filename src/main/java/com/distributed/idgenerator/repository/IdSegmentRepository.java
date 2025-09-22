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
     * 根据业务类型查找所有号段
     */
    List<IdSegment> findByBusinessType(String businessType);

    /**
     * 根据业务类型和时间键查找号段
     */
    List<IdSegment> findByBusinessTypeAndTimeKey(String businessType, String timeKey);
}