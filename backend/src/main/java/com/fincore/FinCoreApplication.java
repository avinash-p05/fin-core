package com.fincore;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class FinCoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(FinCoreApplication.class, args);
    }
}
