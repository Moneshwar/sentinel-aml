package com.moneshwar.hackathon.stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "sentinel.streaming.publisher", havingValue = "in-memory", matchIfMissing = true)
public class InMemoryTransactionEventPublisher implements TransactionEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(InMemoryTransactionEventPublisher.class);

    private final ApplicationEventPublisher applicationEventPublisher;

    public InMemoryTransactionEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        this.applicationEventPublisher = applicationEventPublisher;
    }

    @Override
    public void publish(TransactionIngestedEvent event) {
        log.debug("Publishing streaming transaction {}", event.transactionRef());
        applicationEventPublisher.publishEvent(event);
    }
}
