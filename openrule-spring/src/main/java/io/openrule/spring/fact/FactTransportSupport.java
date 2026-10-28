package io.openrule.spring.fact;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.openrule.spring.studio.StudioDocumentCodec;
import java.util.*;
import static io.openrule.spring.fact.FactConfiguration.*;

final class FactTransportSupport {
    static final int MAX_BYTES = 1024 * 1024;
    private static final StudioDocumentCodec CODEC = new StudioDocumentCodec();
    private static final ObjectMapper WRITER = new ObjectMapper();
    private FactTransportSupport() { }
    static Map<String, Object> requestBody(FactBatchRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        request.provider().requestMap().forEach((key, mapping) -> {
            if (mapping instanceof Constant c) body.put(key, c.value());
            else {
                var found = FactValues.lookup(request.callerFacts(), ((Fact) mapping).pointer());
                if (!found.present() || found.value() == null) throw new InputFailure();
                body.put(key, found.value());
            }
        });
        return body;
    }
    static String scalarJson(Object value) {
        try { return WRITER.writeValueAsString(value); }
        catch (Exception ex) { throw new IllegalArgumentException("Invalid scalar"); }
    }
    static byte[] encode(Object body) {
        try {
            byte[] bytes = WRITER.writeValueAsBytes(body);
            if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("Body exceeds limit");
            // Reuse M1 lexical number/depth limits for caller-provided constants and Mock responses too.
            CODEC.parse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            return bytes;
        } catch (RuntimeException ex) { throw ex; }
        catch (Exception ex) { throw new IllegalArgumentException("Invalid body"); }
    }
    static Object parse(byte[] bytes) {
        // Reject malformed UTF-8 rather than replacing bytes before strict JSON parsing.
        try {
            String text = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            return CODEC.value(CODEC.parse(text));
        } catch (Exception ex) { throw new IllegalArgumentException("Invalid response"); }
    }
    static FactBatchResult response(FactBatchRequest request, Object body) {
        List<FactBatchResult.Field> fields = new ArrayList<>();
        for (Binding field : request.fields()) {
            var value = FactValues.lookup(body, field.responsePointer());
            if (!value.present()) fields.add(new FactBatchResult.Field(field.path(), FactBatchResult.Status.missing, null, "OR-FACT-RESPONSE-MISSING"));
            else if (!field.type().accepts(value.value())) fields.add(new FactBatchResult.Field(field.path(), FactBatchResult.Status.failed, null, "OR-FACT-TYPE"));
            else fields.add(new FactBatchResult.Field(field.path(), FactBatchResult.Status.resolved, value.value(), null));
        }
        return new FactBatchResult(fields, true, null);
    }
    static final class InputFailure extends RuntimeException { }
}
