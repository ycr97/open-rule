package io.openrule.studio.host.fact;

import io.openrule.spring.fact.SecretResolver;

/** Only process environment references are accepted; values never enter managed configuration. */
public final class EnvironmentSecretResolver implements SecretResolver {
    @Override public String resolve(String reference) {
        if (reference == null || !reference.matches("env:OPENRULE_[A-Z0-9_]+")) return null;
        return System.getenv(reference.substring("env:".length()));
    }
}
