package io.openrule.api.studio;

import com.fasterxml.jackson.databind.JsonNode;
import io.openrule.spring.studio.StudioDocumentCodec;
import io.openrule.spring.studio.StudioService;
import io.openrule.spring.studio.StudioStore.Version;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v2")
public final class StudioController {
    private final StudioService service;
    private final StudioDocumentCodec codec;

    public StudioController(StudioService service, StudioDocumentCodec codec) {
        this.service = service;
        this.codec = codec;
    }

    @GetMapping("/admin/flows")
    public Map<String, Object> flows(@RequestParam(required = false) String q,
                                     @RequestParam(defaultValue = "1") int page,
                                     @RequestParam(defaultValue = "20") int pageSize) {
        var found = service.flows(q, page, pageSize);
        return Map.of("items", found.items().stream().map(head -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("flowId", head.flowId());
            item.put("flowName", head.flowName());
            item.put("currentDraft", head.currentDraftVersion() == null ? null
                    : summary(service.version(head.flowId(), Long.toString(head.currentDraftVersion()))));
            item.put("latestPublished", head.latestPublishedVersion() == null ? null
                    : summary(service.version(head.flowId(), Long.toString(head.latestPublishedVersion()))));
            return item;
        }).toList(), "page", page, "pageSize", pageSize, "total", found.total());
    }

    @PostMapping("/admin/flows")
    public ResponseEntity<Map<String, Object>> create(@RequestBody String body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(resource(service.createFlow(codec.parse(body))));
    }

    @GetMapping("/admin/flows/{flowId}/versions")
    public Map<String, Object> versions(@PathVariable String flowId,
                                        @RequestParam(defaultValue = "1") int page,
                                        @RequestParam(defaultValue = "20") int pageSize) {
        var found = service.versions(flowId, page, pageSize);
        return Map.of("items", found.items().stream().map(this::summary).toList(),
                "page", page, "pageSize", pageSize, "total", found.total());
    }

    @GetMapping("/admin/flows/{flowId}/versions/{version}")
    public Map<String, Object> version(@PathVariable String flowId, @PathVariable String version) {
        return resource(service.version(flowId, version));
    }

    @PostMapping("/admin/flows/{flowId}/drafts")
    public ResponseEntity<Map<String, Object>> draft(@PathVariable String flowId, @RequestBody String body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(resource(service.createDraft(flowId, codec.parse(body))));
    }

    @PutMapping("/admin/flows/{flowId}/versions/{version}")
    public Map<String, Object> save(@PathVariable String flowId, @PathVariable String version,
                                    @RequestBody String body) {
        return resource(service.save(flowId, version, codec.parse(body)));
    }

    @PostMapping("/admin/flows/{flowId}/versions/{version}/validations")
    public StudioDocumentCodec.Report validate(@PathVariable String flowId, @PathVariable String version,
                                               @RequestBody String body) {
        return service.validate(flowId, version, codec.parse(body));
    }

    @PostMapping("/admin/flows/{flowId}/versions/{version}/publish")
    public Map<String, Object> publish(@PathVariable String flowId, @PathVariable String version,
                                       @RequestBody String body) {
        return resource(service.publish(flowId, version, codec.parse(body)));
    }

    @PostMapping("/admin/flows/{flowId}/versions/{version}/simulations")
    public Map<String, Object> simulate(@PathVariable String flowId, @PathVariable String version,
                                        @RequestBody String body, HttpServletRequest request) {
        JsonNode value = codec.parse(body);
        request.setAttribute("studioRequestId", value.path("requestId").asText(null));
        return service.execute(flowId, version, value, true);
    }

    @PostMapping("/flows/{flowId}/versions/{version}/executions")
    public Map<String, Object> execute(@PathVariable String flowId, @PathVariable String version,
                                       @RequestBody String body, HttpServletRequest request) {
        JsonNode value = codec.parse(body);
        request.setAttribute("studioRequestId", value.path("requestId").asText(null));
        return service.execute(flowId, version, value, false);
    }

    private Map<String, Object> resource(Version value) {
        Map<String, Object> result = new LinkedHashMap<>(summary(value));
        result.put("document", service.document(value));
        result.put("validation", service.validation(value));
        result.put("changeNote", value.changeNote());
        result.put("updatedAt", value.updatedAt().toString());
        return result;
    }

    private Map<String, Object> summary(Version value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("flowId", value.flowId());
        result.put("flowName", value.flowName());
        result.put("version", Long.toString(value.version()));
        result.put("status", value.status());
        result.put("revision", Long.toString(value.revision()));
        result.put("definitionChecksum", value.checksum());
        return result;
    }
}
