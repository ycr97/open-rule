package io.openrule.spring.standalone;

import io.openrule.spring.loader.FlowLoader;
import io.openrule.spring.port.FlowChangeNotifier;

/** 单进程：流程变更直接失效本地 FlowLoader 缓存（M2b 换 Redis Pub/Sub 跨实例广播）。 */
public class LocalFlowChangeNotifier implements FlowChangeNotifier {

    private final FlowLoader flowLoader;

    public LocalFlowChangeNotifier(FlowLoader flowLoader) {
        this.flowLoader = flowLoader;
    }

    @Override
    public void publishInvalidation(String flowId) {
        flowLoader.invalidate(flowId);
    }
}
