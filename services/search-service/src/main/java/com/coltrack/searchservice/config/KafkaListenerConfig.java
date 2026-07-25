package com.coltrack.searchservice.config;


import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;

import org.springframework.kafka.listener.DefaultErrorHandler;


@Configuration
public class KafkaListenerConfig {


    @Bean
    public ConcurrentKafkaListenerContainerFactory<String,Object>
    kafkaListenerContainerFactory(
            ConsumerFactory<String,Object> consumerFactory,
            DefaultErrorHandler errorHandler
    ) {


        var factory =
                new ConcurrentKafkaListenerContainerFactory<String,Object>();


        factory.setConsumerFactory(
                consumerFactory
        );


        factory.setCommonErrorHandler(
                errorHandler
        );


        return factory;
    }
}
