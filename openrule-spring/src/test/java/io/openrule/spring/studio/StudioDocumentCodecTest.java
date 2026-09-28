package io.openrule.spring.studio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StudioDocumentCodecTest {
    private final StudioDocumentCodec codec = new StudioDocumentCodec();

    @Test void initialDraftIsStructuralButIncomplete() {
        JsonNode initial = codec.initial("test_flow", "Test \"Flow\"\nName");
        codec.requireDraft(initial, "test_flow", "1");
        assertThat(codec.validate(initial).valid()).isFalse();
        assertThat(codec.validate(initial).issues()).anySatisfy(issue ->
                assertThat(issue.stageId()).isEqualTo("default_stage"));
    }

    @Test void checksumMatchesD0Vectors() throws Exception {
        JsonNode vectors = new ObjectMapper().readTree(Path.of("../docs/contracts/studio-m1/fixtures/valid/checksum-vectors.json").toFile());
        for (JsonNode vector : vectors) {
            String json = vector.has("file")
                    ? Files.readString(Path.of("../docs/contracts/studio-m1/fixtures/valid", vector.path("file").asText()))
                    : vector.path("rawJson").asText();
            assertThat(codec.checksum(codec.parse(json))).as(vector.toString())
                    .isEqualTo(vector.path("checksum").asText());
        }
    }

    @Test void rejectsDuplicateKeysAndUnsafeDecimals() {
        assertThatThrownBy(() -> codec.parse("{\"x\":1,\"x\":2}"))
                .isInstanceOf(StudioProblem.class).extracting("code").isEqualTo("OR-DEF-SCHEMA");
        assertThatThrownBy(() -> codec.parse("{\"x\":1e1001}"))
                .isInstanceOf(StudioProblem.class);
        assertThatThrownBy(() -> codec.parse("{\"x\":9007199254740993,\"n\":1e-1001}"))
                .isInstanceOf(StudioProblem.class);
    }
}
