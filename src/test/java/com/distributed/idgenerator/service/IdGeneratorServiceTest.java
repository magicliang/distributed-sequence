package com.distributed.idgenerator.service;

import com.distributed.idgenerator.dto.IdRequest;
import com.distributed.idgenerator.dto.IdResponse;
import com.distributed.idgenerator.entity.IdSegment;
import com.distributed.idgenerator.entity.ServerRegistry;
import com.distributed.idgenerator.repository.IdSegmentRepository;
import com.distributed.idgenerator.repository.ServerRegistryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ID生成器服务测试
 * 
 * @author System
 * @version 1.0.0
 */
@SpringBootTest
@ActiveProfiles("h2")
@Transactional
class IdGeneratorServiceTest {

    @Autowired
    private IdGeneratorService idGeneratorService;

    @Autowired
    private IdSegmentRepository idSegmentRepository;

    @Autowired
    private ServerRegistryRepository serverRegistryRepository;

    private String testBusinessType = "test_order";
    private String testTimeKey = LocalDate.now().toString().replace("-", "");

    @BeforeEach
    void setUp() {
        // 清理测试数据
        idSegmentRepository.deleteAll();
        serverRegistryRepository.deleteAll();
    }

    @Test
    void testGenerateSingleId() {
        // 测试生成单个ID
        IdRequest request = IdRequest.builder()
                .businessType(testBusinessType)
                .timeKey(testTimeKey)
                .count(1)
                .build();

        IdResponse response = idGeneratorService.generateIds(request);

        assertNotNull(response);
        assertNotNull(response.getIds());
        assertEquals(1, response.getIds().size());
        assertEquals(testBusinessType, response.getBusinessType());
        assertEquals(testTimeKey, response.getTimeKey());
        assertNotNull(response.getServerId());
        assertTrue(response.getTimestamp() > 0);

        Long generatedId = response.getFirstId();
        assertNotNull(generatedId);
        assertTrue(generatedId > 0);
        
        System.out.println("生成的ID: " + generatedId);
        System.out.println("分片类型: " + response.getShardTypeDesc());
    }

    @Test
    void testGenerateMultipleIds() {
        // 测试生成多个ID
        int count = 10;
        IdRequest request = IdRequest.builder()
                .businessType(testBusinessType)
                .timeKey(testTimeKey)
                .count(count)
                .build();

        IdResponse response = idGeneratorService.generateIds(request);

        assertNotNull(response);
        assertEquals(count, response.getIdCount());
        
        // 验证ID的唯一性
        Set<Long> uniqueIds = new HashSet<>(response.getIds());
        assertEquals(count, uniqueIds.size());
        
        // 验证ID的递增性
        List<Long> ids = response.getIds();
        for (int i = 1; i < ids.size(); i++) {
            assertTrue(ids.get(i) > ids.get(i - 1));
        }
        
        System.out.println("生成的ID列表: " + ids);
    }

    @Test
    void testOddEvenSharding() {
        // 测试奇偶分片
        IdRequest oddRequest = IdRequest.builder()
                .businessType(testBusinessType)
                .timeKey(testTimeKey)
                .count(5)
                .forceShardType(1) // 强制奇数分片
                .build();

        IdRequest evenRequest = IdRequest.builder()
                .businessType(testBusinessType)
                .timeKey(testTimeKey)
                .count(5)
                .forceShardType(0) // 强制偶数分片
                .build();

        IdResponse oddResponse = idGeneratorService.generateIds(oddRequest);
        IdResponse evenResponse = idGeneratorService.generateIds(evenRequest);

        // 验证奇数分片
        assertEquals(1, oddResponse.getShardType().intValue());
        for (Long id : oddResponse.getIds()) {
            assertEquals(1, id % 2, "ID应该是奇数: " + id);
        }

        // 验证偶数分片
        assertEquals(0, evenResponse.getShardType().intValue());
        for (Long id : evenResponse.getIds()) {
            assertEquals(0, id % 2, "ID应该是偶数: " + id);
        }

        System.out.println("奇数分片ID: " + oddResponse.getIds());
        System.out.println("偶数分片ID: " + evenResponse.getIds());
    }

