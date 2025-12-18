package com.wso2.migration.checker.core;

public abstract class ContainerManager {
    public abstract void start();
    public abstract void stop();
    public abstract String getJdbcUrl();
    public abstract String getUsername();
    public abstract String getPassword();
}