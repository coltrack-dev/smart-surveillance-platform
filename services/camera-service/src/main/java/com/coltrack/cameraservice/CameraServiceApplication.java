package com.coltrack.cameraservice;

import com.coltrack.kafka.KafkaErrorHandlerConfig;
//import com.coltrack.kafka.KafkaProducerConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@Import({
        //KafkaProducerConfig.class,
        KafkaErrorHandlerConfig.class
})
public class CameraServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CameraServiceApplication.class, args);
    }

}
