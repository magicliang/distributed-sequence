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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.net.InetAddress;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
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
         * 奇偶区间错开模式：初始值为区间的起始值
         */
        private final AtomicLong currentValue;
        
        /**
         * 当前号段的最大值
         * 使用volatile关键字保证多线程可见性
         * 当currentValue达到此值时，需要申请新的号段
         * 奇偶区间错开模式：表示当前区间的结束值
         */
        private volatile long maxValue;
        
        /**
         * 当前号段的起始值
         * 使用volatile关键字保证多线程可见性
         * 奇偶区间错开模式：表示当前区间的起始值
         */
        private volatile long startValue;
        
        /**
         * 号段的分片类型
         * 使用volatile关键字保证多线程可见性
         * 0 - 偶数分片：使用偶数区间
         * 1 - 奇数分片：使用奇数区间
         * 决定了区间的分配策略
         */
        private volatile int shardType;
        
        /**
         * 号段刷新标志位
         * 使用AtomicBoolean保证多线程下的原子操作
         * true - 需要刷新号段（已触发预取条件）
         * false - 号段正常，无需刷新
         * 通过CAS操作确保只有一个线程能触发刷新操作
         */
        private final AtomicBoolean needRefresh;
        
        /**
         * 最后一次刷新尝试的时间戳
         * 用于检测刷新超时和实现自动恢复机制
         */
        private volatile long lastRefreshAttemptTime;
        
        /**
         * 刷新超时时间（毫秒）
         * 如果刷新操作超过此时间仍未完成，允许其他线程重新尝试
         */
        private static final long REFRESH_TIMEOUT_MS = 10000; // 10秒
        


        public SegmentBuffer(long startValue, long maxValue, int shardType) {
            this.currentValue = new AtomicLong(startValue);
            this.startValue = startValue;
            this.maxValue = maxValue;
            this.shardType = shardType;
            this.needRefresh = new AtomicBoolean(false);
            this.lastRefreshAttemptTime = 0;
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
        
        public long getStartValue() {
            return startValue;
        }

        public void updateMaxValue(long newMaxValue) {
            this.maxValue = newMaxValue;
            this.needRefresh.set(false);
            this.lastRefreshAttemptTime = 0; // 重置刷新尝试时间，避免误判超时
        }
        
        /**
         * 更新区间范围
         * 奇偶区间错开模式：同时更新起始值和最大值
         * 成功刷新时重置所有刷新相关的状态
         */
        public void updateRange(long newStartValue, long newMaxValue) {
            this.startValue = newStartValue;
            this.maxValue = newMaxValue;
            this.currentValue.set(newStartValue);
            this.needRefresh.set(false);
            this.lastRefreshAttemptTime = 0; // 重置刷新尝试时间，避免误判超时
        }

        /**
         * 判断是否需要刷新号段
         * 奇偶区间错开模式：基于区间使用率计算
         */
        public boolean shouldRefresh(double threshold) {
            long current = currentValue.get();
            long total = maxValue - startValue + 1;
            long used = current - startValue + 1;
            return (double) used / total > threshold;
        }

        public int getShardType() {
            return shardType;
        }

        public boolean isNeedRefresh() {
            return needRefresh.get();
        }

        /**
         * 尝试设置刷新标志位（增强版）
         * 使用CAS操作确保只有一个线程能成功设置
         * 支持超时检测：如果上次刷新尝试超时，允许新的线程重新尝试
         * @return true表示成功设置，false表示已经被其他线程设置
         */
        public boolean trySetNeedRefresh() {
            long currentTime = System.currentTimeMillis();
            
            // 首先尝试正常的CAS操作
            if (needRefresh.compareAndSet(false, true)) {
                lastRefreshAttemptTime = currentTime;
                return true;
            }
            
            // 如果CAS失败，检查是否是因为上次刷新超时
            if (needRefresh.get() && isRefreshTimeout(currentTime)) {
                // 上次刷新已超时，强制重置并重新尝试
                log.warn("检测到刷新操作超时，强制重置刷新标志位进行恢复");
                needRefresh.set(false);
                
                // 重新尝试CAS操作
                if (needRefresh.compareAndSet(false, true)) {
                    lastRefreshAttemptTime = currentTime;
                    return true;
                }
            }
            
            return false;
        }
        
        /**
         * 检查刷新操作是否超时
         */
        private boolean isRefreshTimeout(long currentTime) {
            return lastRefreshAttemptTime > 0 && 
                   (currentTime - lastRefreshAttemptTime) > REFRESH_TIMEOUT_MS;
        }
        
        /**
         * 重置刷新标志位
         * 在刷新完成后调用
         */
        public void resetNeedRefresh() {
            needRefresh.set(false);
            lastRefreshAttemptTime = 0;
        }
        
        /**
         * 获取刷新状态信息（用于监控和调试）
         */
        public Map<String, Object> getRefreshStatus() {
            long currentTime = System.currentTimeMillis();
            Map<String, Object> status = new HashMap<>();
            status.put("needRefresh", needRefresh.get());
            status.put("lastRefreshAttemptTime", lastRefreshAttemptTime);
            status.put("timeSinceLastAttempt", lastRefreshAttemptTime > 0 ? 
                      currentTime - lastRefreshAttemptTime : 0);
            status.put("isTimeout", isRefreshTimeout(currentTime));
            return status;
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
     * 奇偶区间错开模式：奇数服务器使用奇数区间，偶数服务器使用偶数区间
     */
    private Long generateSingleId(SegmentBuffer buffer, String segmentKey, 
                                  String businessType, String timeKey, IdRequest request) {
        while (true) {
            long currentId = buffer.getAndIncrement();
            
            // 检查是否需要刷新号段
            if (buffer.shouldRefresh(segmentThreshold) && buffer.trySetNeedRefresh()) {
                // 只有成功设置刷新标志位的线程才会执行异步刷新
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
            
            // 奇偶区间错开模式：直接返回区间内的ID，无需奇偶性检查
            return currentId;
        }
    }

    /**
     * 获取或创建号段缓冲区
     * 
     * 奇偶区间错开模式：
     * - 奇数服务器：使用奇数区间 (1-1000, 2001-3000, 4001-5000, ...)
     * - 偶数服务器：使用偶数区间 (1001-2000, 3001-4000, 5001-6000, ...)
     * - 容错模式：当对方服务器下线时，当前服务器代理全部区间
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
            
            // 奇偶区间错开模式：计算区间起始值
            long startValue = calculateIntervalStartValue(segment.getMaxValue(), segment.getStepSize(), shardType);
            long endValue = segment.getMaxValue();
            
            return new SegmentBuffer(startValue, endValue, shardType);
        });
    }
    
    /**
     * 计算奇偶区间的起始值
     * 奇偶区间错开规则：
     * - 奇数服务器：区间 [1, stepSize], [2*stepSize+1, 3*stepSize], [4*stepSize+1, 5*stepSize], ...
     * - 偶数服务器：区间 [stepSize+1, 2*stepSize], [3*stepSize+1, 4*stepSize], [5*stepSize+1, 6*stepSize], ...
     * 
     * @param maxValue 当前最大值
     * @param stepSize 步长
     * @param shardType 分片类型 (0-偶数, 1-奇数)
     * @return 区间起始值
     */
    private long calculateIntervalStartValue(long maxValue, int stepSize, int shardType) {
        // 计算当前是第几个区间
        long intervalIndex = (maxValue - 1) / stepSize;
        
        if (shardType == 1) {
            // 奇数服务器：使用奇数区间
            // 第0个区间: [1, stepSize]
            // 第1个区间: [2*stepSize+1, 3*stepSize]
            // 第2个区间: [4*stepSize+1, 5*stepSize]
            if (intervalIndex % 2 == 0) {
                return intervalIndex * stepSize + 1;
            } else {
                return (intervalIndex + 1) * stepSize + 1;
            }
        } else {
            // 偶数服务器：使用偶数区间
            // 第0个区间: [stepSize+1, 2*stepSize]
            // 第1个区间: [3*stepSize+1, 4*stepSize]
            // 第2个区间: [5*stepSize+1, 6*stepSize]
            if (intervalIndex % 2 == 0) {
                return stepSize + 1;
            } else {
                return (intervalIndex + 1) * stepSize + 1;
            }
        }
    }

    /**
     * 智能确定分片类型
     * 实现动态负载均衡和容错切换机制
     */
    private int determineShardType(IdRequest request) {
        // 如果强制指定分片类型
        if (request.getForceShardType() != null) {
            return request.getForceShardType();
        }
        
        String businessType = request.getBusinessType();
        String timeKey = request.getEffectiveTimeKey();
        
        // 检查两种分片类型的服务器状态
        boolean evenServerOnline = !serverRegistryRepository.findByServerTypeAndStatus(0, 1).isEmpty();
        boolean oddServerOnline = !serverRegistryRepository.findByServerTypeAndStatus(1, 1).isEmpty();
        
        if (evenServerOnline && oddServerOnline) {
            // 双方都在线，使用智能负载均衡策略
            return selectBalancedShardType(businessType, timeKey);
        } else if (evenServerOnline || oddServerOnline) {
            // 有一方下线，当前服务器接管全部分片
            int offlineType = evenServerOnline ? 1 : 0;
            log.warn("{}服务器下线，当前服务器接管全部分片", offlineType == 0 ? "偶数" : "奇数");
            
            // 使用动态选择策略，优先使用负载较轻的分片
            return selectAnyAvailableShardType(businessType, timeKey);
        } else {
            // 异常情况，使用当前服务器类型
            log.warn("无法检测到其他在线服务器，使用当前服务器类型: {}", serverType);
            return serverType;
        }
    }
    
    /**
     * 选择负载均衡的分片类型
     * 双方服务器都在线时使用
     */
    private int selectBalancedShardType(String businessType, String timeKey) {
        // 查询当前两种分片类型的使用情况
        Optional<IdSegment> evenSegment = idSegmentRepository.findByBusinessTypeAndTimeKeyAndShardType(businessType, timeKey, 0);
        Optional<IdSegment> oddSegment = idSegmentRepository.findByBusinessTypeAndTimeKeyAndShardType(businessType, timeKey, 1);
        
        // 如果都不存在，根据当前服务器类型和负载情况选择
        if (!evenSegment.isPresent() && !oddSegment.isPresent()) {
            // 检查全局负载情况
            long evenServerLoad = getServerTypeLoad(0);
            long oddServerLoad = getServerTypeLoad(1);
            
            // 选择负载较轻的服务器类型
            if (evenServerLoad <= oddServerLoad) {
                return 0;
            } else {
                return 1;
            }
        } else if (!evenSegment.isPresent()) {
            return 0; // 偶数分片未使用，优先使用
        } else if (!oddSegment.isPresent()) {
            return 1; // 奇数分片未使用，优先使用
        } else {
            // 都存在，比较使用情况选择负载较轻的
            long evenUsage = evenSegment.get().getMaxValue();
            long oddUsage = oddSegment.get().getMaxValue();
            
            // 考虑步长差异，计算实际使用率
            double evenUsageRate = (double) evenUsage / evenSegment.get().getStepSize();
            double oddUsageRate = (double) oddUsage / oddSegment.get().getStepSize();
            
            return evenUsageRate <= oddUsageRate ? 0 : 1;
        }
    }
    
    /**
     * 选择任意可用的分片类型
     * 容错模式下使用，可以使用任意分片类型
     */
    private int selectAnyAvailableShardType(String businessType, String timeKey) {
        // 查询两种分片类型的使用情况
        Optional<IdSegment> evenSegment = idSegmentRepository.findByBusinessTypeAndTimeKeyAndShardType(businessType, timeKey, 0);
        Optional<IdSegment> oddSegment = idSegmentRepository.findByBusinessTypeAndTimeKeyAndShardType(businessType, timeKey, 1);
        
        // 优先选择已存在且使用率较低的分片
        if (evenSegment.isPresent() && oddSegment.isPresent()) {
            // 比较使用情况
            long evenUsage = evenSegment.get().getMaxValue();
            long oddUsage = oddSegment.get().getMaxValue();
            return evenUsage <= oddUsage ? 0 : 1;
        } else if (evenSegment.isPresent()) {
            return 0;
        } else if (oddSegment.isPresent()) {
            return 1;
        } else {
            // 都不存在，使用哈希分布确保均匀性
            String hashKey = businessType + "_" + timeKey;
            return Math.abs(hashKey.hashCode()) % 2;
        }
    }
    
    /**
     * 获取指定服务器类型的负载情况
     */
    private long getServerTypeLoad(int serverType) {
        try {
            return idSegmentRepository.getTotalMaxValueByShardType(serverType);
        } catch (Exception e) {
            log.warn("获取服务器类型{}负载失败: {}", serverType, e.getMessage());
            return 0;
        }
    }

    /**
     * 检查当前服务器是否处于容错模式（对方服务器下线）
     * 
     * @return true表示处于容错模式，需要代理全部分片；false表示正常模式
     */
    private boolean isInFailoverMode() {
        boolean evenServerOnline = !serverRegistryRepository.findByServerTypeAndStatus(0, 1).isEmpty();
        boolean oddServerOnline = !serverRegistryRepository.findByServerTypeAndStatus(1, 1).isEmpty();
        
        // 只有一种类型的服务器在线时，就是容错模式
        boolean failoverMode = !(evenServerOnline && oddServerOnline);
        
        // 或者当前有代理分片时，也认为是容错模式
        boolean hasProxyShards = segmentBuffers.keySet().stream()
                .anyMatch(key -> key.contains("_proxy_"));
        
        failoverMode = failoverMode || hasProxyShards;
        
        if (failoverMode) {
            String onlineType = evenServerOnline ? "偶数" : (oddServerOnline ? "奇数" : "无");
            log.debug("当前处于容错模式，在线服务器类型: {}, 代理分片数量: {}", 
                    onlineType, hasProxyShards ? getProxyShardCount() : 0);
        }
        
        return failoverMode;
    }
    
    /**
     * 获取代理分片数量
     */
    private int getProxyShardCount() {
        return (int) segmentBuffers.keySet().stream()
                .filter(key -> key.contains("_proxy_"))
                .count();
    }
    
    /**
     * 确保恢复的节点获取新的增长segment
     * 清理本节点类型的所有缓存，强制从数据库重新获取
     */
    public void ensureRecoveredNodeGetsNewSegment() {
        try {
            List<String> keysToRemove = new ArrayList<>();
            
            // 找到所有属于当前节点类型的segment缓存
            for (String key : segmentBuffers.keySet()) {
                if (!key.contains("_proxy_")) {
                    // 这是正常的segment，检查是否属于当前节点类型
                    SegmentBuffer buffer = segmentBuffers.get(key);
                    if (buffer != null && buffer.getShardType() == serverType) {
                        keysToRemove.add(key);
                    }
                }
            }
            
            // 清理这些缓存
            for (String key : keysToRemove) {
                segmentBuffers.remove(key);
                log.info("清理恢复节点的segment缓存: {}", key);
            }
            
            if (!keysToRemove.isEmpty()) {
                log.info("恢复节点清理了 {} 个segment缓存，下次请求将从数据库获取新的增长segment", 
                        keysToRemove.size());
            }
            
        } catch (Exception e) {
            log.error("清理恢复节点segment缓存失败", e);
        }
    }
    
    /**
     * 服务器故障转移处理
     * 定期检查并处理服务器切换
     */
    public void handleServerFailover() {
        boolean evenServerOnline = !serverRegistryRepository.findByServerTypeAndStatus(0, 1).isEmpty();
        boolean oddServerOnline = !serverRegistryRepository.findByServerTypeAndStatus(1, 1).isEmpty();
        boolean wasInFailoverMode = isInFailoverMode();
        
        if (!evenServerOnline && oddServerOnline && serverType == 1) {
            // 偶数服务器下线，奇数服务器接管
            log.info("检测到偶数服务器下线，奇数服务器开始接管偶数分片");
            takeOverShards(0);
        } else if (evenServerOnline && !oddServerOnline && serverType == 0) {
            // 奇数服务器下线，偶数服务器接管
            log.info("检测到奇数服务器下线，偶数服务器开始接管奇数分片");
            takeOverShards(1);
        } else if (evenServerOnline && oddServerOnline && wasInFailoverMode) {
            // 双方都恢复，使用简单的放弃策略
            log.info("检测到服务器恢复，使用简单放弃策略清理代理状态");
            
            // 1. 放弃所有代理分片（允许ID浪费）
            simpleAbandonProxyShards();
            
            // 2. 清理本节点的segment缓存，确保获取新的增长segment
            ensureRecoveredNodeGetsNewSegment();
            
            log.info("服务器恢复完成，恢复节点将从数据库获取新的增长segment");
        }
    }
    
    /**
     * 接管指定类型的分片
     */
    private void takeOverShards(int targetShardType) {
        try {
            // 查找目标分片类型的所有活跃号段
            List<IdSegment> targetSegments = idSegmentRepository.findByShardType(targetShardType);
            
            for (IdSegment segment : targetSegments) {
                String segmentKey = buildSegmentKey(segment.getBusinessType(), segment.getTimeKey());
                String proxyKey = segmentKey + "_proxy_" + targetShardType;
                
                // 检查是否已经代理
                if (!segmentBuffers.containsKey(proxyKey)) {
                    // 创建代理缓冲区
                    long startValue = calculateIntervalStartValue(segment.getMaxValue(), segment.getStepSize(), targetShardType);
                    SegmentBuffer proxyBuffer = new SegmentBuffer(startValue, segment.getMaxValue(), targetShardType);
                    
                    segmentBuffers.put(proxyKey, proxyBuffer);
                    log.info("接管分片: {}, 类型: {}, 区间: [{}, {}]", 
                            segmentKey, targetShardType, startValue, segment.getMaxValue());
                }
            }
        } catch (Exception e) {
            log.error("接管分片失败, 目标类型: {}", targetShardType, e);
        }
    }
    
    /**
     * 简单的放弃代理分片策略
     * 直接清理所有代理分片，不进行ID回收，允许ID浪费
     * 恢复的节点将从数据库获取新的增长segment
     */
    private void simpleAbandonProxyShards() {
        try {
            List<String> proxyKeys = segmentBuffers.keySet().stream()
                    .filter(key -> key.contains("_proxy_"))
                    .collect(java.util.stream.Collectors.toList());
            
            int abandonedCount = 0;
            long totalAbandonedIds = 0;
            
            for (String proxyKey : proxyKeys) {
                SegmentBuffer proxyBuffer = segmentBuffers.get(proxyKey);
                if (proxyBuffer != null) {
                    // 计算被放弃的ID数量（仅用于统计）
                    long abandonedIds = proxyBuffer.getMaxValue() - proxyBuffer.getCurrentValue();
                    totalAbandonedIds += abandonedIds;
                    
                    log.info("放弃代理分片: {}, 放弃ID数量: {}, 范围: [{}, {})", 
                            proxyKey, abandonedIds, proxyBuffer.getCurrentValue(), proxyBuffer.getMaxValue());
                }
                
                // 直接移除，不进行任何同步操作
                segmentBuffers.remove(proxyKey);
                abandonedCount++;
            }
            
            if (abandonedCount > 0) {
                log.info("服务器恢复完成，放弃了 {} 个代理分片，总计放弃ID数量: {}", 
                        abandonedCount, totalAbandonedIds);
                log.info("恢复的节点将从数据库获取新的增长segment，确保ID生成的连续性");
            }
        } catch (Exception e) {
            log.error("放弃代理分片失败", e);
        }
    }
    
    /**
     * 清理代理分片（保留原有复杂方法）
     * 当对方服务器恢复后调用
     * @deprecated 推荐使用 simpleAbandonProxyShards() 方法
     */
    @Deprecated
    private void cleanupProxyShards() {
        try {
            List<String> proxyKeys = segmentBuffers.keySet().stream()
                    .filter(key -> key.contains("_proxy_"))
                    .collect(java.util.stream.Collectors.toList());
            
            for (String proxyKey : proxyKeys) {
                segmentBuffers.remove(proxyKey);
                log.info("清理代理分片: {}", proxyKey);
            }
            
            if (!proxyKeys.isEmpty()) {
                log.info("服务器恢复，清理了 {} 个代理分片", proxyKeys.size());
            }
        } catch (Exception e) {
            log.error("清理代理分片失败", e);
        }
    }
    
    /**
     * 冲突解决机制
     * 服务器恢复后解决可能的ID冲突
     */
    public Map<String, Object> resolveConflictsAfterRecovery() {
        Map<String, Object> result = new HashMap<>();
        List<String> resolvedSegments = new ArrayList<>();
        int conflictCount = 0;
        
        try {
            // 查找所有业务类型和时间键的组合
            List<String> businessTypes = idSegmentRepository.findAllDistinctBusinessTypes();
            
            for (String businessType : businessTypes) {
                List<IdSegment> segments = idSegmentRepository.findByBusinessType(businessType);
                
                // 按时间键分组
                Map<String, List<IdSegment>> segmentsByTimeKey = segments.stream()
                        .collect(java.util.stream.Collectors.groupingBy(IdSegment::getTimeKey));
                
                for (Map.Entry<String, List<IdSegment>> entry : segmentsByTimeKey.entrySet()) {
                    String timeKey = entry.getKey();
                    List<IdSegment> timeKeySegments = entry.getValue();
                    
                    // 检查是否存在冲突
                    if (timeKeySegments.size() > 1) {
                        boolean resolved = resolveSegmentConflict(businessType, timeKey, timeKeySegments);
                        if (resolved) {
                            conflictCount++;
                            resolvedSegments.add(businessType + ":" + timeKey);
                        }
                    }
                }
            }
            
            result.put("success", true);
            result.put("conflictCount", conflictCount);
            result.put("resolvedSegments", resolvedSegments);
            result.put("message", String.format("冲突解决完成，处理了 %d 个冲突", conflictCount));
            
        } catch (Exception e) {
            log.error("冲突解决失败", e);
            result.put("success", false);
            result.put("message", "冲突解决失败: " + e.getMessage());
        }
        
        result.put("timestamp", System.currentTimeMillis());
        return result;
    }
    
    /**
     * 解决单个号段的冲突
     */
    private boolean resolveSegmentConflict(String businessType, String timeKey, List<IdSegment> segments) {
        try {
            // 找到最大的maxValue作为新的起点
            long maxValue = segments.stream()
                    .mapToLong(IdSegment::getMaxValue)
                    .max()
                    .orElse(0);
            
            // 统一步长（使用最大的步长）
            int maxStepSize = segments.stream()
                    .mapToInt(IdSegment::getStepSize)
                    .max()
                    .orElse(defaultStepSize);
            
            // 更新所有相关号段
            for (IdSegment segment : segments) {
                if (segment.getMaxValue() < maxValue || segment.getStepSize() != maxStepSize) {
                    segment.setMaxValue(maxValue);
                    segment.setStepSize(maxStepSize);
                    idSegmentRepository.save(segment);
                    
                    // 清理对应的缓存
                    String segmentKey = buildSegmentKey(businessType, timeKey);
                    segmentBuffers.remove(segmentKey);
                    
                    log.info("解决冲突: {} 分片类型: {}, 新maxValue: {}, 新stepSize: {}", 
                            segmentKey, segment.getShardType(), maxValue, maxStepSize);
                }
            }
            
            return true;
        } catch (Exception e) {
            log.error("解决号段冲突失败: {}:{}", businessType, timeKey, e);
            return false;
        }
    }

    /**
     * 获取或创建号段
     * 奇偶区间错开模式：每个分片类型独立管理自己的区间序列
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
        
        // 奇偶区间错开模式：计算初始最大值
        long initialMaxValue = calculateInitialMaxValue(stepSize, shardType);
        
        IdSegment newSegment = IdSegment.builder()
                .businessType(businessType)
                .timeKey(timeKey)
                .shardType(shardType)
                .maxValue(initialMaxValue)
                .stepSize(stepSize)
                .build();
        
        return idSegmentRepository.save(newSegment);
    }
    
    /**
     * 计算初始最大值
     * 奇偶区间错开规则：
     * - 奇数服务器：第一个区间 [1, stepSize]，初始maxValue = stepSize
     * - 偶数服务器：第一个区间 [stepSize+1, 2*stepSize]，初始maxValue = 2*stepSize
     * 
     * @param stepSize 步长
     * @param shardType 分片类型 (0-偶数, 1-奇数)
     * @return 初始最大值
     */
    private long calculateInitialMaxValue(int stepSize, int shardType) {
        if (shardType == 1) {
            // 奇数服务器：第一个区间 [1, stepSize]
            return stepSize;
        } else {
            // 偶数服务器：第一个区间 [stepSize+1, 2*stepSize]
            return 2L * stepSize;
        }
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
     * 增强版：支持超时机制和更完善的异常处理
     */
    private void refreshSegmentAsync(String segmentKey, String businessType, 
                                     String timeKey, IdRequest request) {
        SegmentBuffer buffer = segmentBuffers.get(segmentKey);
        if (buffer == null) {
            return;
        }
        
        // 记录刷新开始时间，用于超时检测
        long startTime = System.currentTimeMillis();
        
        try {
            // 设置刷新超时时间（5秒）
            long timeoutMs = 5000;
            
            boolean success = refreshSegmentFromDBWithTimeout(buffer, businessType, timeKey, request, timeoutMs);
            
            if (!success) {
                // 刷新失败，重置标志位以便后续重试
                buffer.resetNeedRefresh();
                log.warn("异步刷新号段失败，已重置刷新标志位: {}, 耗时: {}ms", 
                        segmentKey, System.currentTimeMillis() - startTime);
            } else {
                log.debug("异步刷新号段成功: {}, 耗时: {}ms", 
                        segmentKey, System.currentTimeMillis() - startTime);
            }
            // 注意：成功的情况下，refreshSegmentFromDB内部的updateRange方法会自动重置标志位
            
        } catch (Exception e) {
            // 异常情况下也要重置标志位
            buffer.resetNeedRefresh();
            long duration = System.currentTimeMillis() - startTime;
            log.error("异步刷新号段异常，已重置刷新标志位: {}, 耗时: {}ms, 错误: {}", 
                    segmentKey, duration, e.getMessage(), e);
        }
    }
    
    /**
     * 带超时机制的数据库刷新方法
     */
    private boolean refreshSegmentFromDBWithTimeout(SegmentBuffer buffer, String businessType, 
                                                    String timeKey, IdRequest request, long timeoutMs) {
        long startTime = System.currentTimeMillis();
        
        try {
            // 检查是否已经超时
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                log.warn("刷新号段超时: {}, 超时时间: {}ms", buildSegmentKey(businessType, timeKey), timeoutMs);
                return false;
            }
            
            // 调用原有的刷新方法
            return refreshSegmentFromDB(buffer, businessType, timeKey, request);
            
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("刷新号段异常: {}, 耗时: {}ms", buildSegmentKey(businessType, timeKey), duration, e);
            return false;
        }
    }

    /**
     * 从数据库刷新号段
     * 奇偶区间错开模式：每次刷新时跳跃到下一个属于当前分片类型的区间
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
                    // 步长发生变化，需要重新计算下一个区间
                    long nextMaxValue = calculateNextIntervalMaxValue(businessType, timeKey,
                            segment.getStepSize(), newStepSize, shardType);
                    updateCount = idSegmentRepository.updateMaxValueAndStepSizeAtomicallyWithValue(
                            businessType, timeKey, shardType, nextMaxValue, newStepSize);
                    log.info("检测到步长变更: {} 从 {} 变更为 {}, 新区间最大值: {}", 
                            buildSegmentKey(businessType, timeKey), segment.getStepSize(), newStepSize, nextMaxValue);
                } else {
                    // 步长未变化，计算下一个区间
                    long nextMaxValue = calculateNextIntervalMaxValue(businessType, timeKey,
                            newStepSize, newStepSize, shardType);
                    updateCount = idSegmentRepository.updateMaxValueAtomicallyWithValue(
                            businessType, timeKey, shardType, nextMaxValue);
                }
            } else {
                // 号段不存在，使用初始值
                long initialMaxValue = calculateInitialMaxValue(newStepSize, shardType);
                updateCount = idSegmentRepository.updateMaxValueAtomicallyWithValue(
                        businessType, timeKey, shardType, initialMaxValue);
            }
            
            if (updateCount > 0) {
                // 获取更新后的最大值
                Optional<Long> newMaxValue = idSegmentRepository
                        .getCurrentMaxValue(businessType, timeKey, shardType);
                
                if (newMaxValue.isPresent()) {
                    // 重新计算缓冲区的起始值
                    long newStartValue = calculateIntervalStartValue(newMaxValue.get(), newStepSize, shardType);
                    buffer.updateRange(newStartValue, newMaxValue.get());
                    
                    if (stepSizeChanged) {
                        log.info("号段刷新成功（含步长变更）: {} -> [{}, {}], 新步长: {}", 
                                buildSegmentKey(businessType, timeKey), newStartValue, newMaxValue.get(), newStepSize);
                    } else {
                        log.debug("号段刷新成功: {} -> [{}, {}]", 
                                buildSegmentKey(businessType, timeKey), newStartValue, newMaxValue.get());
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
     * 计算下一个区间的最大值
     * 奇偶区间错开模式：基于全局最大值计算下一个属于当前分片类型的区间
     * 
     * @param businessType 业务类型
     * @param timeKey 时间键
     * @param currentStepSize 当前步长
     * @param newStepSize 新步长
     * @param shardType 分片类型
     * @return 下一个区间的最大值
     */
    private long calculateNextIntervalMaxValue(String businessType, String timeKey,
                                               int currentStepSize, int newStepSize, int shardType) {
        // 获取全局最大值（两个分片类型中的最大值）
        long globalMaxValue = getGlobalMaxValue(businessType, timeKey, newStepSize);
        
        // 基于全局最大值计算当前全局区间索引
        long globalIntervalIndex = (globalMaxValue - 1) / newStepSize;
        
        // 找到下一个属于当前分片类型的区间索引
        long nextIntervalIndex = findNextAvailableIntervalIndex(globalIntervalIndex, shardType);
        
        return (nextIntervalIndex + 1) * newStepSize;
    }
    
    /**
     * 获取全局最大值（考虑两个分片类型）
     */
    private long getGlobalMaxValue(String businessType, String timeKey, int stepSize) {
        // 查询两个分片类型的最大值
        Optional<Long> evenMaxValue = idSegmentRepository.getCurrentMaxValue(businessType, timeKey, 0);
        Optional<Long> oddMaxValue = idSegmentRepository.getCurrentMaxValue(businessType, timeKey, 1);
        
        long maxEven = evenMaxValue.orElse(0L);
        long maxOdd = oddMaxValue.orElse(0L);
        
        // 返回全局最大值，如果都为0则返回初始步长
        long globalMax = Math.max(maxEven, maxOdd);
        return globalMax == 0 ? stepSize : globalMax;
    }
    
    /**
     * 找到下一个可用的区间索引
     */
    private long findNextAvailableIntervalIndex(long currentGlobalIndex, int shardType) {
        // 从当前全局索引开始，找到下一个属于指定分片类型的区间
        long candidateIndex = currentGlobalIndex + 1;
        
        while (true) {
            if (shardType == 1) {
                // 奇数服务器：使用偶数索引区间 (0, 2, 4, 6, ...)
                if (candidateIndex % 2 == 0) {
                    return candidateIndex;
                }
            } else {
                // 偶数服务器：使用奇数索引区间 (1, 3, 5, 7, ...)
                if (candidateIndex % 2 == 1) {
                    return candidateIndex;
                }
            }
            candidateIndex++;
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
     * 定时处理服务器故障转移
     * 每30秒检查一次服务器状态
     */
    @Scheduled(fixedDelay = 30000)
    public void scheduledFailoverCheck() {
        try {
            handleServerFailover();
        } catch (Exception e) {
            log.error("定时故障转移检查失败", e);
        }
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
        status.put("isInFailoverMode", isInFailoverMode());
        
        // 统计代理分片数量
        long proxyShardCount = segmentBuffers.keySet().stream()
                .filter(key -> key.contains("_proxy_"))
                .count();
        status.put("proxyShardCount", proxyShardCount);
        
        // 添加刷新状态监控
        Map<String, Object> refreshStatus = getRefreshStatusSummary();
        status.put("refreshStatus", refreshStatus);
        
        // 添加负载均衡信息
        Map<String, Object> loadBalanceInfo = getLoadBalanceInfo();
        status.put("loadBalance", loadBalanceInfo);
        
        return status;
    }
    
    /**
     * 获取负载均衡信息
     */
    private Map<String, Object> getLoadBalanceInfo() {
        Map<String, Object> info = new HashMap<>();
        
        try {
            long evenServerLoad = getServerTypeLoad(0);
            long oddServerLoad = getServerTypeLoad(1);
            
            info.put("evenServerLoad", evenServerLoad);
            info.put("oddServerLoad", oddServerLoad);
            info.put("totalLoad", evenServerLoad + oddServerLoad);
            
            if (evenServerLoad + oddServerLoad > 0) {
                double evenLoadRatio = (double) evenServerLoad / (evenServerLoad + oddServerLoad);
                double oddLoadRatio = (double) oddServerLoad / (evenServerLoad + oddServerLoad);
                info.put("evenLoadRatio", Math.round(evenLoadRatio * 100.0) / 100.0);
                info.put("oddLoadRatio", Math.round(oddLoadRatio * 100.0) / 100.0);
                info.put("isBalanced", Math.abs(evenLoadRatio - oddLoadRatio) < 0.2); // 20%以内认为是均衡的
            } else {
                info.put("evenLoadRatio", 0.0);
                info.put("oddLoadRatio", 0.0);
                info.put("isBalanced", true);
            }
        } catch (Exception e) {
            log.warn("获取负载均衡信息失败", e);
            info.put("error", e.getMessage());
        }
        
        return info;
    }
    
    /**
     * 获取刷新状态摘要
     * 用于监控是否有卡住的刷新操作
     */
    public Map<String, Object> getRefreshStatusSummary() {
        Map<String, Object> summary = new HashMap<>();
        int totalBuffers = 0;
        int refreshingBuffers = 0;
        int timeoutBuffers = 0;
        List<Map<String, Object>> timeoutDetails = new ArrayList<>();
        
        for (Map.Entry<String, SegmentBuffer> entry : segmentBuffers.entrySet()) {
            totalBuffers++;
            SegmentBuffer buffer = entry.getValue();
            Map<String, Object> bufferStatus = buffer.getRefreshStatus();
            
            if ((Boolean) bufferStatus.get("needRefresh")) {
                refreshingBuffers++;
                
                if ((Boolean) bufferStatus.get("isTimeout")) {
                    timeoutBuffers++;
                    Map<String, Object> timeoutInfo = new HashMap<>();
                    timeoutInfo.put("segmentKey", entry.getKey());
                    timeoutInfo.put("timeSinceLastAttempt", bufferStatus.get("timeSinceLastAttempt"));
                    timeoutDetails.add(timeoutInfo);
                }
            }
        }
        
        summary.put("totalBuffers", totalBuffers);
        summary.put("refreshingBuffers", refreshingBuffers);
        summary.put("timeoutBuffers", timeoutBuffers);
        summary.put("timeoutDetails", timeoutDetails);
        summary.put("hasTimeoutIssues", timeoutBuffers > 0);
        
        return summary;
    }
    
    /**
     * 手动恢复超时的刷新操作
     * 用于运维场景，可以手动清理卡住的刷新状态
     */
    public Map<String, Object> recoverTimeoutRefresh() {
        Map<String, Object> result = new HashMap<>();
        int recoveredCount = 0;
        List<String> recoveredSegments = new ArrayList<>();
        
        for (Map.Entry<String, SegmentBuffer> entry : segmentBuffers.entrySet()) {
            SegmentBuffer buffer = entry.getValue();
            Map<String, Object> bufferStatus = buffer.getRefreshStatus();
            
            if ((Boolean) bufferStatus.get("needRefresh") && (Boolean) bufferStatus.get("isTimeout")) {
                buffer.resetNeedRefresh();
                recoveredCount++;
                recoveredSegments.add(entry.getKey());
                log.info("手动恢复超时的刷新操作: {}", entry.getKey());
            }
        }
        
        result.put("recoveredCount", recoveredCount);
        result.put("recoveredSegments", recoveredSegments);
        result.put("timestamp", System.currentTimeMillis());
        
        return result;
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
    
    // ==================== 简单放弃策略管理API ====================
    
    /**
     * 手动触发简单放弃策略
     * 用于运维场景，强制清理代理分片
     */
    public Map<String, Object> triggerSimpleAbandonStrategy() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            int proxyShardCount = getProxyShardCount();
            
            if (proxyShardCount == 0) {
                result.put("success", true);
                result.put("message", "当前没有代理分片需要清理");
                result.put("abandonedCount", 0);
                result.put("timestamp", System.currentTimeMillis());
                return result;
            }
            
            // 执行简单放弃策略
            simpleAbandonProxyShards();
            ensureRecoveredNodeGetsNewSegment();
            
            result.put("success", true);
            result.put("message", "简单放弃策略执行完成");
            result.put("abandonedCount", proxyShardCount);
            result.put("timestamp", System.currentTimeMillis());
            
            log.info("手动触发简单放弃策略完成，清理了 {} 个代理分片", proxyShardCount);
            
        } catch (Exception e) {
            log.error("执行简单放弃策略失败", e);
            result.put("success", false);
            result.put("message", "执行失败: " + e.getMessage());
            result.put("timestamp", System.currentTimeMillis());
        }
        
        return result;
    }
    
    /**
     * 获取放弃策略状态信息
     */
    public Map<String, Object> getAbandonStrategyStatus() {
        Map<String, Object> status = new HashMap<>();
        
        try {
            // 统计代理分片信息
            List<Map<String, Object>> proxyShardDetails = new ArrayList<>();
            long totalAbandonableIds = 0;
            
            for (String key : segmentBuffers.keySet()) {
                if (key.contains("_proxy_")) {
                    SegmentBuffer buffer = segmentBuffers.get(key);
                    if (buffer != null) {
                        Map<String, Object> detail = new HashMap<>();
                        detail.put("key", key);
                        detail.put("shardType", buffer.getShardType());
                        detail.put("currentValue", buffer.getCurrentValue());
                        detail.put("maxValue", buffer.getMaxValue());
                        
                        long abandonableIds = buffer.getMaxValue() - buffer.getCurrentValue();
                        detail.put("abandonableIds", abandonableIds);
                        totalAbandonableIds += abandonableIds;
                        
                        proxyShardDetails.add(detail);
                    }
                }
            }
            
            status.put("proxyShardCount", proxyShardDetails.size());
            status.put("proxyShardDetails", proxyShardDetails);
            status.put("totalAbandonableIds", totalAbandonableIds);
            status.put("isInFailoverMode", isInFailoverMode());
            status.put("canUseAbandonStrategy", proxyShardDetails.size() > 0);
            status.put("timestamp", System.currentTimeMillis());
            
        } catch (Exception e) {
            log.error("获取放弃策略状态失败", e);
            status.put("error", e.getMessage());
            status.put("timestamp", System.currentTimeMillis());
        }
        
        return status;
    }
    
    /**
     * 强制清理恢复节点的segment缓存
     * 用于确保恢复的节点获取新的增长segment
     */
    public Map<String, Object> forceCleanRecoveredNodeCache() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            int originalCacheCount = segmentBuffers.size();
            
            // 执行清理
            ensureRecoveredNodeGetsNewSegment();
            
            int newCacheCount = segmentBuffers.size();
            int cleanedCount = originalCacheCount - newCacheCount;
            
            result.put("success", true);
            result.put("message", "恢复节点缓存清理完成");
            result.put("cleanedCount", cleanedCount);
            result.put("remainingCacheCount", newCacheCount);
            result.put("timestamp", System.currentTimeMillis());
            
            log.info("手动清理恢复节点缓存完成，清理了 {} 个segment缓存", cleanedCount);
            
        } catch (Exception e) {
            log.error("清理恢复节点缓存失败", e);
            result.put("success", false);
            result.put("message", "清理失败: " + e.getMessage());
            result.put("timestamp", System.currentTimeMillis());
        }
        
        return result;
    }
}