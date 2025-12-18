package com.wso2.migration.checker.core.containers;

import com.example.driftmaster.core.ContainerManager;
import org.testcontainers.containers.MSSQLServerContainer;

public class MSSQLContainer extends ContainerManager {
    private MSSQLServerContainer<?> container;

    @Override
    public void start() {
        container = new MSSQLServerContainer<>("mcr.microsoft. com/mssql/server: 2022-latest")
                .acceptLicense()
                .withReuse(false);
        container.start();
    }

    @Override
    public void stop() {
        if (container != null) container.stop();
    }

    @Override
    public String getJdbcUrl() {
        return container. getJdbcUrl();
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