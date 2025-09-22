package com.distributed.idgenerator.service;

import com.distributed.idgenerator.dto.IdRequest;
import com.distributed.idgenerator.dto.IdResponse;
import com.distributed.idgenerator.entity.IdSegment;
import com.distributed.idgenerator.entity.ServerRegistry;
import com.distributed.idgenerator.repository.IdSegmentRepository;
import com.distributed.idgenerator.repository.ServerRegistryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.net.InetAddress;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * ID生成器核心服务
 * 
 * @author System
 * @version 1.0.0
 */
@Service
@Slf4j
public class IdGeneratorService {

    @Autowired
    private IdSegmentRepository idSegmentRepository;

    @Autowired
    private ServerRegistryRepository serverRegistryRepository;

    @Value("${id.generator.server.type:0}")
    private Integer serverType; // 0-偶数服务器, 1-奇数服务器

    @Value("${id.generator.step.size:1000}")
    private Integer defaultStepSize;

    @Value("${id.generator.segment.threshold:0.1}")
    private Double segmentThreshold; // 号段使用阈值，低于此值时预取新号段

    private String serverId;
    
    // 内存中的号段缓存 - 业务类型+时间键 -> 号段信息
    private final ConcurrentHashMap<String, SegmentBuffer> segmentBuffers = new ConcurrentHashMap<>();
    
    // 锁映射 - 每个业务类型+时间键对应一个锁
    private final ConcurrentHashMap<String, ReentrantLock> lockMap = new ConcurrentHashMap<>();

    /**
     * 号段缓冲区
     */
    private static class SegmentBuffer {
        private final AtomicLong currentValue;
        private volatile long maxValue;
        private volatile int shardType;
        private volatile boolean needRefresh;
        private final ReentrantLock refreshLock = new ReentrantLock();

        public SegmentBuffer(long currentValue, long maxValue, int shardType) {
            this.currentValue = new AtomicLong(currentValue);
            this.maxValue = maxValue;
            this.shardType = shardType;
            this.needRefresh = false;
        }

        public long getAndIncrement() {
            return currentValue.getAndIncrement();
        }

        public long getCurrentValue() {
            return currentValue.get();
        }

        public long getMaxValue() {
            return maxValue;
        }

        public void updateMaxValue(long newMaxValue) {
            this.maxValue = newMaxValue;
            this.needRefresh = false;
        }

        public boolean shouldRefresh(double threshold) {
            long current = currentValue.get();
            long total = maxValue - (maxValue % 2 == shardType ? maxValue - 1 : maxValue);
            return (double)(current - (current % 2 == shardType ? current - 1 : current)) / total > threshold;
        }

        public int getShardType() {
            return shardType;
        }

        public boolean isNeedRefresh() {
            return needRefresh;
        }

        public void setNeedRefresh(boolean needRefresh) {
            this.needRefresh = needRefresh;
        }

        public ReentrantLock getRefreshLock() {
            return refreshLock;
        }
    }

    @PostConstruct
    public void init() {
        try {
            // 生成服务器ID
            String hostName = InetAddress.getLocalHost().getHostName();
            String hostAddress = InetAddress.getLocalHost().getHostAddress();
            this.serverId = String.format("%s-%s-%d", hostName, hostAddress, serverType);
            
            // 注册服务器
            registerServer();
            
            log.info("ID生成器服务初始化完成，服务器ID: {}, 类型: {}", 
                    serverId, serverType == 0 ? "偶数服务器" : "奇数服务器");
        } catch (Exception e) {
            log.error("ID生成器服务初始化失败", e);
            throw new RuntimeException("服务初始化失败", e);
        }
    }

    /**
     * 注册服务器
     */
    private void registerServer() {
        try {
            Optional<ServerRegistry> existingServer = serverRegistryRepository.findByServerId(serverId);
            if (existingServer.isPresent()) {
                // 更新现有服务器状态
                serverRegistryRepository.updateServerStatus(serverId, 1);
                log.info("更新服务器状态: {}", serverId);
            } else {
                // 注册新服务器
                ServerRegistry server = ServerRegistry.builder()
                        .serverId(serverId)
                        .serverType(serverType)
                        .status(1)
                        .build();
                serverRegistryRepository.save(server);
                log.info("注册新服务器: {}", serverId);
            }
        } catch (Exception e) {
            log.error("服务器注册失败", e);
        }
    }

