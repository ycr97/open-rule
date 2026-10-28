package io.openrule.studio.host.fact;

import io.openrule.core.studio.StudioEngine;
import io.openrule.spring.fact.*;
import io.openrule.spring.studio.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration(proxyBeanMethods = false)
public class FactHostConfiguration {
    @Bean public FactDocuments factDocuments(StudioDocumentCodec codec) { return new FactDocuments(codec); }
    @Bean public JdbcFactStore jdbcFactStore(JdbcTemplate jdbc, PlatformTransactionManager manager, StudioDocumentCodec codec, FactDocuments documents) { return new JdbcFactStore(jdbc, manager, codec, documents); }
    @Bean(destroyMethod = "shutdownNow") public ExecutorService studioFactWorkers() { return Executors.newVirtualThreadPerTaskExecutor(); }
    @Bean public SecretResolver studioFactSecrets() { return new EnvironmentSecretResolver(); }
    @Bean public FactResolver studioFactResolver(@Qualifier("studioFactWorkers") ExecutorService workers, SecretResolver secrets,
                                                @Value("${studio.facts.max-in-flight:64}") int maxInFlight) {
        return new FactResolver(workers, List.of(new HttpFactProvider(secrets), new MockFactProvider()), maxInFlight);
    }
    @Bean public FactService factService(JdbcFactStore store, FactDocuments documents, StudioService flows, StudioDocumentCodec codec,
                                        StudioEngine engine, FactResolver resolver,
                                        @Value("${openrule.studio.max-timeout-millis:30000}") long timeout) {
        return new FactService(store, documents, flows, codec, engine, resolver, timeout);
    }
}
