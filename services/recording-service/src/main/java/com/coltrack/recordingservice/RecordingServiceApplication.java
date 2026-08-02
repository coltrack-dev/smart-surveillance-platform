package com.coltrack.recordingservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@EnableJpaRepositories(
        basePackages = "com.coltrack.recordingservice.repository"
)
public class RecordingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(RecordingServiceApplication.class, args);
    }

}
