package com.moneshwar.hackathon.stream;

/**
 * Transport abstraction for streaming transactions. The default implementation
 * publishes Spring application events in-process; a Kafka adapter can be wired
 * by providing another bean and switching {@code sentinel.streaming.publisher}.
 */
public interface TransactionEventPublisher {

    void publish(TransactionIngestedEvent event);
}
