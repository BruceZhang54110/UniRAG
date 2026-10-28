package com.unirag.infrastructure.embedding.store;


import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @author zhw
 */
@ConfigurationProperties(prefix = "embedding-store")
public class EmbeddingStoreProperties {

    private String host;

    private Integer port;

    private String database;

    private String user;

    private String password;

    private String table;

    private Integer dimension;

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public String getDatabase() {
        return database;
    }

    public void setDatabase(String database) {
        this.database = database;
    }

    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = user;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getTable() {
        return table;
    }

    public void setTable(String table) {
        this.table = table;
    }

    public Integer getDimension() {
        return dimension;
    }

    public void setDimension(Integer dimension) {
        this.dimension = dimension;
    }
}
