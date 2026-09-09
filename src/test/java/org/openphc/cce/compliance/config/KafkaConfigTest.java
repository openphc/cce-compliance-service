package org.openphc.cce.compliance.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.junit.jupiter.api.Test;
import org.openphc.cce.common.kafka.KafkaTopicProperties;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The compliance plane publishes intelligence triggers and consumes nothing — its work arrives by
 * polling {@code step_sla_state_transition}. These assertions are what stop a listener container or an
 * inbound topic creeping back in.
 */
class KafkaConfigTest {

    private final KafkaConfig config = new KafkaConfig();

    private KafkaProperties kafkaProperties() {
        KafkaProperties props = new KafkaProperties();
        props.setBootstrapServers(List.of("localhost:9092"));
        return props;
    }

    @Test
    void producerSerializesToJsonWithoutTypeInfoHeaders() {
        // Type headers off on purpose: the Intelligence Service deserializes into its own class, and
        // a __TypeId__ naming a class in this service's package would break that.
        // acks / enable.idempotence are set in application.yml, not here.
        var props = config.producerFactory(kafkaProperties()).getConfigurationProperties();

        assertEquals(StringSerializer.class, props.get(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG));
        assertEquals(JsonSerializer.class, props.get(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG));
        assertEquals(false, props.get(JsonSerializer.ADD_TYPE_INFO_HEADERS));
    }

    @Test
    void buildsAKafkaTemplateOverThatFactory() {
        ProducerFactory<String, Object> factory = config.producerFactory(kafkaProperties());

        KafkaTemplate<String, Object> template = config.kafkaTemplate(factory);

        assertSame(factory, template.getProducerFactory());
    }

    @Test
    void declaresOnlyTheIntelligenceTriggerTopic() {
        KafkaTopicProperties topics = new KafkaTopicProperties();
        topics.setIntelligenceTriggers("cce.intelligence.triggers");
        topics.setDefaultPartitions(25);

        NewTopic topic = config.intelligenceTriggersTopic(topics);

        assertEquals("cce.intelligence.triggers", topic.name());
        assertEquals(25, topic.numPartitions());
    }

    @Test
    void declaresNoConsumerOrInboundTopic() {
        List<String> beanMethods = java.util.Arrays.stream(KafkaConfig.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName).toList();

        assertFalse(beanMethods.contains("kafkaListenerContainerFactory"),
                "compliance consumes no topic — a listener container here would be dead wiring");
        assertFalse(beanMethods.contains("inboundEventsTopic"));
        assertFalse(beanMethods.contains("inboundEventsDlqTopic"));
    }
}
