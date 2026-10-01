package com.example.shortlink.stats;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(VisitStatsProperties.class)
public class StatsDataSourceConfiguration {
    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    HikariDataSource dataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean(name = "statsDataSource", destroyMethod = "close")
    HikariDataSource statsDataSource(DataSourceProperties database, VisitStatsProperties properties) {
        HikariDataSource pool = database.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        pool.setPoolName("visit-statistics");
        pool.setMaximumPoolSize(4);
        pool.setMinimumIdle(0);
        pool.setInitializationFailTimeout(-1);
        pool.setConnectionTimeout(properties.connectionTimeoutMs());
        pool.setValidationTimeout(properties.validationTimeoutMs());
        pool.addDataSourceProperty("connectTimeout", properties.connectTimeoutMs());
        pool.addDataSourceProperty("socketTimeout", properties.socketTimeoutMs());
        pool.addDataSourceProperty("connectionTimeZone", "UTC");
        pool.addDataSourceProperty("forceConnectionTimeZoneToSession", true);
        pool.setConnectionInitSql("SET SESSION innodb_lock_wait_timeout=" + properties.lockTimeoutSeconds());
        return pool;
    }
}
