package io.openrule.spring.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** OpenRule 配置项。 */
@ConfigurationProperties(prefix = "openrule")
public class OpenRuleProperties {

    private final FlowCache flowCache = new FlowCache();

    public FlowCache getFlowCache() { return flowCache; }

    public static class FlowCache {
        /** CompiledFlow 缓存上限。 */
        private long maximumSize = 500;
        /** 访问后多久过期。 */
        private Duration expireAfterAccess = Duration.ofHours(2);

        public long getMaximumSize() { return maximumSize; }
        public void setMaximumSize(long maximumSize) { this.maximumSize = maximumSize; }
        public Duration getExpireAfterAccess() { return expireAfterAccess; }
        public void setExpireAfterAccess(Duration v) { this.expireAfterAccess = v; }
    }
}
