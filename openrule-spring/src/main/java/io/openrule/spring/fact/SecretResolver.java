package io.openrule.spring.fact;

/** Host-owned environment lookup. The reference is stored; the resolved value is never reported. */
@FunctionalInterface
public interface SecretResolver {
    String resolve(String secretRef);
}
