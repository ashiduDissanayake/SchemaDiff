package com.wso2.migration.checker.core.containers;

import com.wso2.migration.checker.core.ContainerManager;
import org.testcontainers.containers.Db2Container;

public class DB2Container extends ContainerManager {
    private Db2Container container;

    @Override
    public void start() {
        container = new Db2Container("icr.io/db2_community/db2:latest")
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