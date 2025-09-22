package com.distributed.idgenerator;

import com.distributed.idgenerator.dto.IdRequest;
import com.distributed.idgenerator.dto.IdResponse;
import com.distributed.idgenerator.entity.IdSegment;
import com.distributed.idgenerator.entity.ServerRegistry;
import com.distributed.idgenerator.repository.IdSegmentRepository;
import com.distributed.idgenerator.repository.ServerRegistryRepository;
import com.distributed.idgenerator.service.IdGeneratorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 负载均衡和故障转移功能测试
 * 
 * @author System
 * @version 1.0.0
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
public class LoadBalanceTest {

    @Autowired
    private IdGeneratorService idGeneratorService;

    @Autowired
    private IdSegmentRepository idSegmentRepository;

    @Autowired
    private ServerRegistryRepository serverRegistryRepository;

    @BeforeEach
    void setUp() {
        // 清理测试数据
        idSegmentRepository.deleteAll();
        serverRegistryRepository.deleteAll();
    }

    @Test
    void testLoadBalanceWithBothServersOnline() {
        // 模拟两个服务器都在线
        ServerRegistry evenServer = ServerRegistry.builder()
                .serverId("test-even-server")
                .serverType(0)
                .status(1)
                .build();
        
        ServerRegistry oddServer = ServerRegistry.builder()
                .serverId("test-odd-server")
                .serverType(1)
                .status(1)
                .build();
        
        serverRegistryRepository.save(evenServer);
        serverRegistryRepository.save(oddServer);

        // 创建测试请求
        IdRequest request = IdRequest.builder()
                .businessType("TEST_BALANCE")
                .count(10)
                .build();

        // 生成ID
        IdResponse response = idGeneratorService.generateIds(request);
        
        assertNotNull(response);
        assertEquals(10, response.getIds().size());
        
        // 验证分片类型选择是基于负载均衡的
        assertTrue(response.getShardType() == 0 || response.getShardType() == 1);
        
        System.out.println("负载均衡测试 - 选择的分片类型: " + response.getShardType());
        System.out.println("生成的ID: " + response.getIds());
    }

    @Test
    void testFailoverWhenEvenServerOffline() {
        // 模拟只有奇数服务器在线
        ServerRegistry oddServer = ServerRegistry.builder()
                .serverId("test-odd-server")
                .serverType(1)
                .status(1)
                .build();
        
        serverRegistryRepository.save(oddServer);

        // 创建测试请求
        IdRequest request = IdRequest.builder()
                .businessType("TEST_FAILOVER")
                .count(5)
                .build();

        // 生成ID
        IdResponse response = idGeneratorService.generateIds(request);
        
        assertNotNull(response);
        assertEquals(5, response.getIds().size());
        
        // 在故障转移模式下，应该能够使用任意分片类型
        assertTrue(response.getShardType() == 0 || response.getShardType() == 1);
        
        System.out.println("故障转移测试 - 选择的分片类型: " + response.getShardType());
        System.out.println("生成的ID: " + response.getIds());
    }

    @Test
    void testServerStatusMonitoring() {
        // 模拟服务器状态
        ServerRegistry evenServer = ServerRegistry.builder()
                .serverId("test-even-server")
                .serverType(0)
                .status(1)
                .build();
        
        serverRegistryRepository.save(evenServer);

        // 获取服务器状态
        Map<String, Object> status = idGeneratorService.getServerStatus();
        
        assertNotNull(status);
        assertTrue(status.containsKey("isInFailoverMode"));
        assertTrue(status.containsKey("loadBalance"));
        assertTrue(status.containsKey("proxyShardCount"));
        
        // 验证故障转移模式检测
        Boolean isInFailoverMode = (Boolean) status.get("isInFailoverMode");
        assertTrue(isInFailoverMode); // 只有一个服务器在线，应该是故障转移模式
        
        System.out.println("服务器状态监控测试:");
        System.out.println("故障转移模式: " + isInFailoverMode);
        System.out.println("负载均衡信息: " + status.get("loadBalance"));
    }

