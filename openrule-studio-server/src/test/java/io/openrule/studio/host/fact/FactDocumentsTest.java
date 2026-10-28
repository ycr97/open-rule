package io.openrule.studio.host.fact;

import io.openrule.spring.studio.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class FactDocumentsTest {
    private final StudioDocumentCodec codec = new StudioDocumentCodec();
    private final FactDocuments documents = new FactDocuments(codec);
    @Test void usesFrozenSchemaAndRejectsUnsafeOrInconsistentConfiguration() throws Exception {
        var root = Path.of("../docs/contracts/studio-m2/fixtures");
        var provider = codec.parse(Files.readString(root.resolve("valid/risk-profile.provider.json")));
        documents.validate("ProviderDocument", provider); documents.configuration(provider, false, "");
        for (String fixture : java.util.List.of("java.provider.json", "plaintext-secret.provider.json", "mixed-mapping.provider.json", "write-provider.provider.json")) {
            var invalid = codec.parse(Files.readString(root.resolve("invalid/" + fixture)));
            assertThatThrownBy(() -> documents.validate("ProviderDocument", invalid)).isInstanceOf(StudioProblem.class);
        }
        for (String fixture : java.util.List.of("ms-01.semantic.json", "ms-02.semantic.json", "ms-04.semantic.json")) {
            var invalid = codec.parse(Files.readString(root.resolve("valid/" + fixture)));
            assertThatThrownBy(() -> documents.configuration(invalid, !fixture.startsWith("ms-04"), "")).isInstanceOf(StudioProblem.class);
        }
    }
    @Test void transactionConnectionFailuresAreStoreErrors() {
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        var response = new FactProblemAdvice().problem(new org.springframework.transaction.CannotCreateTransactionException("connection-private-value"), request);
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).containsEntry("code", "OR-FACT-STORE");
        assertThat(response.getBody().toString()).doesNotContain("connection-private-value");
    }
    @Test void errorsAndSecretsDoNotEchoInputs() {
        assertThatThrownBy(() -> documents.parse("{\"secret\":\"private-value\"")).hasMessage("Invalid JSON");
        assertThatThrownBy(() -> FactDocuments.positive("9223372036854775808")).isInstanceOf(StudioProblem.class);
        assertThat(new EnvironmentSecretResolver().resolve("env:OTHER_SECRET")).isNull();
    }
}
