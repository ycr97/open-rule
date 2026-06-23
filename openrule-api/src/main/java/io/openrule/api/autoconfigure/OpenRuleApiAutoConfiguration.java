package io.openrule.api.autoconfigure;

import io.openrule.api.ExecuteController;
import io.openrule.api.FlowAdminController;
import io.openrule.api.advice.OpenRuleExceptionAdvice;
import io.openrule.spring.autoconfigure.OpenRuleAutoConfiguration;
import io.openrule.spring.loader.FlowDefinitionJsonCodec;
import io.openrule.spring.service.OpenRuleService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/** REST 装配：控制器经 @Bean 注册（不靠组件扫描，避免污染用户包）；异常 advice 条件化可退让。 */
@AutoConfiguration(after = OpenRuleAutoConfiguration.class)
public class OpenRuleApiAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ExecuteController executeController(OpenRuleService service) {
        return new ExecuteController(service);
    }

    @Bean
    @ConditionalOnMissingBean
    public FlowAdminController flowAdminController(OpenRuleService service, FlowDefinitionJsonCodec codec) {
        return new FlowAdminController(service, codec);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "openrule.api.exception-advice.enabled", havingValue = "true",
            matchIfMissing = true)
    public OpenRuleExceptionAdvice openRuleExceptionAdvice() {
        return new OpenRuleExceptionAdvice();
    }
}
