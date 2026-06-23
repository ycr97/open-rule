package io.openrule.spring.loader;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.openrule.core.definition.FlowDefinition;
import io.openrule.core.enums.NodeType;
import io.openrule.core.exception.FlowValidationException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlowDefinitionJsonCodecTest {

    private final FlowDefinitionJsonCodec codec = new FlowDefinitionJsonCodec(new ObjectMapper());

    private static final String ORDER_RISK = """
        {
          "flowId": "order_risk", "flowName": "订单风控", "aggregatePolicy": "PRIORITY",
          "stages": [
            {
              "stageId": "s1", "stageName": "硬规则", "order": 100,
              "executionMode": "SERIAL", "skipWhenStopped": true,
              "nodes": [
                {
                  "nodeId": "AMOUNT_LIMIT", "nodeName": "金额上限", "nodeType": "OPERATOR", "order": 20,
                  "operatorDef": { "leftFact": "fact.order.amount", "operator": "GT", "rightValue": 50000 },
                  "decisionOnHit": "REJECT", "stopOnHit": true, "failPolicy": "SKIP", "timeoutMillis": 500
                }
              ]
            }
          ]
        }
        """;

    @Test
    void parsesOperatorFlow() {
        FlowDefinition def = codec.parse(ORDER_RISK);
        assertThat(def.getFlowId()).isEqualTo("order_risk");
        var node = def.getStages().get(0).getNodes().get(0);
        assertThat(node.getNodeType()).isEqualTo(NodeType.OPERATOR);
        assertThat(node.getOperatorDef().getOperator()).isEqualTo("GT");
        assertThat(node.getOperatorDef().getRightValue()).isEqualTo(50000);
    }

    @Test
    void roundTrips() {
        FlowDefinition def = codec.parse(ORDER_RISK);
        FlowDefinition again = codec.parse(codec.toJson(def));
        assertThat(again.getFlowId()).isEqualTo("order_risk");
        assertThat(again.getStages().get(0).getNodes().get(0).getOperatorDef().getOperator())
                .isEqualTo("GT");
    }

    @Test
    void malformedJsonThrowsFlowValidation() {
        assertThatThrownBy(() -> codec.parse("{ not json"))
                .isInstanceOf(FlowValidationException.class);
    }
}
