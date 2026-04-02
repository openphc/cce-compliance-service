package org.openphc.cce.compliance.kafka.consumer;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.openphc.cce.compliance.kafka.model.CloudEventMessage;
import org.openphc.cce.compliance.service.ComplianceEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka consumer for inbound clinical events from the Collector Service.
 * Delegates to ComplianceEngine for processing. Offset is committed automatically
 * per record on success. On failure, exceptions propagate to the container's
 * DefaultErrorHandler which retries with backoff and routes to DLQ after exhausting retries.
 */
@Component
public class InboundEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(InboundEventConsumer.class);

    private final ComplianceEngine complianceEngine;
    private final Counter errorCounter;

    public InboundEventConsumer(ComplianceEngine complianceEngine, MeterRegistry meterRegistry) {
        this.complianceEngine = complianceEngine;
        this.errorCounter = meterRegistry.counter("cce.consumer.inbound.errors");
    }

    @KafkaListener(topics = "${cce.kafka.topics.inbound-events}")
    public void consume(CloudEventMessage event) {
        if (event.getCorrelationid() != null) MDC.put("correlationId", event.getCorrelationid());
        if (event.getSource() != null) MDC.put("source", event.getSource());
        if (event.getType() != null) MDC.put("eventType", event.getType());
        if (event.getSubject() != null) MDC.put("subject", event.getSubject());
        try {
            log.debug("Received inbound event: cloudeventsId={}, source={}", event.getId(), event.getSource());
            complianceEngine.processInboundEvent(event);
        } catch (Exception e) {
            errorCounter.increment();
            throw e; // Propagate to DefaultErrorHandler for retry + DLQ
        } finally {
            MDC.clear();
        }
    }
}
