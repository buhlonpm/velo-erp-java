package com.velo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class VeloBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(VeloBackendApplication.class, args);
    }
}
