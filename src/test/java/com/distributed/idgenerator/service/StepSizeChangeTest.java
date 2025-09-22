package com.distributed.idgenerator.service;

import com.distributed.idgenerator.dto.IdRequest;
import com.distributed.idgenerator.dto.IdResponse;
import com.distributed.idgenerator.entity.IdSegment;
import com.distributed.idgenerator.repository.IdSegmentRepository;
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
 * 步长变更功能测试
 * 
 * @author System
 * @version 1.0.0
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
public class StepSizeChangeTest {

    @Autowired
    private IdGeneratorService idGeneratorService;

    @Autowired
    private IdSegmentRepository idSegmentRepository;

    @Test
    public void testStepSizeChangePreview() {
        // 准备测试数据
        String businessType = "test_order";
        String timeKey = "20231201";
        
        // 先生成一些ID，创建初始号段
        IdRequest request = IdRequest.builder()
                .businessType(businessType)
                .timeKey(timeKey)
                .count(5)
                .customStepSize(1000)
                .build();
        
        IdResponse response = idGeneratorService.generateIds(request);
        assertTrue(response.getIds().size() > 0);
        
        // 验证初始步长
        Optional<IdSegment> segment = idSegmentRepository
                .findByBusinessTypeAndTimeKeyAndShardType(businessType, timeKey, 0);
        assertTrue(segment.isPresent());
        assertEquals(1000, segment.get().getStepSize().intValue());
        
        // 预览步长变更
        Map<String, Object> previewResult = idGeneratorService.changeStepSize(
                businessType, null, 2000, true);
        
        // 验证预览结果
        assertTrue((Boolean) previewResult.get("success"));
        assertTrue((Boolean) previewResult.get("preview"));
        assertEquals(2000, previewResult.get("newStepSize"));
        assertTrue((Integer) previewResult.get("changedCount") > 0);
        
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> affectedSegments = 
                (List<Map<String, Object>>) previewResult.get("affectedSegments");
        assertFalse(affectedSegments.isEmpty());
        
        // 验证预览不会实际修改数据
        Optional<IdSegment> unchangedSegment = idSegmentRepository
                .findByBusinessTypeAndTimeKeyAndShardType(businessType, timeKey, 0);
        assertTrue(unchangedSegment.isPresent());
        assertEquals(1000, unchangedSegment.get().getStepSize().intValue());
        
        System.out.println("步长变更预览测试通过");
        System.out.println("预览结果: " + previewResult.get("message"));
    }

    @Test
    public void testStepSizeChangeExecution() {
        // 准备测试数据
        String businessType = "test_product";
        String timeKey = "20231202";
        
        // 先生成一些ID，创建初始号段
        IdRequest request = IdRequest.builder()
                .businessType(businessType)
                .timeKey(timeKey)
                .count(5)
                .customStepSize(500)
                .build();
        
        IdResponse response = idGeneratorService.generateIds(request);
        assertTrue(response.getIds().size() > 0);
        
        // 验证初始步长
        Optional<IdSegment> initialSegment = idSegmentRepository
                .findByBusinessTypeAndTimeKeyAndShardType(businessType, timeKey, 0);
        assertTrue(initialSegment.isPresent());
        assertEquals(500, initialSegment.get().getStepSize().intValue());
        
        // 执行步长变更
        Map<String, Object> changeResult = idGeneratorService.changeStepSize(
                businessType, timeKey, 1500, false);
        
        // 验证变更结果
        assertTrue((Boolean) changeResult.get("success"));
        assertFalse((Boolean) changeResult.get("preview"));
        assertEquals(1500, changeResult.get("newStepSize"));
        assertTrue((Integer) changeResult.get("changedCount") > 0);
        
        // 验证数据库中的步长已更新
        Optional<IdSegment> updatedSegment = idSegmentRepository
                .findByBusinessTypeAndTimeKeyAndShardType(businessType, timeKey, 0);
        assertTrue(updatedSegment.isPresent());
        assertEquals(1500, updatedSegment.get().getStepSize().intValue());
        
        // 验证后续ID生成使用新步长
        IdRequest newRequest = IdRequest.builder()
                .businessType(businessType)
                .timeKey(timeKey)
                .count(5)
                .build();
        
        IdResponse newResponse = idGeneratorService.generateIds(newRequest);
        assertTrue(newResponse.getIds().size() > 0);
        
        System.out.println("步长变更执行测试通过");
        System.out.println("变更结果: " + changeResult.get("message"));
        System.out.println("新生成的ID: " + newResponse.getIds());
    }

