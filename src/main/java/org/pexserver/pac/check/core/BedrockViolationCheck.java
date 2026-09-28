package org.pexserver.pac.check.core;

/** Detector or reporter for violations produced by the Geyser prediction engine. */
public interface BedrockViolationCheck extends CheckModule {
    void inspect(BedrockViolationContext context);
}
