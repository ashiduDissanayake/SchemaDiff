package com.wso2.migration.checker.core.containers;

import com.wso2.migration.checker.core.ContainerManager;
import org.testcontainers.utility.DockerImageName;

public class OracleContainer extends ContainerManager {
    private org.testcontainers.containers.OracleContainer container;

    @Override
    public void start() {
        container = new org.testcontainers.containers.OracleContainer(
                DockerImageName.parse("gvenzl/oracle-xe:latest").asCompatibleSubstituteFor("gvenzl/oracle-xe"))
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