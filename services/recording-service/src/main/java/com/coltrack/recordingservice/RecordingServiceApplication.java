package com.coltrack.recordingservice;

import com.coltrack.recordingservice.config.RecordingStoragePolicyProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableJpaRepositories(
        basePackages = "com.coltrack.recordingservice.repository"
)
@EnableScheduling
@EnableConfigurationProperties(RecordingStoragePolicyProperties.class)
public class RecordingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(RecordingServiceApplication.class, args);
    }

}
