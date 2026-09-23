package org.openphc.cce.compliance.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.openphc.cce.common.kafka.KafkaTopicProperties;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka wiring for the compliance plane: <strong>produce-only</strong>.
 *
 * <p>This service's work arrives by polling {@code step_sla_state_transition}, not from a topic, so
 * there is deliberately no consumer factory, no listener container, no error handler and no DLQ here —
 * and nothing to retry, which is why {@code cce.kafka.retry.*} goes unused by this service.
 * {@code KafkaConfigTest} asserts their absence, so dead inbound wiring cannot creep back in.
 */
@Configuration
public class KafkaConfig {

    @Bean
    public ProducerFactory<String, Object> producerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildProducerProperties(null));
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        props.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<String, Object> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    // ── Topic declarations ──

    @Bean
    public NewTopic intelligenceTriggersTopic(KafkaTopicProperties topicProperties) {
        return TopicBuilder.name(topicProperties.getIntelligenceTriggers())
                .partitions(topicProperties.getDefaultPartitions())
                .build();
    }
}
