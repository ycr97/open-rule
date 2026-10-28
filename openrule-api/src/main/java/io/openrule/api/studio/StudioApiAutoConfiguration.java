package io.openrule.api.studio;

import io.openrule.spring.studio.StudioAutoConfiguration;
import io.openrule.spring.studio.StudioDocumentCodec;
import io.openrule.spring.studio.StudioService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(after = StudioAutoConfiguration.class)
@ConditionalOnBean(StudioService.class)
public class StudioApiAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public StudioController studioController(StudioService service, StudioDocumentCodec codec) {
        return new StudioController(service, codec);
    }

    @Bean
    @ConditionalOnMissingBean
    public StudioProblemAdvice studioProblemAdvice() { return new StudioProblemAdvice(); }
}
