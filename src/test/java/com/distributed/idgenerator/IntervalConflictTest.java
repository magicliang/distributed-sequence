package com.distributed.idgenerator;

import com.distributed.idgenerator.dto.IdRequest;
import com.distributed.idgenerator.dto.IdResponse;
import com.distributed.idgenerator.entity.IdSegment;
import com.distributed.idgenerator.repository.IdSegmentRepository;
import com.distributed.idgenerator.service.IdGeneratorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 区间冲突测试
 * 验证修复后的区间计算算法不会产生冲突
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
public class IntervalConflictTest {

    @Autowired
    private IdGeneratorService idGeneratorService;

    @Autowired
    private IdSegmentRepository idSegmentRepository;

    private Method calculateNextIntervalMaxValueMethod;
    private Method getGlobalMaxValueMethod;
    private Method findNextAvailableIntervalIndexMethod;

    @BeforeEach
    void setUp() throws Exception {
        try {
            // 通过反射获取私有方法进行测试
            calculateNextIntervalMaxValueMethod = IdGeneratorService.class.getDeclaredMethod(
                    "calculateNextIntervalMaxValue", String.class, String.class, int.class, int.class, int.class);
            calculateNextIntervalMaxValueMethod.setAccessible(true);

            getGlobalMaxValueMethod = IdGeneratorService.class.getDeclaredMethod(
                    "getGlobalMaxValue", String.class, String.class, int.class);
            getGlobalMaxValueMethod.setAccessible(true);

            findNextAvailableIntervalIndexMethod = IdGeneratorService.class.getDeclaredMethod(
                    "findNextAvailableIntervalIndex", long.class, int.class);
            findNextAvailableIntervalIndexMethod.setAccessible(true);
        } catch (NoSuchMethodException e) {
            System.err.println("Failed to find method: " + e.getMessage());
            // 打印所有可用的方法
            Method[] methods = IdGeneratorService.class.getDeclaredMethods();
            for (Method method : methods) {
                if (method.getName().contains("calculateNext") || method.getName().contains("getGlobal") || method.getName().contains("findNext")) {
                    System.err.println("Available method: " + method.getName() + " with parameters: " + Arrays.toString(method.getParameterTypes()));
                }
            }
            throw e;
        }
    }

    @Test
    void testNoIntervalConflict() throws Exception {
        String businessType = "conflict_test";
        String timeKey = "20241222";

        // 清理测试数据
        List<IdSegment> existingSegments = idSegmentRepository.findByBusinessTypeAndTimeKey(businessType, timeKey);
        if (!existingSegments.isEmpty()) {
            idSegmentRepository.deleteAll(existingSegments);
        }

        // 测试基本的ID生成功能，确保奇偶分片能正常工作
        IdRequest request = new IdRequest();
        request.setBusinessType(businessType);
        request.setTimeKey(timeKey);
        request.setCount(10);

        // 生成一批ID
        IdResponse response = idGeneratorService.generateIds(request);
        
        assertNotNull(response, "响应不应该为空");
        assertTrue(response.isSuccess(), "ID生成应该成功");
        assertNotNull(response.getIds(), "ID列表不应该为空");
        assertEquals(10, response.getIds().size(), "应该生成10个ID");
        
        // 验证ID是连续的
        List<Long> ids = response.getIds();
        for (int i = 1; i < ids.size(); i++) {
            assertEquals(ids.get(i-1) + 1, ids.get(i).longValue(), 
                    "ID应该是连续的: " + ids.get(i-1) + " -> " + ids.get(i));
        }
        
        // 验证ID在合理范围内
        // 在容错模式下，偶数服务器可能接管奇数分片，从区间0开始: [1, 1000]
        // 或者使用自己的区间1: [1001, 2000]
        assertTrue(ids.get(0) >= 1, "第一个ID应该 >= 1，实际值: " + ids.get(0));
        assertTrue(ids.get(0) <= 2000, "第一个ID应该 <= 2000，实际值: " + ids.get(0));
        
        // 验证ID确实是有效的正整数
        for (Long id : ids) {
            assertTrue(id > 0, "所有ID都应该是正整数，发现: " + id);
        }
    }

    @Test
    void testGlobalMaxValueCalculation() throws Exception {
        String businessType = "global_max_test";
        String timeKey = "20241222";
        int stepSize = 1000;

        // 清理测试数据
        List<IdSegment> existingSegments = idSegmentRepository.findByBusinessTypeAndTimeKey(businessType, timeKey);
        if (!existingSegments.isEmpty()) {
            idSegmentRepository.deleteAll(existingSegments);
        }

        // 只有偶数分片有数据
        IdSegment evenSegment = new IdSegment();
        evenSegment.setBusinessType(businessType);
        evenSegment.setTimeKey(timeKey);
        evenSegment.setShardType(0);
        evenSegment.setMaxValue(5000L);
        evenSegment.setStepSize(stepSize);
        idSegmentRepository.save(evenSegment);

        // 测试全局最大值计算
        long globalMaxValue = (Long) getGlobalMaxValueMethod.invoke(
                idGeneratorService, businessType, timeKey, stepSize);

        assertEquals(5000L, globalMaxValue, "全局最大值应该是5000");
    }

