package io.openrule.jdbc.autoconfigure;

import io.openrule.jdbc.audit.JdbcExecutionLogQuery;
import io.openrule.jdbc.audit.JdbcExecutionLogger;
import io.openrule.jdbc.repository.JdbcFlowDefinitionRepository;
import io.openrule.spring.autoconfigure.OpenRuleAutoConfiguration;
import io.openrule.spring.port.ExecutionLogQuery;
import io.openrule.spring.port.ExecutionLogger;
import io.openrule.spring.port.FlowDefinitionRepository;
import io.openrule.spring.standalone.InMemoryExecutionLogger;
import io.openrule.spring.standalone.InMemoryFlowDefinitionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

class OpenRuleJdbcAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    JacksonAutoConfiguration.class,
                    DataSourceAutoConfiguration.class,
                    DataSourceTransactionManagerAutoConfiguration.class,
                    OpenRuleAutoConfiguration.class,
                    OpenRuleJdbcAutoConfiguration.class));

    @Test
    void withDataSource_jdbcAdaptersOverrideInMemory() {
        runner.withPropertyValues(
                "spring.datasource.url=jdbc:h2:mem:t;MODE=MySQL",
                "spring.datasource.driver-class-name=org.h2.Driver").run(ctx -> {
            assertThat(ctx.getBean(FlowDefinitionRepository.class))
                    .isInstanceOf(JdbcFlowDefinitionRepository.class);
            assertThat(ctx.getBean(ExecutionLogger.class)).isInstanceOf(JdbcExecutionLogger.class);
            assertThat(ctx.getBean(ExecutionLogQuery.class)).isInstanceOf(JdbcExecutionLogQuery.class);
            assertThat(ctx).doesNotHaveBean(InMemoryFlowDefinitionRepository.class);
        });
    }

    @Test
    void withoutDataSource_staysInMemory() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        JacksonAutoConfiguration.class,
                        OpenRuleAutoConfiguration.class,
                        OpenRuleJdbcAutoConfiguration.class))
                .run(ctx -> {
                    assertThat(ctx.getBean(FlowDefinitionRepository.class))
                            .isInstanceOf(InMemoryFlowDefinitionRepository.class);
                    assertThat(ctx.getBean(ExecutionLogger.class))
                            .isInstanceOf(InMemoryExecutionLogger.class);
                });
    }
}
