package org.openphc.cce.compliance.kafka.producer;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.openphc.cce.compliance.config.KafkaTopicProperties;
import org.openphc.cce.compliance.kafka.model.IntelligenceTriggerEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes intelligence trigger events to the cce.intelligence.triggers Kafka topic.
 * Uses protocolInstanceId as the Kafka key for partition locality — all events for
 * a protocol instance go to the same partition.
 */
@Component
public class IntelligenceTriggerProducer {

    private static final Logger log = LoggerFactory.getLogger(IntelligenceTriggerProducer.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String topicName;
    private final Counter publishedCounter;
    private final Counter failedCounter;

    public IntelligenceTriggerProducer(KafkaTemplate<String, Object> kafkaTemplate,
                                       KafkaTopicProperties topicProperties,
                                       MeterRegistry meterRegistry) {
        this.kafkaTemplate = kafkaTemplate;
        this.topicName = topicProperties.getIntelligenceTriggers();
        this.publishedCounter = meterRegistry.counter("cce.events.intelligence.published");
        this.failedCounter = meterRegistry.counter("cce.events.intelligence.failed");
    }

    public void publish(IntelligenceTriggerEvent event) {
        String key = event.getProtocolInstanceId() != null
                ? event.getProtocolInstanceId().toString()
                : event.getId().toString();

        kafkaTemplate.send(topicName, key, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        failedCounter.increment();
                        log.error("Failed to publish intelligence trigger event: id={}, type={}, protocolInstanceId={}",
                                event.getId(), event.getType(), event.getProtocolInstanceId(), ex);
                    } else {
                        publishedCounter.increment();
                        log.info("Published intelligence trigger event: id={}, type={}, topic={}, partition={}",
                                event.getId(), event.getType(), topicName,
                                result.getRecordMetadata().partition());
                    }
                });
    }
}
