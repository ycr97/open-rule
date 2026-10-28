package io.openrule.studio.host.fact;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v2")
public final class FactController {
    static final String CONTEXT = FactController.class.getName() + ".context";
    private final JdbcFactStore store;
    private final FactDocuments documents;
    private final FactService service;
    public FactController(JdbcFactStore store, FactDocuments documents, FactService service) { this.store = store; this.documents = documents; this.service = service; }
    @ModelAttribute void context(HttpServletRequest request) { request.setAttribute(CONTEXT, new FactExecutionContext()); }
    private JsonNode body(String raw, HttpServletRequest request) {
        JsonNode body = documents.parse(raw);
        if (body.path("requestId").isTextual()) {
            String requestId = body.path("requestId").asText();
            int length = requestId.codePointCount(0, requestId.length());
            if (length > 0 && length <= 128) ctx(request).requestId = requestId;
        }
        return body;
    }
    private static FactExecutionContext ctx(HttpServletRequest request) { return (FactExecutionContext) request.getAttribute(CONTEXT); }
    @GetMapping("/admin/facts")
    public JdbcFactStore.Page facts(@RequestParam(required = false) String q, @RequestParam(required = false) String source,
                                    @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
        return store.list(true, q, source, page, pageSize);
    }
    @PostMapping("/admin/facts")
    public ResponseEntity<JdbcFactStore.Resource> createFact(@RequestBody String raw, HttpServletRequest request) { return ResponseEntity.status(201).body(store.create(true, body(raw, request))); }
    @GetMapping("/admin/facts/{factId}")
    public JdbcFactStore.Resource fact(@PathVariable String factId) { return store.get(true, factId); }
    @PutMapping("/admin/facts/{factId}")
    public JdbcFactStore.Resource updateFact(@PathVariable String factId, @RequestBody String raw, HttpServletRequest request) { return store.update(true, factId, body(raw, request)); }
    @GetMapping("/admin/fact-providers")
    public JdbcFactStore.Page providers(@RequestParam(required = false) String q, @RequestParam(defaultValue = "1") int page,
                                        @RequestParam(defaultValue = "20") int pageSize) { return store.list(false, q, null, page, pageSize); }
    @PostMapping("/admin/fact-providers")
    public ResponseEntity<JdbcFactStore.Resource> createProvider(@RequestBody String raw, HttpServletRequest request) { return ResponseEntity.status(201).body(store.create(false, body(raw, request))); }
    @GetMapping("/admin/fact-providers/{providerId}")
    public JdbcFactStore.Resource provider(@PathVariable String providerId) { return store.get(false, providerId); }
    @PutMapping("/admin/fact-providers/{providerId}")
    public JdbcFactStore.Resource updateProvider(@PathVariable String providerId, @RequestBody String raw, HttpServletRequest request) { return store.update(false, providerId, body(raw, request)); }
    @PostMapping("/admin/fact-providers/{providerId}/mock-tests")
    public Map<String, Object> mock(@PathVariable String providerId, @RequestBody String raw, HttpServletRequest request) { return service.mock(providerId, body(raw, request), ctx(request)); }
    @GetMapping("/admin/flows/{flowId}/versions/{version}/required-facts")
    public Map<String, Object> required(@PathVariable String flowId, @PathVariable String version) { return service.required(flowId, version); }
    @PostMapping("/admin/flows/{flowId}/versions/{version}/fact-simulations")
    public Map<String, Object> simulate(@PathVariable String flowId, @PathVariable String version, @RequestBody String raw, HttpServletRequest request) { return service.execute(flowId, version, body(raw, request), true, ctx(request)); }
    @PostMapping("/flows/{flowId}/versions/{version}/fact-executions")
    public Map<String, Object> execute(@PathVariable String flowId, @PathVariable String version, @RequestBody String raw, HttpServletRequest request) { return service.execute(flowId, version, body(raw, request), false, ctx(request)); }
}
