package com.coltrack.websocketservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
public class WebSocketServiceApplication {

    public static void main(String[] args) {

        SpringApplication.run(
                WebSocketServiceApplication.class,
                args
        );
    }
}
