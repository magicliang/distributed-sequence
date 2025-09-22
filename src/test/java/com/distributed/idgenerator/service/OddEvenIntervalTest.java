package com.distributed.idgenerator.service;

import com.distributed.idgenerator.dto.IdRequest;
import com.distributed.idgenerator.dto.IdResponse;
import com.distributed.idgenerator.entity.IdSegment;
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
 * 奇偶区间错开模式测试
 * 
 * @author System
 * @version 1.0.0
 */
@SpringBootTest
@ActiveProfiles("h2")
@Transactional
class OddEvenIntervalTest {

    @Autowired
    private IdGeneratorService idGeneratorService;

    @Autowired
    private IdSegmentRepository idSegmentRepository;

    @Autowired
    private ServerRegistryRepository serverRegistryRepository;

    private String testBusinessType = "interval_test";
    private String testTimeKey = LocalDate.now().toString().replace("-", "");
    private int defaultStepSize = 1000;

    @BeforeEach
    void setUp() {
        // 清理测试数据
        idSegmentRepository.deleteAll();
        serverRegistryRepository.deleteAll();
    }

    @Test
    void testOddServerIntervalAllocation() {
        // 测试奇数服务器的区间分配
        // 奇数服务器应该使用：[1, 1000], [2001, 3000], [4001, 5000], ...
        
        IdRequest request = IdRequest.builder()
                .businessType(testBusinessType)
                .timeKey(testTimeKey)
                .count(50)
                .forceShardType(1) // 奇数服务器
                .customStepSize(defaultStepSize)
                .build();

        IdResponse response = idGeneratorService.generateIds(request);

        assertNotNull(response);
        assertEquals(50, response.getIdCount());
        assertEquals(1, response.getShardType().intValue());

        List<Long> ids = response.getIds();
        
        // 验证所有ID都在第一个奇数服务器区间内 [1, 1000]
        for (Long id : ids) {
            assertTrue(id >= 1 && id <= defaultStepSize, 
                    String.format("奇数服务器第一个区间的ID %d 应该在 [1, %d] 范围内", id, defaultStepSize));
        }

        System.out.println("奇数服务器第一个区间ID: " + ids);
        System.out.println("ID范围: [" + Collections.min(ids) + ", " + Collections.max(ids) + "]");
    }

    @Test
    void testEvenServerIntervalAllocation() {
        // 测试偶数服务器的区间分配
        // 偶数服务器应该使用：[1001, 2000], [3001, 4000], [5001, 6000], ...
        
        IdRequest request = IdRequest.builder()
                .businessType(testBusinessType)
                .timeKey(testTimeKey)
                .count(50)
                .forceShardType(0) // 偶数服务器
                .customStepSize(defaultStepSize)
                .build();

        IdResponse response = idGeneratorService.generateIds(request);

        assertNotNull(response);
        assertEquals(50, response.getIdCount());
        assertEquals(0, response.getShardType().intValue());

        List<Long> ids = response.getIds();
        
        // 验证所有ID都在第一个偶数服务器区间内 [1001, 2000]
        for (Long id : ids) {
            assertTrue(id >= (defaultStepSize + 1) && id <= (2 * defaultStepSize), 
                    String.format("偶数服务器第一个区间的ID %d 应该在 [%d, %d] 范围内", 
                            id, defaultStepSize + 1, 2 * defaultStepSize));
        }

        System.out.println("偶数服务器第一个区间ID: " + ids);
        System.out.println("ID范围: [" + Collections.min(ids) + ", " + Collections.max(ids) + "]");
    }

    @Test
    void testIntervalTransition() {
        // 测试区间跳跃
        // 当一个区间用完后，应该跳跃到下一个属于该服务器的区间
        
        IdRequest request = IdRequest.builder()
                .businessType(testBusinessType)
                .timeKey(testTimeKey)
                .count(defaultStepSize + 100) // 超过一个区间的容量
                .forceShardType(1) // 奇数服务器
                .customStepSize(defaultStepSize)
                .build();

        IdResponse response = idGeneratorService.generateIds(request);

        assertNotNull(response);
        assertEquals(defaultStepSize + 100, response.getIdCount());

        List<Long> ids = response.getIds();
        
        // 分析ID分布
        Map<Integer, List<Long>> intervalDistribution = new HashMap<>();
        for (Long id : ids) {
            int intervalIndex = (int) ((id - 1) / defaultStepSize);
            intervalDistribution.computeIfAbsent(intervalIndex, k -> new ArrayList<>()).add(id);
        }

        System.out.println("区间分布: " + intervalDistribution.keySet());
        
        // 验证只使用了奇数服务器的区间（偶数索引）
        for (Integer intervalIndex : intervalDistribution.keySet()) {
            assertEquals(0, intervalIndex % 2, 
                    String.format("奇数服务器应该只使用偶数索引区间，发现奇数索引区间: %d", intervalIndex));
        }

        // 验证区间0和区间2都有ID
        assertTrue(intervalDistribution.containsKey(0), "应该包含区间0");
        assertTrue(intervalDistribution.containsKey(2), "应该包含区间2");
        
        System.out.println("区间跳跃验证通过");
    }