    @Test
    void testConcurrentIdGeneration() throws InterruptedException, ExecutionException {
        // 测试并发ID生成
        int threadCount = 10;
        int idsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        
        List<Future<List<Long>>> futures = new ArrayList<>();
        
        for (int i = 0; i < threadCount; i++) {
            final int threadIndex = i;
            Future<List<Long>> future = executor.submit(() -> {
                List<Long> threadIds = new ArrayList<>();
                for (int j = 0; j < idsPerThread; j++) {
                    IdRequest request = IdRequest.builder()
                            .businessType(testBusinessType + "_thread_" + threadIndex)
                            .timeKey(testTimeKey)
                            .count(1)
                            .build();
                    
                    IdResponse response = idGeneratorService.generateIds(request);
                    threadIds.add(response.getFirstId());
                }
                return threadIds;
            });
            futures.add(future);
        }
        
        // 收集所有生成的ID
        Set<Long> allIds = new HashSet<>();
        for (Future<List<Long>> future : futures) {
            List<Long> threadIds = future.get();
            allIds.addAll(threadIds);
        }
        
        executor.shutdown();
        
        // 验证ID的唯一性
        assertEquals(threadCount * idsPerThread, allIds.size(), "所有ID应该是唯一的");
        
        System.out.println("并发生成ID总数: " + allIds.size());
        System.out.println("ID范围: " + Collections.min(allIds) + " - " + Collections.max(allIds));
    }

    @Test
    void testRoutingInfo() {
        // 测试路由信息
        IdRequest request = IdRequest.builder()
                .businessType(testBusinessType)
                .timeKey(testTimeKey)
                .count(1)
                .includeRouting(true)
                .shardDbCount(4)
                .shardTableCount(8)
                .build();

        IdResponse response = idGeneratorService.generateIds(request);

        assertNotNull(response);
        assertTrue(response.hasRoutingInfo());
        
        IdResponse.RoutingInfo routing = response.getRoutingInfo();
        assertNotNull(routing);
        assertTrue(routing.getDbIndex() >= 0 && routing.getDbIndex() < 4);
        assertTrue(routing.getTableIndex() >= 0 && routing.getTableIndex() < 8);
        assertEquals(4, routing.getShardDbCount().intValue());
        assertEquals(8, routing.getShardTableCount().intValue());
        
        System.out.println("路由信息 - DB索引: " + routing.getDbIndex() + 
                          ", 表索引: " + routing.getTableIndex() + 
                          ", 路由键: " + routing.getRoutingKey());
    }

    @Test
    void testDifferentBusinessTypes() {
        // 测试不同业务类型
        String[] businessTypes = {"order", "user", "product", "payment"};
        Map<String, List<Long>> businessIds = new HashMap<>();
        
        for (String businessType : businessTypes) {
            IdRequest request = IdRequest.builder()
                    .businessType(businessType)
                    .timeKey(testTimeKey)
                    .count(5)
                    .build();
            
            IdResponse response = idGeneratorService.generateIds(request);
            businessIds.put(businessType, response.getIds());
        }
        
        // 验证不同业务类型的ID互不冲突
        Set<Long> allIds = new HashSet<>();
        for (List<Long> ids : businessIds.values()) {
            for (Long id : ids) {
                assertTrue(allIds.add(id), "不同业务类型的ID应该唯一: " + id);
            }
        }
        
        System.out.println("不同业务类型的ID:");
        businessIds.forEach((type, ids) -> 
            System.out.println(type + ": " + ids));
    }

    @Test
    void testDifferentTimeKeys() {
        // 测试不同时间键
        String[] timeKeys = {"20231201", "20231202", "20231203"};
        Map<String, List<Long>> timeKeyIds = new HashMap<>();
        
        for (String timeKey : timeKeys) {
            IdRequest request = IdRequest.builder()
                    .businessType(testBusinessType)
                    .timeKey(timeKey)
                    .count(5)
                    .build();
            
            IdResponse response = idGeneratorService.generateIds(request);
            timeKeyIds.put(timeKey, response.getIds());
        }
        
        System.out.println("不同时间键的ID:");
        timeKeyIds.forEach((timeKey, ids) -> 
            System.out.println(timeKey + ": " + ids));
    }

