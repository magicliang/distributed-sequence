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

    /**
     * ID号段数据访问层
     * 用于操作id_segment表，包括查询、更新号段信息
     * 提供号段的持久化存储和原子性更新操作
     */
    @Autowired
    private IdSegmentRepository idSegmentRepository;

    /**
     * 服务器注册信息数据访问层
     * 用于操作server_registry表，管理服务器注册、心跳、状态等信息
     * 支持服务发现和健康检查功能
     */
    @Autowired
    private ServerRegistryRepository serverRegistryRepository;

    /**
     * 当前服务器类型配置
     * 从配置文件id.generator.server.type读取，默认值为0
     * 0 - 偶数服务器：负责处理偶数分片的ID生成
     * 1 - 奇数服务器：负责处理奇数分片的ID生成
     * 用于实现奇偶分片的负载均衡策略
     */
    @Value("${id.generator.server.type:0}")
    private Integer serverType;

    /**
     * 默认号段步长配置
     * 从配置文件id.generator.step.size读取，默认值为1000
     * 表示每次从数据库申请号段时的增量大小
     * 较大的步长减少数据库访问但可能浪费ID，较小的步长节约ID但增加访问频率
     * 可通过请求参数customStepSize进行覆盖
     */
    @Value("${id.generator.step.size:1000}")
    private Integer defaultStepSize;

    /**
     * 号段刷新阈值配置
     * 从配置文件id.generator.segment.threshold读取，默认值为0.1（10%）
     * 当号段使用率超过此阈值时，触发异步预取新号段
     * 用于避免号段耗尽时的阻塞等待，提高系统响应性能
     * 取值范围：0.0-1.0，建议设置为0.1-0.3之间
     */
    @Value("${id.generator.segment.threshold:0.1}")
    private Double segmentThreshold;

    /**
     * 当前服务器的唯一标识符
     * 在服务启动时自动生成，格式：主机名-IP地址-服务器类型
     * 用于服务器注册、心跳上报和请求追踪
     * 例如：server01-192.168.1.100-0
     */
    private String serverId;
    
    /**
     * 内存中的号段缓存映射
     * Key：业务类型+时间键的组合（格式：businessType:timeKey）
     * Value：对应的号段缓冲区对象
     * 用于缓存从数据库获取的号段信息，避免频繁数据库访问
     * 线程安全的ConcurrentHashMap实现
     */
    private final ConcurrentHashMap<String, SegmentBuffer> segmentBuffers = new ConcurrentHashMap<>();
    
    /**
     * 号段操作锁映射
     * Key：业务类型+时间键的组合（格式：businessType:timeKey）
     * Value：对应的可重入锁对象
     * 用于保证同一业务类型和时间键的号段操作的线程安全性
     * 避免并发访问时的数据竞争和重复申请号段
     */
    private final ConcurrentHashMap<String, ReentrantLock> lockMap = new ConcurrentHashMap<>();

    /**
     * 号段缓冲区内部类
     * 用于在内存中缓存和管理单个号段的状态信息
     * 提供线程安全的ID分配和号段刷新机制
     */
    private static class SegmentBuffer {
        /**
         * 当前ID值的原子计数器
         * 使用AtomicLong保证多线程环境下的原子性操作
         * 每次获取ID时通过getAndIncrement()方法原子性递增
         * 初始值为号段的起始值
         */
        private final AtomicLong currentValue;
        
        /**
         * 当前号段的最大值
         * 使用volatile关键字保证多线程可见性
         * 当currentValue达到此值时，需要申请新的号段
         * 在号段刷新时会被更新为新的最大值
         */
        private volatile long maxValue;
        
        /**
         * 号段的分片类型
         * 使用volatile关键字保证多线程可见性
         * 0 - 偶数分片：生成偶数ID
         * 1 - 奇数分片：生成奇数ID
         * 决定了ID的奇偶性和分片策略
         */
        private volatile int shardType;
        
        /**
         * 号段刷新标志位
         * 使用volatile关键字保证多线程可见性
         * true - 需要刷新号段（已触发预取条件）
         * false - 号段正常，无需刷新
         * 用于避免重复触发号段预取操作
         */
        private volatile boolean needRefresh;
        
        /**
         * 号段刷新操作的互斥锁
         * 使用ReentrantLock保证号段刷新操作的原子性
         * 避免多个线程同时执行号段刷新，造成重复申请
         * 支持可重入特性，同一线程可以多次获取锁
         */
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
     * 使用事务管理确保ID生成过程的数据一致性
     */
    @Transactional
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
     * 
     * 该方法会根据当前系统状态智能选择分片类型：
     * - 正常模式：使用当前服务器的分片类型（奇数服务器处理奇数分片，偶数服务器处理偶数分片）
     * - 容错模式：当对方服务器下线时，当前服务器代理全部分片，根据业务类型和时间键的哈希值分配分片
     * 
     * @param segmentKey 号段缓存键，格式为：业务类型_时间键_分片类型
     * @param businessType 业务类型，用于区分不同业务场景的ID生成需求
     * @param timeKey 时间键，用于按时间维度分片管理ID号段
     * @param request ID生成请求对象，包含生成参数和配置信息
     * @return 号段缓冲区对象，包含当前可用的ID范围和分片信息
     */
    private SegmentBuffer getOrCreateSegmentBuffer(String segmentKey, String businessType, 
                                                   String timeKey, IdRequest request) {
        return segmentBuffers.computeIfAbsent(segmentKey, k -> {
            // 智能确定分片类型（支持容错机制）
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
        
        // 正常情况下使用当前服务器的分片类型
        int targetShardType = serverType;
        int oppositeShardType = 1 - serverType;
        
        // 检查对方服务器是否在线
        List<ServerRegistry> oppositeServers = serverRegistryRepository
                .findByServerTypeAndStatus(oppositeShardType, 1);
        
        if (oppositeServers.isEmpty()) {
            // 对方服务器下线，当前服务器需要代理全部分片
            log.warn("对方服务器下线，当前服务器代理全部分片");
            
            // 根据业务类型和时间键的哈希值来决定使用哪个分片
            // 这样可以保证请求的分布相对均匀
            String hashKey = request.getBusinessType() + "_" + request.getEffectiveTimeKey();
            int hashValue = Math.abs(hashKey.hashCode());
            
            // 使用哈希值的奇偶性来决定分片类型，保证负载均衡
            int calculatedShardType = hashValue % 2;
            
            log.debug("容错模式：业务类型={}, 时间键={}, 计算分片类型={}", 
                    request.getBusinessType(), request.getEffectiveTimeKey(), calculatedShardType);
            
            return calculatedShardType;
        }
        
        // 对方服务器在线，使用当前服务器的分片类型
        return targetShardType;
    }

    /**
     * 检查当前服务器是否处于容错模式（对方服务器下线）
     * 
     * @return true表示处于容错模式，需要代理全部分片；false表示正常模式
     */
    private boolean isInFailoverMode() {
        int oppositeShardType = 1 - serverType;
        List<ServerRegistry> oppositeServers = serverRegistryRepository
                .findByServerTypeAndStatus(oppositeShardType, 1);
        
        boolean failoverMode = oppositeServers.isEmpty();
        if (failoverMode) {
            log.debug("当前处于容错模式，代理全部分片");
        }
        
        return failoverMode;
    }

    /**
     * 获取或创建号段
     * 注意：移除了@Transactional注解，因为private方法上的事务注解不会生效
     * 事务管理应该在调用此方法的public方法上进行
     */
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
     * 注意：移除了@Transactional注解，因为private方法上的事务注解不会生效
     * 该方法在generateIds()事务中被调用，依赖外层事务管理
     * 
     * 支持步长变更：
     * 1. 检测步长是否发生变化
     * 2. 如果步长变化，同时更新maxValue和stepSize
     * 3. 如果步长未变化，仅更新maxValue
     */
    private boolean refreshSegmentFromDB(SegmentBuffer buffer, String businessType, 
                                         String timeKey, IdRequest request) {
        try {
            int shardType = buffer.getShardType();
            int newStepSize = request.getCustomStepSize() != null ? 
                    request.getCustomStepSize() : defaultStepSize;
            
            // 获取当前数据库中的号段信息
            Optional<IdSegment> currentSegment = idSegmentRepository
                    .getSegmentInfo(businessType, timeKey, shardType);
            
            int updateCount = 0;
            boolean stepSizeChanged = false;
            
            if (currentSegment.isPresent()) {
                IdSegment segment = currentSegment.get();
                stepSizeChanged = segment.needsStepSizeUpdate(newStepSize);
                
                if (stepSizeChanged) {
                    // 步长发生变化，同时更新最大值和步长
                    updateCount = idSegmentRepository.updateMaxValueAndStepSizeAtomically(
                            businessType, timeKey, shardType, newStepSize);
                    log.info("检测到步长变更: {} 从 {} 变更为 {}", 
                            buildSegmentKey(businessType, timeKey), segment.getStepSize(), newStepSize);
                } else {
                    // 步长未变化，仅更新最大值
                    updateCount = idSegmentRepository.updateMaxValueAtomically(
                            businessType, timeKey, shardType, newStepSize);
                }
            } else {
                // 号段不存在，使用普通更新
                updateCount = idSegmentRepository.updateMaxValueAtomically(
                        businessType, timeKey, shardType, newStepSize);
            }
            
            if (updateCount > 0) {
                // 获取更新后的最大值
                Optional<Long> newMaxValue = idSegmentRepository
                        .getCurrentMaxValue(businessType, timeKey, shardType);
                
                if (newMaxValue.isPresent()) {
                    buffer.updateMaxValue(newMaxValue.get());
                    
                    if (stepSizeChanged) {
                        log.info("号段刷新成功（含步长变更）: {} -> {}, 新步长: {}", 
                                buildSegmentKey(businessType, timeKey), newMaxValue.get(), newStepSize);
                    } else {
                        log.debug("号段刷新成功: {} -> {}", 
                                buildSegmentKey(businessType, timeKey), newMaxValue.get());
                    }
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

    /**
     * 步长变更管理
     * 
     * @param businessType 业务类型
     * @param timeKey 时间键（可选）
     * @param newStepSize 新的步长值
     * @param preview 是否仅预览
     * @return 变更结果
     */
    @Transactional
    public Map<String, Object> changeStepSize(String businessType, String timeKey, 
                                              Integer newStepSize, Boolean preview) {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> affectedSegments = new ArrayList<>();
        
        try {
            // 参数验证
            if (newStepSize == null || newStepSize <= 0) {
                throw new IllegalArgumentException("步长必须大于0");
            }
            
            // 查找受影响的号段
            List<IdSegment> segments;
            if (timeKey != null && !timeKey.trim().isEmpty()) {
                // 指定时间键
                segments = idSegmentRepository.findByBusinessTypeAndTimeKey(businessType, timeKey);
            } else {
                // 所有时间键
                segments = idSegmentRepository.findByBusinessType(businessType);
            }
            
            int changedCount = 0;
            int skippedCount = 0;
            
            for (IdSegment segment : segments) {
                Map<String, Object> segmentInfo = new HashMap<>();
                segmentInfo.put("businessType", segment.getBusinessType());
                segmentInfo.put("timeKey", segment.getTimeKey());
                segmentInfo.put("shardType", segment.getShardType());
                segmentInfo.put("currentStepSize", segment.getStepSize());
                segmentInfo.put("newStepSize", newStepSize);
                
                if (segment.needsStepSizeUpdate(newStepSize)) {
                    segmentInfo.put("action", preview ? "WILL_UPDATE" : "UPDATED");
                    segmentInfo.put("changed", true);
                    
                    if (!preview) {
                        // 实际执行更新
                        segment.updateStepSizeIfNeeded(newStepSize);
                        idSegmentRepository.save(segment);
                        
                        // 清理对应的缓存
                        String segmentKey = buildSegmentKey(segment.getBusinessType(), segment.getTimeKey());
                        segmentBuffers.remove(segmentKey);
                        
                        log.info("步长变更完成: {} 从 {} 变更为 {}", 
                                segmentKey, segment.getStepSize(), newStepSize);
                    }
                    changedCount++;
                } else {
                    segmentInfo.put("action", "NO_CHANGE");
                    segmentInfo.put("changed", false);
                    skippedCount++;
                }
                
                affectedSegments.add(segmentInfo);
            }
            
            result.put("success", true);
            result.put("preview", preview);
            result.put("businessType", businessType);
            result.put("timeKey", timeKey);
            result.put("newStepSize", newStepSize);
            result.put("totalSegments", segments.size());
            result.put("changedCount", changedCount);
            result.put("skippedCount", skippedCount);
            result.put("affectedSegments", affectedSegments);
            result.put("timestamp", System.currentTimeMillis());
            
            if (preview) {
                result.put("message", String.format("预览完成：将影响 %d 个号段，其中 %d 个需要变更，%d 个无需变更", 
                        segments.size(), changedCount, skippedCount));
            } else {
                result.put("message", String.format("变更完成：共处理 %d 个号段，其中 %d 个已变更，%d 个无需变更", 
                        segments.size(), changedCount, skippedCount));
            }
            
        } catch (Exception e) {
            log.error("步长变更失败", e);
            result.put("success", false);
            result.put("message", "步长变更失败: " + e.getMessage());
            result.put("timestamp", System.currentTimeMillis());
        }
        
        return result;
    }

    /**
     * 获取当前步长配置信息
     * 
     * @param businessType 业务类型（可选）
     * @return 步长配置信息
     */
    public Map<String, Object> getCurrentStepSizeInfo(String businessType) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            result.put("defaultStepSize", defaultStepSize);
            result.put("timestamp", System.currentTimeMillis());
            
            if (businessType != null && !businessType.trim().isEmpty()) {
                // 查询指定业务类型的步长信息
                List<IdSegment> segments = idSegmentRepository.findByBusinessType(businessType);
                
                Map<String, List<Map<String, Object>>> groupedSegments = new HashMap<>();
                
                for (IdSegment segment : segments) {
                    String timeKey = segment.getTimeKey();
                    groupedSegments.computeIfAbsent(timeKey, k -> new ArrayList<>());
                    
                    Map<String, Object> segmentInfo = new HashMap<>();
                    segmentInfo.put("shardType", segment.getShardType());
                    segmentInfo.put("stepSize", segment.getStepSize());
                    segmentInfo.put("maxValue", segment.getMaxValue());
                    segmentInfo.put("updatedTime", segment.getUpdatedTime());
                    
                    groupedSegments.get(timeKey).add(segmentInfo);
                }
                
                result.put("businessType", businessType);
                result.put("segments", groupedSegments);
                result.put("totalSegments", segments.size());
            } else {
                // 查询所有业务类型的统计信息
                List<String> businessTypes = idSegmentRepository.findAllDistinctBusinessTypes();
                List<Map<String, Object>> businessTypeStats = new ArrayList<>();
                
                for (String bt : businessTypes) {
                    List<IdSegment> segments = idSegmentRepository.findByBusinessType(bt);
                    
                    Map<String, Object> stats = new HashMap<>();
                    stats.put("businessType", bt);
                    stats.put("segmentCount", segments.size());
                    
                    // 统计不同步长的使用情况
                    Map<Integer, Integer> stepSizeCount = new HashMap<>();
                    for (IdSegment segment : segments) {
                        stepSizeCount.merge(segment.getStepSize(), 1, Integer::sum);
                    }
                    stats.put("stepSizeDistribution", stepSizeCount);
                    
                    businessTypeStats.add(stats);
                }
                
                result.put("businessTypes", businessTypeStats);
                result.put("totalBusinessTypes", businessTypes.size());
            }
            
            result.put("success", true);
            
        } catch (Exception e) {
            log.error("查询步长配置失败", e);
            result.put("success", false);
            result.put("message", "查询失败: " + e.getMessage());
            result.put("timestamp", System.currentTimeMillis());
        }
        
        return result;
    }
}