    @Test
    void testStepSizeChangeWithIntervals() {
        // 测试步长变更对区间分配的影响
        
        // 先用默认步长生成一些ID
        IdRequest request1 = IdRequest.builder()
                .businessType(testBusinessType)
                .timeKey(testTimeKey)
                .count(10)
                .forceShardType(1) // 奇数服务器
                .customStepSize(defaultStepSize)
                .build();

        IdResponse response1 = idGeneratorService.generateIds(request1);
        List<Long> firstBatch = response1.getIds();

        // 改变步长后再生成ID
        int newStepSize = 500;
        IdRequest request2 = IdRequest.builder()
                .businessType(testBusinessType)
                .timeKey(testTimeKey)
                .count(10)
                .forceShardType(1) // 奇数服务器
                .customStepSize(newStepSize)
                .build();

        IdResponse response2 = idGeneratorService.generateIds(request2);
        List<Long> secondBatch = response2.getIds();

        // 验证两批ID都是有效的
        assertFalse(firstBatch.isEmpty());
        assertFalse(secondBatch.isEmpty());

        // 验证没有重复ID
        Set<Long> allIds = new HashSet<>();
        allIds.addAll(firstBatch);
        allIds.addAll(secondBatch);
        assertEquals(firstBatch.size() + secondBatch.size(), allIds.size(), "不应该有重复ID");

        System.out.println("步长变更前ID: " + firstBatch);
        System.out.println("步长变更后ID: " + secondBatch);
        System.out.println("步长变更测试通过");
    }

    @Test
    void testConcurrentIntervalGeneration() throws InterruptedException {
        // 测试并发环境下的区间分配
        int threadCount = 5;
        int idsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        
        Set<Long> allIds = ConcurrentHashMap.newKeySet();
        List<Exception> exceptions = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            final int threadIndex = i;
            executor.submit(() -> {
                try {
                    IdRequest request = IdRequest.builder()
                            .businessType(testBusinessType + "_concurrent")
                            .timeKey(testTimeKey)
                            .count(idsPerThread)
                            .forceShardType(threadIndex % 2) // 交替使用奇偶服务器
                            .customStepSize(defaultStepSize)
                            .build();

                    IdResponse response = idGeneratorService.generateIds(request);
                    allIds.addAll(response.getIds());
                } catch (Exception e) {
                    exceptions.add(e);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        // 验证没有异常
        assertTrue(exceptions.isEmpty(), "并发测试中出现异常: " + exceptions);
        
        // 验证ID唯一性
        assertEquals(threadCount * idsPerThread, allIds.size(), "ID重复或丢失");
        
        // 分析区间分布
        Map<Integer, Integer> intervalDistribution = new HashMap<>();
        for (Long id : allIds) {
            int intervalIndex = (int) ((id - 1) / defaultStepSize);
            intervalDistribution.merge(intervalIndex, 1, Integer::sum);
        }

        System.out.println("并发生成ID总数: " + allIds.size());
        System.out.println("区间分布: " + intervalDistribution);
        System.out.println("并发区间分配测试通过");
    }

    @Test
    void testIntervalBoundaryValues() {
        // 测试区间边界值
        
        // 生成足够多的ID以触发区间边界
        IdRequest request = IdRequest.builder()
                .businessType(testBusinessType)
                .timeKey(testTimeKey)
                .count(defaultStepSize * 2 + 10) // 跨越多个区间
                .forceShardType(1) // 奇数服务器
                .customStepSize(defaultStepSize)
                .build();

        IdResponse response = idGeneratorService.generateIds(request);

        List<Long> ids = response.getIds();
        
        // 验证ID的连续性和正确性
        Collections.sort(ids);
        
        // 检查区间边界
        Set<Integer> usedIntervals = new HashSet<>();
        for (Long id : ids) {
            int intervalIndex = (int) ((id - 1) / defaultStepSize);
            usedIntervals.add(intervalIndex);
            
            // 验证奇数服务器只使用偶数索引区间
            assertEquals(0, intervalIndex % 2, 
                    String.format("奇数服务器应该只使用偶数索引区间，发现ID %d 在区间 %d", id, intervalIndex));
        }

        System.out.println("使用的区间: " + usedIntervals);
        System.out.println("ID范围: [" + Collections.min(ids) + ", " + Collections.max(ids) + "]");
        System.out.println("区间边界测试通过");
    }
}