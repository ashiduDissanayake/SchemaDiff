package com.wso2.migration.checker.core.containers;

import com.example.driftmaster. core.ContainerManager;
import org.testcontainers.containers.MySQLContainer;

public class MySQLContainer extends ContainerManager {
    private MySQLContainer<?> container;

    @Override
    public void start() {
        container = new MySQLContainer<>("mysql:8").withReuse(false);
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