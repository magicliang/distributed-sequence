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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 步长同步功能测试
 * 验证强制步长同步和一致性检查功能
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
public class StepSizeSyncTest {

    @Autowired
    private IdGeneratorService idGeneratorService;

    @Autowired
    private IdSegmentRepository idSegmentRepository;

    @BeforeEach
    void setUp() {
        // 清理测试数据
        String businessType = "sync_test";
        String timeKey = "20241222";
        List<IdSegment> existingSegments = idSegmentRepository.findByBusinessTypeAndTimeKey(businessType, timeKey);
        if (!existingSegments.isEmpty()) {
            idSegmentRepository.deleteAll(existingSegments);
        }
    }

    @Test
    void testForceGlobalStepSizeSync() {
        String businessType = "sync_test";
        String timeKey = "20241222";

        // 创建两个分片，使用不同的步长（模拟不一致状态）
        IdSegment evenSegment = new IdSegment();
        evenSegment.setBusinessType(businessType);
        evenSegment.setTimeKey(timeKey);
        evenSegment.setShardType(0);
        evenSegment.setMaxValue(1000L);
        evenSegment.setStepSize(1000); // 步长1000
        idSegmentRepository.save(evenSegment);

        IdSegment oddSegment = new IdSegment();
        oddSegment.setBusinessType(businessType);
        oddSegment.setTimeKey(timeKey);
        oddSegment.setShardType(1);
        oddSegment.setMaxValue(2000L);
        oddSegment.setStepSize(1500); // 步长1500（不一致）
        idSegmentRepository.save(oddSegment);

        // 1. 检查初始状态（应该不一致）
        Map<String, Object> consistencyCheck = idGeneratorService.checkStepSizeConsistency(businessType);
        assertFalse((Boolean) consistencyCheck.get("isConsistent"), "初始状态应该不一致");
        assertEquals(2L, consistencyCheck.get("distinctStepSizeCount"), "应该有2种不同的步长");

        // 2. 预览强制同步
        Map<String, Object> previewResult = idGeneratorService.forceGlobalStepSizeSync(businessType, 2000, true);
        assertTrue((Boolean) previewResult.get("success"), "预览应该成功");
        assertTrue((Boolean) previewResult.get("preview"), "应该是预览模式");
        assertEquals(2, previewResult.get("updatedCount"), "应该影响2个号段");

        // 3. 执行强制同步
        Map<String, Object> syncResult = idGeneratorService.forceGlobalStepSizeSync(businessType, 2000, false);
        assertTrue((Boolean) syncResult.get("success"), "同步应该成功");
        assertFalse((Boolean) syncResult.get("preview"), "应该是执行模式");
        assertEquals(2, syncResult.get("updatedCount"), "应该更新2个号段");

        // 4. 验证同步后的一致性
        Map<String, Object> afterSyncCheck = idGeneratorService.checkStepSizeConsistency(businessType);
        assertTrue((Boolean) afterSyncCheck.get("isConsistent"), "同步后应该一致");
        assertEquals(1L, afterSyncCheck.get("distinctStepSizeCount"), "应该只有1种步长");
        assertEquals(2000, afterSyncCheck.get("commonStepSize"), "统一步长应该是2000");

        // 5. 验证数据库中的实际数据
        List<IdSegment> updatedSegments = idSegmentRepository.findByBusinessTypeAndTimeKey(businessType, timeKey);
        assertEquals(2, updatedSegments.size(), "应该有2个号段");
        for (IdSegment segment : updatedSegments) {
            assertEquals(2000, segment.getStepSize(), "所有号段的步长都应该是2000");
        }
    }

