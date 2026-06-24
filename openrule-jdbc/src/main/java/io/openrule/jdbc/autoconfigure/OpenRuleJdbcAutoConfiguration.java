package io.openrule.jdbc.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.openrule.jdbc.audit.JdbcExecutionLogQuery;
import io.openrule.jdbc.audit.JdbcExecutionLogger;
import io.openrule.jdbc.repository.JdbcFlowDefinitionRepository;
import io.openrule.spring.autoconfigure.OpenRuleAutoConfiguration;
import io.openrule.spring.loader.FlowDefinitionJsonCodec;
import io.openrule.spring.port.ExecutionLogQuery;
import io.openrule.spring.port.ExecutionLogger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * JDBC 持久化装配：有 DataSource 时覆盖内存端口实现。
 * 须在 OpenRuleAutoConfiguration 之前注册，令其 @ConditionalOnMissingBean 内存实现退让；
 * 须在 DataSourceAutoConfiguration 之后，以便 @ConditionalOnBean(DataSource) 能看到数据源。
 */
@AutoConfiguration(after = DataSourceAutoConfiguration.class, before = OpenRuleAutoConfiguration.class)
@EnableConfigurationProperties(OpenRuleJdbcProperties.class)
@ConditionalOnBean(DataSource.class)
public class OpenRuleJdbcAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public JdbcFlowDefinitionRepository jdbcFlowDefinitionRepository(
            DataSource dataSource, PlatformTransactionManager txm, FlowDefinitionJsonCodec codec) {
        return new JdbcFlowDefinitionRepository(new JdbcTemplate(dataSource), txm, codec);
    }

    @Bean("openRuleAuditPool")
    @ConditionalOnMissingBean(name = "openRuleAuditPool")
    public Executor openRuleAuditPool() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    @ConditionalOnMissingBean(ExecutionLogger.class)
    public JdbcExecutionLogger jdbcExecutionLogger(DataSource dataSource,
            ObjectProvider<ObjectMapper> objectMapper, OpenRuleJdbcProperties props, Executor openRuleAuditPool) {
        Set<String> keys = new HashSet<>(props.getFactsDesensitizeKeys());
        return new JdbcExecutionLogger(new JdbcTemplate(dataSource),
                objectMapper.getIfAvailable(ObjectMapper::new), keys, openRuleAuditPool);
    }

    @Bean
    @ConditionalOnMissingBean(ExecutionLogQuery.class)
    public JdbcExecutionLogQuery jdbcExecutionLogQuery(DataSource dataSource) {
        return new JdbcExecutionLogQuery(new JdbcTemplate(dataSource));
    }
}