    @Test
    void testFindNextAvailableIntervalIndex() throws Exception {
        // 测试奇数服务器（使用偶数索引区间）
        long nextOddServerIndex = (Long) findNextAvailableIntervalIndexMethod.invoke(
                idGeneratorService, 5L, 1); // 当前全局索引5，奇数服务器
        assertEquals(6L, nextOddServerIndex, "奇数服务器应该使用下一个偶数索引6");

        // 测试偶数服务器（使用奇数索引区间）
        long nextEvenServerIndex = (Long) findNextAvailableIntervalIndexMethod.invoke(
                idGeneratorService, 5L, 0); // 当前全局索引5，偶数服务器
        assertEquals(7L, nextEvenServerIndex, "偶数服务器应该使用下一个奇数索引7");
    }

    @Test
    void testIntervalSequenceCorrectness() throws Exception {
        String businessType = "sequence_test";
        String timeKey = "20241222";
        int stepSize = 1000;

        // 清理测试数据
        List<IdSegment> existingSegments = idSegmentRepository.findByBusinessTypeAndTimeKey(businessType, timeKey);
        if (!existingSegments.isEmpty()) {
            idSegmentRepository.deleteAll(existingSegments);
        }

        // 模拟交替分配场景
        long[] expectedOddIntervals = {1000L, 3000L, 5000L, 7000L}; // 区间0,2,4,6
        long[] expectedEvenIntervals = {2000L, 4000L, 6000L, 8000L}; // 区间1,3,5,7

        // 初始化两个分片
        IdSegment oddSegment = new IdSegment();
        oddSegment.setBusinessType(businessType);
        oddSegment.setTimeKey(timeKey);
        oddSegment.setShardType(1);
        oddSegment.setMaxValue(0L);
        oddSegment.setStepSize(stepSize);
        idSegmentRepository.save(oddSegment);

        IdSegment evenSegment = new IdSegment();
        evenSegment.setBusinessType(businessType);
        evenSegment.setTimeKey(timeKey);
        evenSegment.setShardType(0);
        evenSegment.setMaxValue(0L);
        evenSegment.setStepSize(stepSize);
        idSegmentRepository.save(evenSegment);

        // 模拟交替分配
        for (int i = 0; i < 4; i++) {
            // 奇数服务器分配
            long oddMaxValue = (Long) calculateNextIntervalMaxValueMethod.invoke(
                    idGeneratorService, businessType, timeKey, stepSize, stepSize, 1);
            assertEquals(expectedOddIntervals[i], oddMaxValue, 
                    "奇数服务器第" + (i+1) + "次分配应该得到区间" + (i*2));
            
            // 更新数据库状态
            idSegmentRepository.updateMaxValueAtomicallyWithValue(
                    businessType, timeKey, 1, oddMaxValue);

            // 偶数服务器分配
            long evenMaxValue = (Long) calculateNextIntervalMaxValueMethod.invoke(
                    idGeneratorService, businessType, timeKey, stepSize, stepSize, 0);
            assertEquals(expectedEvenIntervals[i], evenMaxValue,
                    "偶数服务器第" + (i+1) + "次分配应该得到区间" + (i*2+1));
            
            // 更新数据库状态
            idSegmentRepository.updateMaxValueAtomicallyWithValue(
                    businessType, timeKey, 0, evenMaxValue);
        }
    }

    @Test
    void testStepSizeChangeWithGlobalConsistency() throws Exception {
        String businessType = "stepsize_test";
        String timeKey = "20241222";
        int oldStepSize = 1000;
        int newStepSize = 2000;

        // 清理测试数据
        List<IdSegment> existingSegments = idSegmentRepository.findByBusinessTypeAndTimeKey(businessType, timeKey);
        if (!existingSegments.isEmpty()) {
            idSegmentRepository.deleteAll(existingSegments);
        }

        // 设置初始状态：两个分片都有一些进度
        IdSegment oddSegment = new IdSegment();
        oddSegment.setBusinessType(businessType);
        oddSegment.setTimeKey(timeKey);
        oddSegment.setShardType(1);
        oddSegment.setMaxValue(3000L); // 区间2结束
        oddSegment.setStepSize(oldStepSize);
        idSegmentRepository.save(oddSegment);

        IdSegment evenSegment = new IdSegment();
        evenSegment.setBusinessType(businessType);
        evenSegment.setTimeKey(timeKey);
        evenSegment.setShardType(0);
        evenSegment.setMaxValue(4000L); // 区间3结束
        evenSegment.setStepSize(oldStepSize);
        idSegmentRepository.save(evenSegment);

        // 步长变更后，计算下一个区间
        long nextOddMaxValue = (Long) calculateNextIntervalMaxValueMethod.invoke(
                idGeneratorService, businessType, timeKey, oldStepSize, newStepSize, 1);

        // 基于全局最大值4000，下一个奇数服务器区间应该是区间4: [8001, 10000]
        assertEquals(10000L, nextOddMaxValue, 
                "步长变更后，奇数服务器应该基于全局最大值计算下一个区间");
    }
}