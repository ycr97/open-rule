package io.openrule.core.result;

import io.openrule.core.enums.Decision;
import io.openrule.core.enums.NodeType;
import lombok.Builder;
import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

/**
 * 节点统一输出。并行隔离的核心：并行节点所有写入封装在 outputs 返回，
 * 由 StageExecutor 单线程合并进 context.variables（C1/C2）。
 */
@Getter
@Builder(toBuilder = true)
public class NodeResult {

    private String   nodeId;
    private String   nodeName;
    private NodeType nodeType;

    private boolean  hit;
    private Decision decision;
    private int      score;
    private boolean  stop;
    private String   reason;

    @Builder.Default
    private Map<String, Object> outputs = new HashMap<>();
    @Builder.Default
    private Map<String, Object> details = new HashMap<>();

    private boolean success;
    private String  errorCode;
    private String  errorMessage;
    private long    costMillis;
    private boolean skipped;
}
