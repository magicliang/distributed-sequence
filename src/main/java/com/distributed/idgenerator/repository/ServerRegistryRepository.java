package com.distributed.idgenerator.repository;

import com.distributed.idgenerator.entity.ServerRegistry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import javax.transaction.Transactional;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 服务器注册数据访问层
 * 
 * @author System
 * @version 1.0.0
 */
@Repository
public interface ServerRegistryRepository extends JpaRepository<ServerRegistry, Long> {

    /**
     * 根据服务器ID查找服务器
     */
    Optional<ServerRegistry> findByServerId(String serverId);

    /**
     * 根据服务器类型查找所有在线服务器
     */
    List<ServerRegistry> findByServerTypeAndStatus(Integer serverType, Integer status);

    /**
     * 查找所有在线服务器
     */
    List<ServerRegistry> findByStatus(Integer status);

    /**
     * 根据服务器类型查找所有服务器
     */
    List<ServerRegistry> findByServerType(Integer serverType);

    /**
     * 更新服务器心跳时间
     */
    @Modifying
    @Transactional
    @Query("UPDATE ServerRegistry s SET s.lastHeartbeat = CURRENT_TIMESTAMP " +
           "WHERE s.serverId = :serverId")
    int updateHeartbeat(@Param("serverId") String serverId);

    /**
     * 更新服务器状态
     */
    @Modifying
    @Transactional
    @Query("UPDATE ServerRegistry s SET s.status = :status, s.lastHeartbeat = CURRENT_TIMESTAMP " +
           "WHERE s.serverId = :serverId")
    int updateServerStatus(@Param("serverId") String serverId, @Param("status") Integer status);

    /**
     * 查找心跳超时的服务器
     */
    @Query("SELECT s FROM ServerRegistry s WHERE s.status = 1 AND s.lastHeartbeat < :timeoutTime")
    List<ServerRegistry> findTimeoutServers(@Param("timeoutTime") LocalDateTime timeoutTime);

    /**
     * 批量更新超时服务器状态为下线
     */
    @Modifying
    @Transactional
    @Query("UPDATE ServerRegistry s SET s.status = 0 WHERE s.lastHeartbeat < :timeoutTime AND s.status = 1")
    int markTimeoutServersOffline(@Param("timeoutTime") LocalDateTime timeoutTime);

    /**
     * 检查服务器是否存在
     */
    boolean existsByServerId(String serverId);

    /**
     * 统计指定类型的在线服务器数量
     */
    @Query("SELECT COUNT(s) FROM ServerRegistry s WHERE s.serverType = :serverType AND s.status = 1")
    long countOnlineServersByType(@Param("serverType") Integer serverType);

    /**
     * 获取指定类型的第一个在线服务器
     */
    @Query("SELECT s FROM ServerRegistry s WHERE s.serverType = :serverType AND s.status = 1 " +
           "ORDER BY s.lastHeartbeat DESC")
    List<ServerRegistry> findFirstOnlineServerByType(@Param("serverType") Integer serverType);
}