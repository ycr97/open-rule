package io.openrule.spring.studio;

import io.openrule.core.studio.StudioEngine;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Value;

import java.util.concurrent.ExecutorService;

@AutoConfiguration(afterName = "io.openrule.jdbc.autoconfigure.OpenRuleJdbcAutoConfiguration")
public class StudioAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public StudioDocumentCodec studioDocumentCodec() { return new StudioDocumentCodec(); }

    @Bean
    @ConditionalOnMissingBean
    public StudioEngine studioEngine(@Qualifier("openRuleTimeoutPool") ExecutorService pool) {
        return new StudioEngine(pool);
    }

    @Bean
    @ConditionalOnBean(StudioStore.class)
    @ConditionalOnMissingBean
    public StudioService studioService(StudioStore store, StudioDocumentCodec codec, StudioEngine engine,
                                       @Value("${openrule.studio.max-timeout-millis:30000}") long maxTimeoutMillis) {
        return new StudioService(store, codec, engine, maxTimeoutMillis);
    }
}
