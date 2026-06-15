package com.mall.ac;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AcDrainageApplication {
    public static void main(String[] args) {
        SpringApplication.run(AcDrainageApplication.class, args);
        System.out.println("""
            ============================================
               Mall AC Drainage Service Started!
               REST API: http://localhost:8080
               gRPC Server: localhost:9090
            ============================================
            """);
    }
}
