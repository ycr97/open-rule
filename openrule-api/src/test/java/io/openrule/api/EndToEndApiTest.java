package io.openrule.api;

import io.openrule.api.test.ApiTestApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = ApiTestApplication.class)
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)   // 每方法重建上下文 → 内存仓储干净（版本恒为 1）
class EndToEndApiTest {

    @Autowired
    MockMvc mvc;

    private static final String ORDER_RISK = """
        {
          "flowId": "order_risk", "flowName": "订单风控", "aggregatePolicy": "PRIORITY",
          "stages": [
            { "stageId": "s1", "stageName": "硬规则", "order": 100,
              "executionMode": "SERIAL", "skipWhenStopped": true,
              "nodes": [ { "nodeId": "AMOUNT_LIMIT", "nodeName": "金额上限", "nodeType": "OPERATOR", "order": 20,
                "operatorDef": { "leftFact": "fact.order.amount", "operator": "GT", "rightValue": 50000 },
                "decisionOnHit": "REJECT", "stopOnHit": true, "failPolicy": "SKIP", "timeoutMillis": 500 } ] },
            { "stageId": "s2", "stageName": "并行评分", "order": 200,
              "executionMode": "PARALLEL", "skipWhenStopped": true, "stageTimeoutMillis": 2000,
              "nodes": [ { "nodeId": "VIP_CHECK", "nodeName": "VIP", "nodeType": "OPERATOR", "order": 10,
                "operatorDef": { "leftFact": "fact.buyer.level", "operator": "EQ", "rightValue": "NEW" },
                "decisionOnHit": "REVIEW", "stopOnHit": false, "failPolicy": "SKIP", "timeoutMillis": 500 } ] }
          ]
        }
        """;

    private void register() throws Exception {
        mvc.perform(post("/api/v1/admin/flows").contentType(MediaType.APPLICATION_JSON).content(ORDER_RISK))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flowId").value("order_risk"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.enabled").value(true));
    }

    private String executeBody(int amount, String level, boolean debug) {
        return """
            { "flowId": "order_risk", "bizId": "B1", "debug": %s,
              "facts": { "order": { "amount": %d }, "buyer": { "level": "%s" } } }
            """.formatted(debug, amount, level);
    }

    @Test
    void register_thenExecute_bigAmount_rejected() throws Exception {
        register();
        mvc.perform(post("/api/v1/execute").contentType(MediaType.APPLICATION_JSON)
                        .content(executeBody(80000, "NEW", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("REJECT"))
                .andExpect(jsonPath("$.flowVersion").value(1))
                .andExpect(jsonPath("$.hitNodes[0]").value("AMOUNT_LIMIT"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void execute_newBuyer_review() throws Exception {
        register();
        mvc.perform(post("/api/v1/execute").contentType(MediaType.APPLICATION_JSON)
                        .content(executeBody(1000, "NEW", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("REVIEW"));
    }

    @Test
    void execute_vip_pass() throws Exception {
        register();
        mvc.perform(post("/api/v1/execute").contentType(MediaType.APPLICATION_JSON)
                        .content(executeBody(1000, "VIP", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("PASS"));
    }

    @Test
    void execute_unknownFlow_404() throws Exception {
        mvc.perform(post("/api/v1/execute").contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"flowId\": \"nope\", \"bizId\": \"B\", \"facts\": {} }"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RULE_ENGINE"));
    }

    @Test
    void simulate_draftReturnsDetail() throws Exception {
        String body = """
            { "definition": %s, "facts": { "order": { "amount": 80000 }, "buyer": { "level": "NEW" } } }
            """.formatted(ORDER_RISK);
        mvc.perform(post("/api/v1/admin/simulate").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("REJECT"))
                .andExpect(jsonPath("$.nodeResults").isArray());
    }
}