    @Test
    void testCustomStepSize() {
        // 测试自定义步长
        int customStepSize = 500;
        IdRequest request = IdRequest.builder()
                .businessType(testBusinessType)
                .timeKey(testTimeKey)
                .count(1)
                .customStepSize(customStepSize)
                .build();

        IdResponse response = idGeneratorService.generateIds(request);
        assertNotNull(response);
        
        // 验证数据库中的步长设置
        Optional<IdSegment> segment = idSegmentRepository
                .findByBusinessTypeAndTimeKeyAndShardType(
                    testBusinessType, testTimeKey, response.getShardType());
        
        assertTrue(segment.isPresent());
        assertEquals(customStepSize, segment.get().getStepSize().intValue());
        
        System.out.println("自定义步长测试 - 步长: " + customStepSize + 
                          ", 生成ID: " + response.getFirstId());
    }

    @Test
    void testServerStatus() {
        // 测试服务器状态
        Map<String, Object> status = idGeneratorService.getServerStatus();
        
        assertNotNull(status);
        assertTrue(status.containsKey("serverId"));
        assertTrue(status.containsKey("serverType"));
        assertTrue(status.containsKey("serverTypeDesc"));
        assertTrue(status.containsKey("segmentBufferCount"));
        assertTrue(status.containsKey("timestamp"));
        
        System.out.println("服务器状态: " + status);
    }

    @Test
    void testSegmentRefresh() {
        // 测试号段刷新机制
        IdRequest request = IdRequest.builder()
                .businessType(testBusinessType)
                .timeKey(testTimeKey)
                .count(1500) // 超过默认步长，触发号段刷新
                .build();

        IdResponse response = idGeneratorService.generateIds(request);
        
        assertNotNull(response);
        assertEquals(1500, response.getIdCount());
        
        // 验证ID的连续性和唯一性
        List<Long> ids = response.getIds();
        Set<Long> uniqueIds = new HashSet<>(ids);
        assertEquals(ids.size(), uniqueIds.size(), "所有ID应该唯一");
        
        System.out.println("号段刷新测试 - 生成ID数量: " + ids.size() + 
                          ", ID范围: " + Collections.min(ids) + " - " + Collections.max(ids));
    }

    @Test
    void testCleanExpiredSegments() {
        // 测试清理过期号段
        // 先创建一些测试数据
        String expiredTimeKey = "20231101";
        String currentTimeKey = "20231201";
        
        // 创建过期号段
        IdRequest expiredRequest = IdRequest.builder()
                .businessType(testBusinessType)
                .timeKey(expiredTimeKey)
                .count(1)
                .build();
        idGeneratorService.generateIds(expiredRequest);
        
        // 创建当前号段
        IdRequest currentRequest = IdRequest.builder()
                .businessType(testBusinessType)
                .timeKey(currentTimeKey)
                .count(1)
                .build();
        idGeneratorService.generateIds(currentRequest);
        
        // 验证号段存在
        assertEquals(2, idSegmentRepository.count());
        
        // 清理过期号段
        int deletedCount = idGeneratorService.cleanExpiredSegments("20231130");
        
        // 验证清理结果
        assertTrue(deletedCount > 0);
        assertTrue(idSegmentRepository.count() < 2);
        
        System.out.println("清理过期号段 - 删除数量: " + deletedCount);
    }

    @Test
    void testIdDistribution() {
        // 测试ID分布情况
        int totalCount = 1000;
        IdRequest request = IdRequest.builder()
                .businessType(testBusinessType)
                .timeKey(testTimeKey)
                .count(totalCount)
                .build();

        IdResponse response = idGeneratorService.generateIds(request);
        List<Long> ids = response.getIds();
        
        // 统计奇偶分布
        long oddCount = ids.stream().filter(id -> id % 2 == 1).count();
        long evenCount = ids.stream().filter(id -> id % 2 == 0).count();
        
        System.out.println("ID分布统计:");
        System.out.println("总数: " + totalCount);
        System.out.println("奇数: " + oddCount);
        System.out.println("偶数: " + evenCount);
        System.out.println("分片类型: " + response.getShardTypeDesc());
        
        // 根据分片类型验证分布
        if (response.getShardType() == 1) {
            assertEquals(totalCount, oddCount, "奇数分片应该只生成奇数ID");
            assertEquals(0, evenCount, "奇数分片不应该生成偶数ID");
        } else {
            assertEquals(totalCount, evenCount, "偶数分片应该只生成偶数ID");
            assertEquals(0, oddCount, "偶数分片不应该生成奇数ID");
        }
    }

