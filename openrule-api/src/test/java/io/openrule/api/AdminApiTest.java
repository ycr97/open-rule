package io.openrule.api;

import io.openrule.api.test.ApiTestApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = ApiTestApplication.class)
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AdminApiTest {

    @Autowired
    MockMvc mvc;

    private String flow(int threshold) {
        return """
            { "flowId": "f", "flowName": "n", "aggregatePolicy": "PRIORITY",
              "stages": [ { "stageId": "s1", "order": 100, "executionMode": "SERIAL", "skipWhenStopped": true,
                "nodes": [ { "nodeId": "AMT", "nodeName": "AMT", "nodeType": "OPERATOR", "order": 10,
                  "operatorDef": { "leftFact": "fact.order.amount", "operator": "GT", "rightValue": %d },
                  "decisionOnHit": "REJECT", "stopOnHit": true, "failPolicy": "SKIP", "timeoutMillis": 500 } ] } ] }
            """.formatted(threshold);
    }

    private void register(int threshold) throws Exception {
        mvc.perform(post("/api/v1/admin/flows").contentType(MediaType.APPLICATION_JSON).content(flow(threshold)))
                .andExpect(status().isOk());
    }

    private String exec(int amount) {
        return """
            { "flowId": "f", "bizId": "B1", "facts": { "order": { "amount": %d } } }
            """.formatted(amount);
    }

    @Test
    void versions_listsAllWithEnabledFlag() throws Exception {
        register(50000);    // v1
        register(100000);   // v2 active
        mvc.perform(get("/api/v1/admin/flows/f/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].version").value(1))
                .andExpect(jsonPath("$[0].enabled").value(false))
                .andExpect(jsonPath("$[1].version").value(2))
                .andExpect(jsonPath("$[1].enabled").value(true));
    }

    @Test
    void enable_switchesPointer_observableInExecute() throws Exception {
        register(50000);    // v1
        register(100000);   // v2 active(阈值10w)
        // active=v2：amount 60000 不命中 → PASS
        mvc.perform(post("/api/v1/execute").contentType(MediaType.APPLICATION_JSON).content(exec(60000)))
                .andExpect(jsonPath("$.decision").value("PASS"));
        // 启用 v1
        mvc.perform(post("/api/v1/admin/flows/f/enable").param("version", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.enabled").value(true));
        // active=v1(阈值5w)：amount 60000 命中 → REJECT
        mvc.perform(post("/api/v1/execute").contentType(MediaType.APPLICATION_JSON).content(exec(60000)))
                .andExpect(jsonPath("$.decision").value("REJECT"));
    }

    @Test
    void rollback_thenLogsQueryReturnsEntries() throws Exception {
        register(50000);    // v1
        register(100000);   // v2
        mvc.perform(post("/api/v1/admin/flows/f/rollback").param("version", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
        mvc.perform(post("/api/v1/execute").contentType(MediaType.APPLICATION_JSON).content(exec(60000)))
                .andExpect(jsonPath("$.decision").value("REJECT"));
        mvc.perform(get("/api/v1/admin/logs").param("bizId", "B1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].flowId").value("f"))
                .andExpect(jsonPath("$[0].flowVersion").value(1));
    }

    @Test
    void enable_missingVersion_404() throws Exception {
        register(50000);
        mvc.perform(post("/api/v1/admin/flows/f/enable").param("version", "99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RULE_ENGINE"));
    }
}
