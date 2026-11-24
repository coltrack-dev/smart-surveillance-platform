package com.coltrack.streamservice;

import com.coltrack.identity.quota.TenantQuotaConfiguration;
import org.springframework.context.annotation.Import;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@Import(TenantQuotaConfiguration.class)
public class StreamServiceApplication {


    public static void main(String[] args) {

        SpringApplication.run(
                StreamServiceApplication.class,
                args
        );
    }
}
