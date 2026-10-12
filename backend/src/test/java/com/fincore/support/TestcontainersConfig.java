package com.fincore.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real infrastructure for integration tests. Containers are JVM-wide singletons (started once,
 * stopped by Testcontainers' Ryuk at JVM exit) so that several Spring test contexts can share them.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfig {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

    static {
        POSTGRES.start();
    }

    @Bean(destroyMethod = "")
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return POSTGRES;
    }
}
