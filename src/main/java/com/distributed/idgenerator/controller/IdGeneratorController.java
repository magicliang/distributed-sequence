package com.distributed.idgenerator.controller;

import com.distributed.idgenerator.dto.IdRequest;
import com.distributed.idgenerator.dto.IdResponse;
import com.distributed.idgenerator.service.IdGeneratorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.HashMap;
import java.util.Map;

/**
 * ID生成器控制器
 * 
 * @author System
 * @version 1.0.0
 */
@RestController
@RequestMapping("/api/id")
@Slf4j
@CrossOrigin(origins = "*")
public class IdGeneratorController {

    /**
     * ID生成器核心服务
     * 负责处理ID生成的核心业务逻辑
     * 包括号段管理、分片策略、缓存机制等功能
     * 通过Spring依赖注入自动装配
     */
    @Autowired
    private IdGeneratorService idGeneratorService;

    /**
     * 生成ID
     */
    @PostMapping("/generate")
    public ResponseEntity<Map<String, Object>> generateIds(@Valid @RequestBody IdRequest request) {
        try {
            log.info("收到ID生成请求: {}", request);
            
            IdResponse response = idGeneratorService.generateIds(request);
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("data", response);
            result.put("message", "ID生成成功");
            
            log.info("ID生成成功: 业务类型={}, 数量={}, 分片类型={}", 
                    response.getBusinessType(), response.getIdCount(), response.getShardTypeDesc());
            
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("ID生成失败", e);
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "ID生成失败: " + e.getMessage());
            result.put("error", e.getClass().getSimpleName());
            
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * 批量生成ID（简化接口）
     */
    @GetMapping("/generate/{businessType}")
    public ResponseEntity<Map<String, Object>> generateIdsByPath(
            @PathVariable String businessType,
            @RequestParam(defaultValue = "1") Integer count,
            @RequestParam(required = false) String timeKey,
            @RequestParam(defaultValue = "false") Boolean includeRouting,
            @RequestParam(required = false) Integer shardDbCount,
            @RequestParam(required = false) Integer shardTableCount) {
        
        IdRequest request = IdRequest.builder()
                .businessType(businessType)
                .timeKey(timeKey)
                .count(count)
                .includeRouting(includeRouting)
                .shardDbCount(shardDbCount)
                .shardTableCount(shardTableCount)
                .build();
        
        return generateIds(request);
    }

    /**
     * 获取单个ID（最简接口）
     */
    @GetMapping("/single/{businessType}")
    public ResponseEntity<Map<String, Object>> getSingleId(@PathVariable String businessType) {
        IdRequest request = IdRequest.builder()
                .businessType(businessType)
                .count(1)
                .build();
        
        try {
            IdResponse response = idGeneratorService.generateIds(request);
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("id", response.getFirstId());
            result.put("businessType", response.getBusinessType());
            result.put("timeKey", response.getTimeKey());
            result.put("shardType", response.getShardTypeDesc());
            result.put("serverId", response.getServerId());
            
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("单个ID生成失败", e);
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "ID生成失败: " + e.getMessage());
            
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * 获取服务器状态
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getServerStatus() {
        try {
            Map<String, Object> status = idGeneratorService.getServerStatus();
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("data", status);
            result.put("message", "获取状态成功");
            
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("获取服务器状态失败", e);
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "获取状态失败: " + e.getMessage());
            
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * 健康检查
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "UP");
        result.put("service", "ID Generator");
        result.put("timestamp", System.currentTimeMillis());
        
        return ResponseEntity.ok(result);
    }

    /**
     * 清理过期号段
     */
    @DeleteMapping("/segments/expired/{timeKey}")
    public ResponseEntity<Map<String, Object>> cleanExpiredSegments(@PathVariable String timeKey) {
        try {
            int deletedCount = idGeneratorService.cleanExpiredSegments(timeKey);
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("deletedCount", deletedCount);
            result.put("message", "清理完成");
            
            log.info("清理过期号段完成，删除数量: {}", deletedCount);
            
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("清理过期号段失败", e);
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "清理失败: " + e.getMessage());
            
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * 步长变更管理接口
     * 
     * @param businessType 业务类型
     * @param timeKey 时间键（可选，不指定则影响该业务类型的所有时间键）
     * @param newStepSize 新的步长值
     * @param preview 是否仅预览影响范围（不实际执行变更）
     * @return 变更结果
     */
    @PostMapping("/admin/step-size/change")
    public ResponseEntity<Map<String, Object>> changeStepSize(
            @RequestParam String businessType,
            @RequestParam(required = false) String timeKey,
            @RequestParam Integer newStepSize,
            @RequestParam(defaultValue = "false") Boolean preview) {
        
        try {
            Map<String, Object> result = idGeneratorService.changeStepSize(
                    businessType, timeKey, newStepSize, preview);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "步长变更失败: " + e.getMessage());
            error.put("timestamp", System.currentTimeMillis());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    /**
     * 查询当前步长配置
     * 
     * @param businessType 业务类型（可选）
     * @return 步长配置信息
     */
    @GetMapping("/admin/step-size/current")
    public ResponseEntity<Map<String, Object>> getCurrentStepSize(
            @RequestParam(required = false) String businessType) {
        
        try {
            Map<String, Object> result = idGeneratorService.getCurrentStepSizeInfo(businessType);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "查询步长配置失败: " + e.getMessage());
            error.put("timestamp", System.currentTimeMillis());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }
    
    // ==================== 步长同步管理接口 ====================
    
    /**
     * 强制所有服务器使用相同的新步长
     * 这是一个强制同步操作，会更新数据库中所有相关号段的步长，并清理所有服务器的内存缓存
     * 
     * @param businessType 业务类型（可选，不指定则更新所有业务类型）
     * @param newStepSize 新的步长值
     * @param preview 是否仅预览影响范围（不实际执行变更）
     * @return 强制同步结果
     */
    @PostMapping("/admin/step-size/force-sync")
    public ResponseEntity<Map<String, Object>> forceGlobalStepSizeSync(
            @RequestParam(required = false) String businessType,
            @RequestParam Integer newStepSize,
            @RequestParam(defaultValue = "false") Boolean preview) {
        
        try {
            Map<String, Object> result = idGeneratorService.forceGlobalStepSizeSync(
                    businessType, newStepSize, preview);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("强制步长同步失败", e);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "强制步长同步失败: " + e.getMessage());
            error.put("timestamp", System.currentTimeMillis());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }
    
    /**
     * 检查步长一致性
     * 检查指定业务类型下所有分片是否使用相同的步长
     * 
     * @param businessType 业务类型
     * @return 一致性检查结果
     */
    @GetMapping("/admin/step-size/consistency-check")
    public ResponseEntity<Map<String, Object>> checkStepSizeConsistency(
            @RequestParam String businessType) {
        
        try {
            Map<String, Object> result = idGeneratorService.checkStepSizeConsistency(businessType);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("检查步长一致性失败", e);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "检查步长一致性失败: " + e.getMessage());
            error.put("timestamp", System.currentTimeMillis());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }
    
    /**
     * 获取全局步长一致性报告
     * 检查所有业务类型的步长一致性状况
     * 
     * @return 全局步长一致性报告
     */
    @GetMapping("/admin/step-size/global-consistency-report")
    public ResponseEntity<Map<String, Object>> getGlobalStepSizeConsistencyReport() {
        
        try {
            Map<String, Object> result = idGeneratorService.getGlobalStepSizeConsistencyReport();
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("获取全局步长一致性报告失败", e);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "获取全局步长一致性报告失败: " + e.getMessage());
            error.put("timestamp", System.currentTimeMillis());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }
}