    @Test
    public void testStepSizeChangeWithMultipleSegments() {
        // 准备测试数据 - 创建多个时间键的号段
        String businessType = "test_user";
        String[] timeKeys = {"20231201", "20231202", "20231203"};
        
        // 为每个时间键生成ID，创建号段
        for (String timeKey : timeKeys) {
            IdRequest request = IdRequest.builder()
                    .businessType(businessType)
                    .timeKey(timeKey)
                    .count(3)
                    .customStepSize(800)
                    .build();
            
            IdResponse response = idGeneratorService.generateIds(request);
            assertTrue(response.getIds().size() > 0);
        }
        
        // 验证所有号段都已创建
        List<IdSegment> segments = idSegmentRepository.findByBusinessType(businessType);
        assertTrue(segments.size() >= timeKeys.length);
        
        // 批量变更所有时间键的步长
        Map<String, Object> changeResult = idGeneratorService.changeStepSize(
                businessType, null, 1200, false);
        
        // 验证批量变更结果
        assertTrue((Boolean) changeResult.get("success"));
        assertTrue((Integer) changeResult.get("totalSegments") >= timeKeys.length);
        assertTrue((Integer) changeResult.get("changedCount") > 0);
        
        // 验证所有号段的步长都已更新
        List<IdSegment> updatedSegments = idSegmentRepository.findByBusinessType(businessType);
        for (IdSegment segment : updatedSegments) {
            assertEquals(1200, segment.getStepSize().intValue());
        }
        
        System.out.println("多号段步长变更测试通过");
        System.out.println("变更结果: " + changeResult.get("message"));
    }

    @Test
    public void testGetCurrentStepSizeInfo() {
        // 准备测试数据
        String businessType = "test_info";
        
        // 创建不同步长的号段
        IdRequest request1 = IdRequest.builder()
                .businessType(businessType)
                .timeKey("20231201")
                .count(3)
                .customStepSize(1000)
                .build();
        
        IdRequest request2 = IdRequest.builder()
                .businessType(businessType)
                .timeKey("20231202")
                .count(3)
                .customStepSize(1500)
                .build();
        
        idGeneratorService.generateIds(request1);
        idGeneratorService.generateIds(request2);
        
        // 查询指定业务类型的步长信息
        Map<String, Object> stepSizeInfo = idGeneratorService.getCurrentStepSizeInfo(businessType);
        
        // 验证查询结果
        assertTrue((Boolean) stepSizeInfo.get("success"));
        assertEquals(businessType, stepSizeInfo.get("businessType"));
        assertTrue((Integer) stepSizeInfo.get("totalSegments") >= 2);
        
        @SuppressWarnings("unchecked")
        Map<String, List<Map<String, Object>>> segments = 
                (Map<String, List<Map<String, Object>>>) stepSizeInfo.get("segments");
        assertFalse(segments.isEmpty());
        
        // 查询所有业务类型的统计信息
        Map<String, Object> allStepSizeInfo = idGeneratorService.getCurrentStepSizeInfo(null);
        
        // 验证统计结果
        assertTrue((Boolean) allStepSizeInfo.get("success"));
        assertTrue((Integer) allStepSizeInfo.get("totalBusinessTypes") > 0);
        
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> businessTypes = 
                (List<Map<String, Object>>) allStepSizeInfo.get("businessTypes");
        assertFalse(businessTypes.isEmpty());
        
        System.out.println("步长信息查询测试通过");
        System.out.println("指定业务类型信息: " + stepSizeInfo.get("totalSegments") + " 个号段");
        System.out.println("全局统计信息: " + allStepSizeInfo.get("totalBusinessTypes") + " 个业务类型");
    }

    @Test
    public void testStepSizeChangeValidation() {
        String businessType = "test_validation";
        
        // 测试无效步长（null）
        Map<String, Object> result1 = idGeneratorService.changeStepSize(
                businessType, null, null, false);
        assertFalse((Boolean) result1.get("success"));
        assertTrue(result1.get("message").toString().contains("步长必须大于0"));
        
        // 测试无效步长（0）
        Map<String, Object> result2 = idGeneratorService.changeStepSize(
                businessType, null, 0, false);
        assertFalse((Boolean) result2.get("success"));
        assertTrue(result2.get("message").toString().contains("步长必须大于0"));
        
        // 测试无效步长（负数）
        Map<String, Object> result3 = idGeneratorService.changeStepSize(
                businessType, null, -100, false);
        assertFalse((Boolean) result3.get("success"));
        assertTrue(result3.get("message").toString().contains("步长必须大于0"));
        
        System.out.println("步长变更参数验证测试通过");
    }

    @Test
    public void testStepSizeChangeIdempotency() {
        // 准备测试数据
        String businessType = "test_idempotency";
        String timeKey = "20231201";
        
        // 创建初始号段
        IdRequest request = IdRequest.builder()
                .businessType(businessType)
                .timeKey(timeKey)
                .count(3)
                .customStepSize(1000)
                .build();
        
        idGeneratorService.generateIds(request);
        
        // 第一次变更步长
        Map<String, Object> result1 = idGeneratorService.changeStepSize(
                businessType, timeKey, 2000, false);
        assertTrue((Boolean) result1.get("success"));
        assertTrue((Integer) result1.get("changedCount") > 0);
        
        // 第二次使用相同步长（应该无变更）
        Map<String, Object> result2 = idGeneratorService.changeStepSize(
                businessType, timeKey, 2000, false);
        assertTrue((Boolean) result2.get("success"));
        assertEquals(0, (Integer) result2.get("changedCount"));
        assertTrue((Integer) result2.get("skippedCount") > 0);
        
        System.out.println("步长变更幂等性测试通过");
        System.out.println("第一次变更: " + result1.get("changedCount") + " 个号段");
        System.out.println("第二次变更: " + result2.get("changedCount") + " 个号段");
    }
}