package io.openrule.spring.fact;

/** A provider never returns a business decision or mutates the request. */
public interface FactProvider {
    String type();
    FactBatchResult resolve(FactBatchRequest request);
}
