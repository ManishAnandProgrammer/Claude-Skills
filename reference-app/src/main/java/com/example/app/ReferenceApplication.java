package com.example.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ReferenceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReferenceApplication.class, args);
    }
}
