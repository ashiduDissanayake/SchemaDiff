package com.wso2.migration.checker.core.containers;

import com.wso2.migration.checker.core.ContainerManager;
import org.testcontainers.containers.PostgreSQLContainer;

public class PostgresContainer extends ContainerManager {
    private PostgreSQLContainer<?> container;

    @Override
    public void start() {
        container = new PostgreSQLContainer<>("postgres:15-alpine").withReuse(false);
        container.start();
    }

    @Override
    public void stop() {
        if (container != null) container.stop();
    }

    @Override
    public String getJdbcUrl() {
        return container.getJdbcUrl();
    }

    @Override
    public String getUsername() {
        return container.getUsername();
    }

    @Override
    public String getPassword() {
        return container.getPassword();
    }
}