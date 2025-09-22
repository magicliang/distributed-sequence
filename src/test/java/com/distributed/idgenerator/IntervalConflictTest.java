package com.distributed.idgenerator;

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
    }

    @Test
    void testNoIntervalConflict() throws Exception {
        String businessType = "conflict_test";
        String timeKey = "20241222";
        int stepSize = 1000;

        // 清理测试数据
        idSegmentRepository.deleteByBusinessTypeAndTimeKey(businessType, timeKey);

        // 模拟场景：偶数服务器已经跑到很前面
        // 偶数服务器使用区间1: [1001, 2000], 区间3: [3001, 4000], 区间5: [5001, 6000]
        IdSegment evenSegment = new IdSegment();
        evenSegment.setBusinessType(businessType);
        evenSegment.setTimeKey(timeKey);
        evenSegment.setShardType(0); // 偶数
        evenSegment.setMaxValue(6000L); // 已经到区间5
        evenSegment.setStepSize(stepSize);
        idSegmentRepository.save(evenSegment);

        // 奇数服务器还在区间0: [1, 1000]
        IdSegment oddSegment = new IdSegment();
        oddSegment.setBusinessType(businessType);
        oddSegment.setTimeKey(timeKey);
        oddSegment.setShardType(1); // 奇数
        oddSegment.setMaxValue(1000L); // 还在区间0
        oddSegment.setStepSize(stepSize);
        idSegmentRepository.save(oddSegment);

        // 测试奇数服务器计算下一个区间
        long nextOddMaxValue = (Long) calculateNextIntervalMaxValueMethod.invoke(
                idGeneratorService, businessType, timeKey, stepSize, stepSize, 1);

        // 奇数服务器应该跳到区间6: [6001, 7000]，而不是区间2
        assertEquals(7000L, nextOddMaxValue, "奇数服务器应该跳到区间6，避免与偶数服务器冲突");

        // 测试偶数服务器计算下一个区间
        long nextEvenMaxValue = (Long) calculateNextIntervalMaxValueMethod.invoke(
                idGeneratorService, businessType, timeKey, stepSize, stepSize, 0);

        // 偶数服务器应该跳到区间7: [7001, 8000]
        assertEquals(8000L, nextEvenMaxValue, "偶数服务器应该跳到区间7");

        // 验证区间不重叠
        assertTrue(nextOddMaxValue < nextEvenMaxValue - stepSize || 
                   nextEvenMaxValue < nextOddMaxValue - stepSize,
                   "两个分片的区间不应该重叠");
    }

    @Test
    void testGlobalMaxValueCalculation() throws Exception {
        String businessType = "global_max_test";
        String timeKey = "20241222";
        int stepSize = 1000;

        // 清理测试数据
        idSegmentRepository.deleteByBusinessTypeAndTimeKey(businessType, timeKey);

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
        idSegmentRepository.deleteByBusinessTypeAndTimeKey(businessType, timeKey);

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
        idSegmentRepository.deleteByBusinessTypeAndTimeKey(businessType, timeKey);

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