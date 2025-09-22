package com.distributed.idgenerator.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * 数据库配置
 * 
 * @author System
 * @version 1.0.0
 */
@Configuration
@Slf4j
public class DatabaseConfig {

    /**
     * 数据源连接URL
     * 从配置文件spring.datasource.url读取
     * MySQL示例：jdbc:mysql://localhost:3306/idgenerator?useUnicode=true&characterEncoding=utf8&useSSL=false
     * 如果未配置则为空字符串
     */
    @Value("${spring.datasource.url:}")
    private String datasourceUrl;

    /**
     * 数据库用户名
     * 从配置文件spring.datasource.username读取
     * 用于数据库连接认证
     * 如果未配置则为空字符串
     */
    @Value("${spring.datasource.username:}")
    private String datasourceUsername;

    /**
     * 数据库密码
     * 从配置文件spring.datasource.password读取
     * 用于数据库连接认证
     * 如果未配置则为空字符串
     */
    @Value("${spring.datasource.password:}")
    private String datasourcePassword;

    /**
     * 数据库驱动类名
     * 从配置文件spring.datasource.driver-class-name读取
     * MySQL驱动：com.mysql.cj.jdbc.Driver
     * H2驱动：org.h2.Driver
     * 如果未配置则为空字符串
     */
    @Value("${spring.datasource.driver-class-name:}")
    private String driverClassName;

    /**
     * MySQL数据源配置
     */
    @Bean
    @Primary
    @ConditionalOnProperty(name = "spring.profiles.active", havingValue = "mysql", matchIfMissing = false)
    public DataSource mysqlDataSource() {
        log.info("配置MySQL数据源");
        
        return DataSourceBuilder.create()
                .url(datasourceUrl)
                .username(datasourceUsername)
                .password(datasourcePassword)
                .driverClassName("com.mysql.cj.jdbc.Driver")
                .build();
    }

    /**
     * H2数据源配置（默认）
     */
    @Bean
    @Primary
    @ConditionalOnProperty(name = "spring.profiles.active", havingValue = "h2", matchIfMissing = true)
    public DataSource h2DataSource() {
        log.info("配置H2数据源");
        
        return DataSourceBuilder.create()
                .url("jdbc:h2:mem:idgenerator;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE")
                .username("sa")
                .password("")
                .driverClassName("org.h2.Driver")
                .build();
    }
}