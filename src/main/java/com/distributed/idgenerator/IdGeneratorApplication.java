package com.distributed.idgenerator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 分布式ID生成器主启动类
 * 
 * @author System
 * @version 1.0.0
 */
@SpringBootApplication
@EnableScheduling
public class IdGeneratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(IdGeneratorApplication.class, args);
        System.out.println("=================================");
        System.out.println("分布式ID生成器启动成功！");
        System.out.println("访问地址: http://localhost:8080");
        System.out.println("健康检查: http://localhost:8080/actuator/health");
        System.out.println("API文档: http://localhost:8080/api/docs");
        System.out.println("=================================");
    }
}