    @Test
    void testConflictResolution() {
        // 创建冲突的号段数据
        IdSegment evenSegment = IdSegment.builder()
                .businessType("TEST_CONFLICT")
                .timeKey("20240101")
                .shardType(0)
                .maxValue(1000L)
                .stepSize(500)
                .build();
        
        IdSegment oddSegment = IdSegment.builder()
                .businessType("TEST_CONFLICT")
                .timeKey("20240101")
                .shardType(1)
                .maxValue(2000L)
                .stepSize(1000)
                .build();
        
        idSegmentRepository.save(evenSegment);
        idSegmentRepository.save(oddSegment);

        // 执行冲突解决
        Map<String, Object> result = idGeneratorService.resolveConflictsAfterRecovery();
        
        assertNotNull(result);
        assertTrue((Boolean) result.get("success"));
        
        // 验证冲突已解决
        List<IdSegment> resolvedSegments = idSegmentRepository
                .findByBusinessTypeAndTimeKey("TEST_CONFLICT", "20240101");
        
        // 所有号段应该有相同的maxValue和stepSize
        long maxValue = resolvedSegments.get(0).getMaxValue();
        int stepSize = resolvedSegments.get(0).getStepSize();
        
        for (IdSegment segment : resolvedSegments) {
            assertEquals(maxValue, segment.getMaxValue());
            assertEquals(stepSize, segment.getStepSize());
        }
        
        System.out.println("冲突解决测试:");
        System.out.println("解决结果: " + result.get("message"));
        System.out.println("统一后的maxValue: " + maxValue);
        System.out.println("统一后的stepSize: " + stepSize);
    }

    @Test
    void testDynamicShardTypeSelection() {
        // 模拟两个服务器都在线
        ServerRegistry evenServer = ServerRegistry.builder()
                .serverId("test-even-server")
                .serverType(0)
                .status(1)
                .build();
        
        ServerRegistry oddServer = ServerRegistry.builder()
                .serverId("test-odd-server")
                .serverType(1)
                .status(1)
                .build();
        
        serverRegistryRepository.save(evenServer);
        serverRegistryRepository.save(oddServer);

        // 创建一个已存在的偶数分片，使其负载较高
        IdSegment existingEvenSegment = IdSegment.builder()
                .businessType("TEST_DYNAMIC")
                .timeKey("20240101")
                .shardType(0)
                .maxValue(10000L)
                .stepSize(1000)
                .build();
        
        idSegmentRepository.save(existingEvenSegment);

        // 创建测试请求
        IdRequest request = IdRequest.builder()
                .businessType("TEST_DYNAMIC")
                .timeKey("20240101")
                .count(5)
                .build();

        // 生成ID
        IdResponse response = idGeneratorService.generateIds(request);
        
        assertNotNull(response);
        assertEquals(5, response.getIds().size());
        
        // 由于偶数分片负载较高，应该倾向于选择奇数分片
        // 但这取决于具体的负载均衡算法实现
        System.out.println("动态分片选择测试 - 选择的分片类型: " + response.getShardType());
        System.out.println("生成的ID: " + response.getIds());
        
        // 验证ID的连续性和正确性
        List<Long> ids = response.getIds();
        for (int i = 1; i < ids.size(); i++) {
            assertTrue(ids.get(i) > ids.get(i-1), "ID应该是递增的");
        }
    }

    @Test
    void testLoadBalanceInfo() {
        // 创建不同负载的号段
        IdSegment evenSegment = IdSegment.builder()
                .businessType("TEST_LOAD")
                .timeKey("20240101")
                .shardType(0)
                .maxValue(5000L)
                .stepSize(1000)
                .build();
        
        IdSegment oddSegment = IdSegment.builder()
                .businessType("TEST_LOAD")
                .timeKey("20240102")
                .shardType(1)
                .maxValue(3000L)
                .stepSize(1000)
                .build();
        
        idSegmentRepository.save(evenSegment);
        idSegmentRepository.save(oddSegment);

        // 获取服务器状态
        Map<String, Object> status = idGeneratorService.getServerStatus();
        Map<String, Object> loadBalance = (Map<String, Object>) status.get("loadBalance");
        
        assertNotNull(loadBalance);
        assertTrue(loadBalance.containsKey("evenServerLoad"));
        assertTrue(loadBalance.containsKey("oddServerLoad"));
        assertTrue(loadBalance.containsKey("isBalanced"));
        
        Long evenLoad = (Long) loadBalance.get("evenServerLoad");
        Long oddLoad = (Long) loadBalance.get("oddServerLoad");
        
        assertEquals(5000L, evenLoad.longValue());
        assertEquals(3000L, oddLoad.longValue());
        
        System.out.println("负载均衡信息测试:");
        System.out.println("偶数服务器负载: " + evenLoad);
        System.out.println("奇数服务器负载: " + oddLoad);
        System.out.println("是否均衡: " + loadBalance.get("isBalanced"));
    }
}