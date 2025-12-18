package com.wso2.migration.checker.core.containers;

import com.wso2.migration.checker.core.ContainerManager;

public class MySQLContainer extends ContainerManager {
    private org.testcontainers.containers.MySQLContainer<?> container;

    @Override
    public void start() {
        container = new org.testcontainers.containers.MySQLContainer<>("mysql:8.0").withReuse(false);
        container.start();
    }

    @Override
    public void stop() {
        if (container != null) container.stop();
    }

    @Override
    public String getJdbcUrl() {
        String url = container.getJdbcUrl();
        if (!url.contains("allowMultiQueries=true")) {
            return url + (url.contains("?") ? "&" : "?") + "allowMultiQueries=true";
        }
        return url;
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