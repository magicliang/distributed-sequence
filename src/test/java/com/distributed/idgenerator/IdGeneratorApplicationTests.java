package com.distributed.idgenerator;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 应用程序测试
 * 
 * @author System
 * @version 1.0.0
 */
@SpringBootTest
@ActiveProfiles("h2")
class IdGeneratorApplicationTests {

    @Test
    void contextLoads() {
        // 测试Spring上下文是否能正常加载
    }
}