package io.openrule.spring.port;

import io.openrule.core.definition.FlowDefinition;

import java.util.List;
import java.util.Optional;

/** 流程定义仓储端口。standalone=内存；M2b=MySQL（ycr data-mp）。 */
public interface FlowDefinitionRepository {

    Optional<FlowDefinition> findActiveByFlowId(String flowId);

    Optional<FlowDefinition> findByFlowIdAndVersion(String flowId, int version);

    /** 保存为新版本：版本号自增、置为唯一 enabled，旧版本转非启用。返回带最终 version 的定义。 */
    FlowDefinition save(FlowDefinition def);

    void enable(String flowId, int version);

    List<Integer> listVersions(String flowId);
}