    @Test
    void testStepSizeConsistencyCheck() {
        String businessType = "consistency_test";
        String timeKey = "20241222";

        // 创建一致的分片
        IdSegment evenSegment = new IdSegment();
        evenSegment.setBusinessType(businessType);
        evenSegment.setTimeKey(timeKey);
        evenSegment.setShardType(0);
        evenSegment.setMaxValue(1000L);
        evenSegment.setStepSize(1000);
        idSegmentRepository.save(evenSegment);

        IdSegment oddSegment = new IdSegment();
        oddSegment.setBusinessType(businessType);
        oddSegment.setTimeKey(timeKey);
        oddSegment.setShardType(1);
        oddSegment.setMaxValue(2000L);
        oddSegment.setStepSize(1000); // 相同步长
        idSegmentRepository.save(oddSegment);

        // 检查一致性
        Map<String, Object> result = idGeneratorService.checkStepSizeConsistency(businessType);
        
        assertTrue((Boolean) result.get("success"), "检查应该成功");
        assertTrue((Boolean) result.get("isConsistent"), "步长应该一致");
        assertEquals(1L, result.get("distinctStepSizeCount"), "应该只有1种步长");
        assertEquals(2, result.get("totalShards"), "应该有2个分片");
        assertEquals(1000, result.get("commonStepSize"), "统一步长应该是1000");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> shardDetails = (List<Map<String, Object>>) result.get("shardDetails");
        assertEquals(2, shardDetails.size(), "应该有2个分片详情");
        
        for (Map<String, Object> detail : shardDetails) {
            assertEquals(1000, detail.get("stepSize"), "每个分片的步长都应该是1000");
        }
    }

    @Test
    void testGlobalStepSizeConsistencyReport() {
        // 创建多个业务类型的测试数据
        String[] businessTypes = {"report_test_1", "report_test_2", "report_test_3"};
        String timeKey = "20241222";

        // 业务类型1：一致的步长
        createTestSegments(businessTypes[0], timeKey, 1000, 1000);
        
        // 业务类型2：不一致的步长
        createTestSegments(businessTypes[1], timeKey, 1000, 1500);
        
        // 业务类型3：一致的步长
        createTestSegments(businessTypes[2], timeKey, 2000, 2000);

        // 获取全局报告
        Map<String, Object> report = idGeneratorService.getGlobalStepSizeConsistencyReport();
        
        assertTrue((Boolean) report.get("success"), "报告生成应该成功");
        assertTrue((Integer) report.get("totalBusinessTypes") >= 3, "应该至少有3个业务类型");
        assertTrue((Integer) report.get("inconsistentBusinessTypes") >= 1, "应该至少有1个不一致的业务类型");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> businessReports = (List<Map<String, Object>>) report.get("businessReports");
        assertNotNull(businessReports, "业务报告不应该为空");
        assertTrue(businessReports.size() >= 3, "应该至少有3个业务报告");
    }

    @Test
    void testForceGlobalStepSizeSyncAllBusinessTypes() {
        // 创建多个业务类型的测试数据
        createTestSegments("global_test_1", "20241222", 1000, 1500);
        createTestSegments("global_test_2", "20241222", 2000, 2500);

        // 执行全局同步（不指定业务类型）
        Map<String, Object> syncResult = idGeneratorService.forceGlobalStepSizeSync(null, 3000, false);
        
        assertTrue((Boolean) syncResult.get("success"), "全局同步应该成功");
        assertTrue((Integer) syncResult.get("updatedCount") >= 4, "应该至少更新4个号段");

        // 验证所有业务类型都已同步
        Map<String, Object> globalReport = idGeneratorService.getGlobalStepSizeConsistencyReport();
        assertEquals(0, globalReport.get("inconsistentBusinessTypes"), "全局同步后不应该有不一致的业务类型");
    }

    private void createTestSegments(String businessType, String timeKey, int evenStepSize, int oddStepSize) {
        // 清理现有数据
        List<IdSegment> existingSegments = idSegmentRepository.findByBusinessTypeAndTimeKey(businessType, timeKey);
        if (!existingSegments.isEmpty()) {
            idSegmentRepository.deleteAll(existingSegments);
        }

        // 创建偶数分片
        IdSegment evenSegment = new IdSegment();
        evenSegment.setBusinessType(businessType);
        evenSegment.setTimeKey(timeKey);
        evenSegment.setShardType(0);
        evenSegment.setMaxValue(1000L);
        evenSegment.setStepSize(evenStepSize);
        idSegmentRepository.save(evenSegment);

        // 创建奇数分片
        IdSegment oddSegment = new IdSegment();
        oddSegment.setBusinessType(businessType);
        oddSegment.setTimeKey(timeKey);
        oddSegment.setShardType(1);
        oddSegment.setMaxValue(2000L);
        oddSegment.setStepSize(oddStepSize);
        idSegmentRepository.save(oddSegment);
    }
}