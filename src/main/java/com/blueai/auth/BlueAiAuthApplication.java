package com.blueai.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = "com.blueai")
@ConfigurationPropertiesScan(basePackages = "com.blueai")
@EntityScan(basePackages = "com.blueai")
@EnableJpaRepositories(basePackages = "com.blueai")
public class BlueAiAuthApplication {

    public static void main(String[] args) {
        SpringApplication.run(BlueAiAuthApplication.class, args);
    }
}
