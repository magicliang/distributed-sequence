package com.distributed.idgenerator.controller;

import com.distributed.idgenerator.dto.IdRequest;
import com.distributed.idgenerator.dto.IdResponse;
import com.distributed.idgenerator.service.IdGeneratorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
}