import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;

/** Offline D0 document validation only. Does not implement fact resolution. */
public final class SchemaCheck {
    public static void main(String[] args) throws Exception {
        var mapper = new ObjectMapper()
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
        var registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
        JsonNode suite = mapper.readTree(Path.of(args[0]).toFile());
        int compiled = 0, valid = 0, rejected = 0;
        for (JsonNode schema : suite.get("schemas")) {
            registry.getSchema(new ByteArrayInputStream(mapper.writeValueAsBytes(schema)));
            compiled++;
        }
        for (JsonNode test : suite.get("tests")) {
            var schema = registry.getSchema(new ByteArrayInputStream(mapper.writeValueAsBytes(test.get("schema"))));
            var errors = schema.validate(test.get("instance"));
            boolean expected = test.get("valid").asBoolean();
            if (errors.isEmpty() != expected) {
                throw new AssertionError(test.get("label").asText() + ": expected valid=" + expected + "; " + errors);
            }
            if (expected) valid++; else rejected++;
        }
        System.out.println("PASS Schema: " + compiled + " definitions compiled; " + valid
                + " valid instances; " + rejected + " invalid instances rejected");
    }
}