    /**
     * 生成ID
     */
    public IdResponse generateIds(IdRequest request) {
        String businessType = request.getBusinessType();
        String timeKey = request.getEffectiveTimeKey();
        int count = request.getEffectiveCount();
        
        String segmentKey = buildSegmentKey(businessType, timeKey);
        
        // 获取或创建号段缓冲区
        SegmentBuffer buffer = getOrCreateSegmentBuffer(segmentKey, businessType, timeKey, request);
        
        // 生成ID列表
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Long id = generateSingleId(buffer, segmentKey, businessType, timeKey, request);
            if (id != null) {
                ids.add(id);
            }
        }

        // 构建响应
        IdResponse response = IdResponse.builder()
                .ids(ids)
                .businessType(businessType)
                .timeKey(timeKey)
                .shardType(buffer.getShardType())
                .serverId(serverId)
                .timestamp(System.currentTimeMillis())
                .build();

        // 添加路由信息
        if (request.needRouting() && !ids.isEmpty()) {
            response.setRoutingInfo(calculateRouting(ids.get(0), request));
        }

        return response;
    }

    /**
     * 生成单个ID
     */
    private Long generateSingleId(SegmentBuffer buffer, String segmentKey, 
                                  String businessType, String timeKey, IdRequest request) {
        while (true) {
            long currentId = buffer.getAndIncrement();
            
            // 检查是否需要刷新号段
            if (buffer.shouldRefresh(segmentThreshold) && !buffer.isNeedRefresh()) {
                buffer.setNeedRefresh(true);
                // 异步刷新号段
                refreshSegmentAsync(segmentKey, businessType, timeKey, request);
            }
            
            // 检查ID是否超出范围
            if (currentId >= buffer.getMaxValue()) {
                // 同步刷新号段
                if (refreshSegmentSync(buffer, segmentKey, businessType, timeKey, request)) {
                    continue; // 重新尝试获取ID
                } else {
                    log.error("无法刷新号段，生成ID失败: {}", segmentKey);
                    return null;
                }
            }
            
            // 确保ID符合奇偶性要求
            if (currentId % 2 == buffer.getShardType()) {
                return currentId;
            }
        }
    }

    /**
     * 获取或创建号段缓冲区
     */
    private SegmentBuffer getOrCreateSegmentBuffer(String segmentKey, String businessType, 
                                                   String timeKey, IdRequest request) {
        return segmentBuffers.computeIfAbsent(segmentKey, k -> {
            // 确定分片类型
            int shardType = determineShardType(request);
            
            // 从数据库获取或创建号段
            IdSegment segment = getOrCreateSegment(businessType, timeKey, shardType, request);
            
            // 创建缓冲区
            long startValue = segment.getMaxValue() - segment.getStepSize();
            return new SegmentBuffer(startValue, segment.getMaxValue(), shardType);
        });
    }

    /**
     * 确定分片类型
     */
    private int determineShardType(IdRequest request) {
        // 如果强制指定分片类型
        if (request.getForceShardType() != null) {
            return request.getForceShardType();
        }
        
        // 检查是否需要切换到对方分片（容错机制）
        int targetShardType = serverType;
        int oppositeShardType = 1 - serverType;
        
        // 检查对方服务器是否在线
        List<ServerRegistry> oppositeServers = serverRegistryRepository
                .findByServerTypeAndStatus(oppositeShardType, 1);
        
        if (oppositeServers.isEmpty()) {
            // 对方服务器下线，当前服务器代理全部分片
            log.warn("对方服务器下线，当前服务器代理全部分片");
            // 可以根据业务需要选择奇数或偶数分片
            return targetShardType;
        }
        
        return targetShardType;
    }

    /**
     * 获取或创建号段
     */
    @Transactional
    private IdSegment getOrCreateSegment(String businessType, String timeKey, 
                                         int shardType, IdRequest request) {
        Optional<IdSegment> existingSegment = idSegmentRepository
                .findByBusinessTypeAndTimeKeyAndShardType(businessType, timeKey, shardType);
        
        if (existingSegment.isPresent()) {
            return existingSegment.get();
        }
        
        // 创建新号段
        int stepSize = request.getCustomStepSize() != null ? 
                request.getCustomStepSize() : defaultStepSize;
        
        IdSegment newSegment = IdSegment.builder()
                .businessType(businessType)
                .timeKey(timeKey)
                .shardType(shardType)
                .maxValue((long) stepSize)
                .stepSize(stepSize)
                .build();
        
        return idSegmentRepository.save(newSegment);
    }

    /**
     * 同步刷新号段
     */
    private boolean refreshSegmentSync(SegmentBuffer buffer, String segmentKey, 
                                       String businessType, String timeKey, IdRequest request) {
        ReentrantLock lock = lockMap.computeIfAbsent(segmentKey, k -> new ReentrantLock());
        
        lock.lock();
        try {
            // 双重检查
            if (buffer.getCurrentValue() < buffer.getMaxValue()) {
                return true;
            }
            
            return refreshSegmentFromDB(buffer, businessType, timeKey, request);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 异步刷新号段
     */
    private void refreshSegmentAsync(String segmentKey, String businessType, 
                                     String timeKey, IdRequest request) {
        // 这里可以使用线程池异步执行
        // 为简化实现，暂时使用同步方式
        SegmentBuffer buffer = segmentBuffers.get(segmentKey);
        if (buffer != null) {
            refreshSegmentFromDB(buffer, businessType, timeKey, request);
        }
    }

    /**
     * 从数据库刷新号段
     */
    @Transactional
    private boolean refreshSegmentFromDB(SegmentBuffer buffer, String businessType, 
                                         String timeKey, IdRequest request) {
        try {
            int shardType = buffer.getShardType();
            int stepSize = request.getCustomStepSize() != null ? 
                    request.getCustomStepSize() : defaultStepSize;
            
            // 原子性更新数据库中的最大值
            int updateCount = idSegmentRepository.updateMaxValueAtomically(
                    businessType, timeKey, shardType, stepSize);
            
            if (updateCount > 0) {
                // 获取更新后的最大值
                Optional<Long> newMaxValue = idSegmentRepository
                        .getCurrentMaxValue(businessType, timeKey, shardType);
                
                if (newMaxValue.isPresent()) {
                    buffer.updateMaxValue(newMaxValue.get());
                    log.debug("刷新号段成功: {} -> {}", buildSegmentKey(businessType, timeKey), newMaxValue.get());
                    return true;
                }
            }
            
            log.error("刷新号段失败: {}", buildSegmentKey(businessType, timeKey));
            return false;
        } catch (Exception e) {
            log.error("刷新号段异常: " + buildSegmentKey(businessType, timeKey), e);
            return false;
        }
    }

    /**
     * 计算路由信息
     */
    private IdResponse.RoutingInfo calculateRouting(Long id, IdRequest request) {
        if (!request.needRouting()) {
            return null;
        }
        
        int dbCount = request.getShardDbCount();
        int tableCount = request.getShardTableCount() != null ? request.getShardTableCount() : 1;
        
        // 使用ID的低位进行路由计算
        long routingKey = id;
        int dbIndex = (int) (routingKey % dbCount);
        int tableIndex = (int) ((routingKey / dbCount) % tableCount);
        
        return IdResponse.RoutingInfo.builder()
                .dbIndex(dbIndex)
                .tableIndex(tableIndex)
                .shardDbCount(dbCount)
                .shardTableCount(tableCount)
                .routingKey(routingKey)
                .build();
    }

    /**
     * 构建号段键
     */
    private String buildSegmentKey(String businessType, String timeKey) {
        return businessType + ":" + timeKey;
    }

    /**
     * 获取服务器状态
     */
    public Map<String, Object> getServerStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("serverId", serverId);
        status.put("serverType", serverType);
        status.put("serverTypeDesc", serverType == 0 ? "偶数服务器" : "奇数服务器");
        status.put("segmentBufferCount", segmentBuffers.size());
        status.put("timestamp", System.currentTimeMillis());
        
        // 统计各分片类型的在线服务器数量
        long evenServerCount = serverRegistryRepository.countOnlineServersByType(0);
        long oddServerCount = serverRegistryRepository.countOnlineServersByType(1);
        
        status.put("evenServerCount", evenServerCount);
        status.put("oddServerCount", oddServerCount);
        
        return status;
    }

    /**
     * 清理过期号段
     */
    @Transactional
    public int cleanExpiredSegments(String expiredTimeKey) {
        return idSegmentRepository.deleteExpiredSegments(expiredTimeKey);
    }
}