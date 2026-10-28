import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.nio.file.*;
import java.util.*;

public final class StudioM1SchemaCheck {
    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]);
        ObjectMapper mapper = new ObjectMapper();
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
        Schema executable = registry.getSchema(Files.newInputStream(root.resolve("schema/definition.schema.json")));
        Schema draft = registry.getSchema(Files.newInputStream(root.resolve("schema/draft.schema.json")));
        int valid = 0, invalid = 0;
        try (var stream = Files.list(root.resolve("fixtures/valid"))) {
            for (Path file : stream.filter(p -> p.toString().endsWith(".definition.json") || p.toString().endsWith(".draft.json")).toList()) {
                Schema schema = file.toString().endsWith("draft.json") ? draft : executable;
                JsonNode value = mapper.readTree(file.toFile());
                var errors = schema.validate(value);
                if (!errors.isEmpty()) throw new AssertionError(file + " should pass: " + errors);
                if (file.toString().endsWith("incomplete.draft.json") && executable.validate(value).isEmpty())
                    throw new AssertionError(file + " must fail executable schema");
                System.out.println("PASS valid " + file.getFileName()); valid++;
            }
        }
        try (var stream = Files.list(root.resolve("fixtures/invalid"))) {
            for (Path file : stream.filter(p -> p.toString().endsWith(".json") && !Set.of("parallel-output.definition.json", "future-node.definition.json", "parallel-terminal.definition.json").contains(p.getFileName().toString())).toList()) {
                JsonNode value = mapper.readTree(file.toFile());
                var errors = executable.validate(value);
                if (errors.isEmpty()) throw new AssertionError(file + " should fail schema");
                System.out.println("PASS rejected " + file.getFileName() + ": " + errors.get(0)); invalid++;
            }
        }
        for (String name : List.of("parallel-output.definition.json", "future-node.definition.json", "parallel-terminal.definition.json")) {
            Path file = root.resolve("fixtures/invalid").resolve(name);
            if (!executable.validate(mapper.readTree(file.toFile())).isEmpty())
                throw new AssertionError(file + " must pass schema before semantic rejection");
        }
        System.out.println("Schema fixtures: " + valid + " valid, " + invalid + " invalid");
    }
}
