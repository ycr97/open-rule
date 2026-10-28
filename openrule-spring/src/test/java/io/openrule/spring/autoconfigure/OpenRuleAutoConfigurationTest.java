package io.openrule.spring.autoconfigure;

import io.openrule.core.aggregate.FirstTerminalAggregator;
import io.openrule.core.compiler.FlowCompiler;
import io.openrule.core.runtime.NodeRunner;
import io.openrule.spring.port.FlowDefinitionRepository;
import io.openrule.spring.service.OpenRuleService;
import io.openrule.spring.standalone.InMemoryFlowDefinitionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;

class OpenRuleAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    JacksonAutoConfiguration.class, OpenRuleAutoConfiguration.class));

    @Test
    void wiresCoreBeansAndStandaloneAdapters() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(OpenRuleService.class);
            assertThat(ctx).hasSingleBean(FlowCompiler.class);
            assertThat(ctx).hasSingleBean(NodeRunner.class);
            assertThat(ctx).hasSingleBean(FirstTerminalAggregator.class);
            assertThat(ctx).hasSingleBean(FlowDefinitionRepository.class);
            assertThat(ctx.getBean(FlowDefinitionRepository.class))
                    .isInstanceOf(InMemoryFlowDefinitionRepository.class);
        });
    }

    @Test
    void backsOffWhenUserProvidesRepository() {
        runner.withUserConfiguration(CustomRepoConfig.class).run(ctx -> {
            assertThat(ctx).hasSingleBean(FlowDefinitionRepository.class);
            assertThat(ctx.getBean(FlowDefinitionRepository.class))
                    .isNotInstanceOf(InMemoryFlowDefinitionRepository.class);
        });
    }

    @Configuration
    static class CustomRepoConfig {
        @Bean
        FlowDefinitionRepository customRepo() {
            return new FlowDefinitionRepository() {
                public Optional<io.openrule.core.definition.FlowDefinition> findActiveByFlowId(String f) { return Optional.empty(); }
                public Optional<io.openrule.core.definition.FlowDefinition> findByFlowIdAndVersion(String f, int v) { return Optional.empty(); }
                public io.openrule.core.definition.FlowDefinition save(io.openrule.core.definition.FlowDefinition d) { return d; }
                public void enable(String f, int v) {}
                public List<Integer> listVersions(String f) { return List.of(); }
                public List<io.openrule.core.definition.FlowDefinition> findAllVersions(String f) { return List.of(); }
            };
        }
    }
}
