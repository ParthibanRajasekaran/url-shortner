package org.example.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
@Data
public class AppProperties {

    private String baseUrl = "http://localhost:8080";
    private String ownHost = "localhost";
    private Snowflake snowflake = new Snowflake();
    private Redis redis = new Redis();

    @Data
    public static class Snowflake {
        private int workerId = 0;
    }

    @Data
    public static class Redis {
        private long urlTtlSeconds = 86400L;
        private long notFoundTtlSeconds = 300L;
    }
}
