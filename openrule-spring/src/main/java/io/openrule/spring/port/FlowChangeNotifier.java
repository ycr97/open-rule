package io.openrule.spring.port;

/** 流程变更通知端口。standalone=本地直接失效缓存；M2b=Redis Pub/Sub。 */
public interface FlowChangeNotifier {
    void publishInvalidation(String flowId);
}
