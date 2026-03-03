package com.lyh.aiagent.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * 多数据源配置
 * MySQL 为主数据源（MyBatis-Plus 使用），PgVector 为独立数据源。
 */
@Configuration
public class DataSourceConfig {

    @Primary
    @Bean("mysqlDataSource")
    public DataSource mysqlDataSource(
            @Value("${spring.datasource.url}") String url,
            @Value("${spring.datasource.username}") String username,
            @Value("${spring.datasource.password}") String password,
            @Value("${spring.datasource.driver-class-name}") String driverClassName) {
        return DataSourceBuilder.create()
                .url(url)
                .username(username)
                .password(password)
                .driverClassName(driverClassName)
                .build();
    }

    @Bean("pgVectorDataSource")
    public DataSource pgVectorDataSource(
            @Value("${spring.ai.vectorstore.pgvector.datasource.url}") String url,
            @Value("${spring.ai.vectorstore.pgvector.datasource.username}") String username,
            @Value("${spring.ai.vectorstore.pgvector.datasource.password}") String password) {
        return DataSourceBuilder.create()
                .url(url)
                .username(username)
                .password(password)
                .build();
    }

    @Bean("pgVectorJdbcTemplate")
    public JdbcTemplate pgVectorJdbcTemplate(
            @org.springframework.beans.factory.annotation.Qualifier("pgVectorDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
