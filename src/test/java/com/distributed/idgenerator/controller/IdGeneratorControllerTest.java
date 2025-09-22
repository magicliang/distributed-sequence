package com.distributed.idgenerator.controller;

import com.distributed.idgenerator.dto.IdRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;

/**
 * ID生成器控制器测试
 * 
 * @author System
 * @version 1.0.0
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("h2")
@Transactional
class IdGeneratorControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void testGenerateIds() throws Exception {
        IdRequest request = IdRequest.builder()
                .businessType("test_order")
                .count(5)
                .build();

        mockMvc.perform(post("/api/id/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.ids").isArray())
                .andExpect(jsonPath("$.data.ids", hasSize(5)))
                .andExpect(jsonPath("$.data.businessType").value("test_order"))
                .andExpect(jsonPath("$.data.serverId").exists())
                .andExpect(jsonPath("$.data.shardType").exists())
                .andExpect(jsonPath("$.message").value("ID生成成功"));
    }

    @Test
    void testGenerateIdsByPath() throws Exception {
        mockMvc.perform(get("/api/id/generate/test_product")
                .param("count", "3")
                .param("includeRouting", "true")
                .param("shardDbCount", "4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.ids").isArray())
                .andExpect(jsonPath("$.data.ids", hasSize(3)))
                .andExpect(jsonPath("$.data.businessType").value("test_product"))
                .andExpect(jsonPath("$.data.routingInfo").exists())
                .andExpect(jsonPath("$.data.routingInfo.shardDbCount").value(4));
    }

    @Test
    void testGetSingleId() throws Exception {
        mockMvc.perform(get("/api/id/single/test_user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.businessType").value("test_user"))
                .andExpect(jsonPath("$.shardType").exists())
                .andExpect(jsonPath("$.serverId").exists());
    }

    @Test
    void testGetServerStatus() throws Exception {
        mockMvc.perform(get("/api/id/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.serverId").exists())
                .andExpect(jsonPath("$.data.serverType").exists())
                .andExpect(jsonPath("$.data.serverTypeDesc").exists())
                .andExpect(jsonPath("$.data.segmentBufferCount").exists())
                .andExpect(jsonPath("$.data.timestamp").exists());
    }

    @Test
    void testHealthCheck() throws Exception {
        mockMvc.perform(get("/api/id/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("ID Generator"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void testCleanExpiredSegments() throws Exception {
        // 先生成一些数据
        IdRequest request = IdRequest.builder()
                .businessType("test_cleanup")
                .timeKey("20231101")
                .count(1)
                .build();

        mockMvc.perform(post("/api/id/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        // 清理过期数据
        mockMvc.perform(delete("/api/id/segments/expired/20231130"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.deletedCount").exists())
                .andExpect(jsonPath("$.message").value("清理完成"));
    }

    @Test
    void testInvalidRequest() throws Exception {
        IdRequest invalidRequest = IdRequest.builder()
                .businessType("") // 空业务类型
                .count(-1) // 无效数量
                .build();

        mockMvc.perform(post("/api/id/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void testConcurrentRequests() throws Exception {
        // 模拟并发请求
        String businessType = "concurrent_test";
        
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(get("/api/id/single/" + businessType))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.id").exists());
        }
    }

    @Test
    void testRoutingRequest() throws Exception {
        IdRequest request = IdRequest.builder()
                .businessType("routing_test")
                .count(1)
                .includeRouting(true)
                .shardDbCount(8)
                .shardTableCount(16)
                .build();

        mockMvc.perform(post("/api/id/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.routingInfo").exists())
                .andExpect(jsonPath("$.data.routingInfo.dbIndex").exists())
                .andExpect(jsonPath("$.data.routingInfo.tableIndex").exists())
                .andExpect(jsonPath("$.data.routingInfo.shardDbCount").value(8))
                .andExpect(jsonPath("$.data.routingInfo.shardTableCount").value(16));
    }

    @Test
    void testCustomStepSize() throws Exception {
        IdRequest request = IdRequest.builder()
                .businessType("custom_step_test")
                .count(1)
                .customStepSize(2000)
                .build();

        mockMvc.perform(post("/api/id/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.ids").isArray())
                .andExpect(jsonPath("$.data.ids", hasSize(1)));
    }

    @Test
    void testForceShardType() throws Exception {
        // 测试强制奇数分片
        IdRequest oddRequest = IdRequest.builder()
                .businessType("force_shard_test")
                .count(5)
                .forceShardType(1)
                .build();

        mockMvc.perform(post("/api/id/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(oddRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.shardType").value(1));

        // 测试强制偶数分片
        IdRequest evenRequest = IdRequest.builder()
                .businessType("force_shard_test_2")
                .count(5)
                .forceShardType(0)
                .build();

        mockMvc.perform(post("/api/id/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(evenRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.shardType").value(0));
    }
}