    /**
     * 测试容错机制 - 对方服务器下线时的分片选择
     */
    @Test
    void testFailoverMechanism() {
        // 模拟当前服务器为偶数服务器（serverType = 0）
        // 清空所有奇数服务器（serverType = 1），模拟对方服务器下线
        serverRegistryRepository.deleteAll();
        
        // 只注册当前偶数服务器
        ServerRegistry currentServer = ServerRegistry.builder()
                .serverId("test-server-0")
                .serverType(0)
                .status(1)
                .build();
        serverRegistryRepository.save(currentServer);
        
        // 创建多个不同的请求，测试分片分配
        Map<String, Integer> businessShardMapping = new HashMap<>();
        
        for (int i = 0; i < 10; i++) {
            String businessType = "test_business_" + i;
            String timeKey = "20231101";
            
            IdRequest request = IdRequest.builder()
                    .businessType(businessType)
                    .timeKey(timeKey)
                    .count(1)
                    .build();
            
            IdResponse response = idGeneratorService.generateIds(request);
            assertNotNull(response);
            assertTrue(response.isSuccess());
            
            // 记录每个业务类型被分配到的分片
            businessShardMapping.put(businessType, response.getShardType());
            
            System.out.println(String.format("业务类型: %s, 分配分片: %d (%s)", 
                    businessType, response.getShardType(), response.getShardTypeDesc()));
        }
        
        // 验证分片分配的合理性
        long oddShardCount = businessShardMapping.values().stream()
                .filter(shardType -> shardType == 1).count();
        long evenShardCount = businessShardMapping.values().stream()
                .filter(shardType -> shardType == 0).count();
        
        System.out.println("容错模式下分片分配统计:");
        System.out.println("奇数分片业务数: " + oddShardCount);
        System.out.println("偶数分片业务数: " + evenShardCount);
        
        // 验证两种分片都有分配（负载均衡）
        assertTrue(oddShardCount > 0, "容错模式下应该有业务分配到奇数分片");
        assertTrue(evenShardCount > 0, "容错模式下应该有业务分配到偶数分片");
        
        // 验证同一个业务类型+时间键的组合总是分配到相同分片（一致性）
        for (int i = 0; i < 3; i++) {
            String businessType = "test_business_0"; // 重复测试第一个业务类型
            String timeKey = "20231101";
            
            IdRequest request = IdRequest.builder()
                    .businessType(businessType)
                    .timeKey(timeKey)
                    .count(1)
                    .build();
            
            IdResponse response = idGeneratorService.generateIds(request);
            assertEquals(businessShardMapping.get(businessType), response.getShardType(),
                    "相同业务类型和时间键应该始终分配到相同分片");
        }
    }

    /**
     * 测试强制指定分片类型
     */
    @Test
    void testForceShardType() {
        // 清理并注册服务器
        serverRegistryRepository.deleteAll();
        ServerRegistry server = ServerRegistry.builder()
                .serverId("test-server-0")
                .serverType(0)
                .status(1)
                .build();
        serverRegistryRepository.save(server);
        
        // 测试强制指定奇数分片
        IdRequest oddRequest = IdRequest.builder()
                .businessType("test_force_odd")
                .timeKey("20231101")
                .count(5)
                .forceShardType(1)
                .build();
        
        IdResponse oddResponse = idGeneratorService.generateIds(oddRequest);
        assertNotNull(oddResponse);
        assertTrue(oddResponse.isSuccess());
        assertEquals(1, oddResponse.getShardType());
        
        // 验证生成的ID都是奇数
        for (Long id : oddResponse.getIds()) {
            assertEquals(1, id % 2, "强制奇数分片应该生成奇数ID");
        }
        
        // 测试强制指定偶数分片
        IdRequest evenRequest = IdRequest.builder()
                .businessType("test_force_even")
                .timeKey("20231101")
                .count(5)
                .forceShardType(0)
                .build();
        
        IdResponse evenResponse = idGeneratorService.generateIds(evenRequest);
        assertNotNull(evenResponse);
        assertTrue(evenResponse.isSuccess());
        assertEquals(0, evenResponse.getShardType());
        
        // 验证生成的ID都是偶数
        for (Long id : evenResponse.getIds()) {
            assertEquals(0, id % 2, "强制偶数分片应该生成偶数ID");
        }
    }
}