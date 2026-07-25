package com.coltrack.searchservice;

import com.coltrack.kafka.KafkaErrorHandlerConfig;
import com.coltrack.kafka.KafkaProducerConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import({
        KafkaProducerConfig.class,
        KafkaErrorHandlerConfig.class
})

public class SearchServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SearchServiceApplication.class, args);
    }

}
