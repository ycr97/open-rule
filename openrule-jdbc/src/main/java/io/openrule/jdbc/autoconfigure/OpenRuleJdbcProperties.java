package io.openrule.jdbc.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** 审计配置（脱敏 key 等）。 */
@ConfigurationProperties(prefix = "openrule.audit")
public class OpenRuleJdbcProperties {

    /** facts 快照脱敏的字段名。 */
    private List<String> factsDesensitizeKeys =
            List.of("mobile", "idCard", "bankCard", "password");

    public List<String> getFactsDesensitizeKeys() { return factsDesensitizeKeys; }
    public void setFactsDesensitizeKeys(List<String> v) { this.factsDesensitizeKeys = v; }
}
