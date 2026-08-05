import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;

@Configuration
@EnableConfigurationProperties(KafkaProperties.class)
public class KafkaConsumerConfig {

    @Bean
    public ConsumerFactory<String, Object> consumerFactory(
            KafkaProperties kafkaProperties
    ) {
        return new DefaultKafkaConsumerFactory<>(
                kafkaProperties.buildConsumerProperties()
        );
    }
}
