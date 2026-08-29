package com.velo.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private Jwt jwt = new Jwt();
    private Admin admin = new Admin();
    private Cors cors = new Cors();

    @Data
    public static class Jwt {
        private String secret;
        private Duration accessTokenTtl = Duration.ofMinutes(30);
        private Duration refreshTokenTtl = Duration.ofDays(30);
    }

    @Data
    public static class Admin {
        private String email;
        private String password;
        private String fullName;
    }

    @Data
    public static class Cors {
        private List<String> allowedOrigins = List.of();
    }
}
