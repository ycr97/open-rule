package io.openrule.studio.host.fact;

import com.fasterxml.jackson.databind.JsonNode;
import io.openrule.spring.fact.FactConfiguration;
import io.openrule.spring.studio.StudioDocumentCodec;
import io.openrule.spring.studio.StudioProblem;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/** EE current-configuration persistence. No transaction spans a Provider call. */
public final class JdbcFactStore {
    public record Resource(JsonNode document, String revision, Instant updatedAt) { }
    public record Page(List<Resource> items, int page, int pageSize, long total) { }
    public record Selection(FactConfiguration.Snapshot snapshot, String providerRevision) { }
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager manager;
    private final StudioDocumentCodec codec;
    private final FactDocuments documents;
    public JdbcFactStore(JdbcTemplate jdbc, PlatformTransactionManager manager, StudioDocumentCodec codec, FactDocuments documents) {
        this.jdbc = jdbc; this.manager = manager; this.codec = codec; this.documents = documents;
    }
    private TransactionTemplate transaction(int isolation, boolean readOnly) {
        TransactionTemplate tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(isolation); tx.setReadOnly(readOnly);
        return tx;
    }
    private <T> T write(int isolation, Supplier<T> action) {
        try { return transaction(isolation, false).execute(status -> action.get()); }
        catch (ConcurrencyFailureException | DataIntegrityViolationException ex) {
            throw new StudioProblem(409, "OR-FACT-CONFLICT", "Concurrent or duplicate configuration write");
        }
    }
    public Resource get(boolean fact, String id) {
        FactDocuments.id(id);
        return find(fact, id, false);
    }
    private Resource find(boolean fact, String id, boolean lock) {
        return jdbc.query("SELECT document_json,revision,updated_at FROM " + table(fact) + " WHERE " + key(fact) + "=?" + (lock ? " FOR UPDATE" : ""),
                (rs, n) -> new Resource(codec.parse(rs.getString(1)), Long.toString(rs.getLong(2)), rs.getTimestamp(3).toInstant()), id)
                .stream().findFirst().orElseThrow(() -> new StudioProblem(404, "OR-FACT-NOT-FOUND", "Configuration not found"));
    }
    public Resource create(boolean fact, JsonNode document) {
        documents.validate(fact ? "FactDocument" : "ProviderDocument", document);
        documents.configuration(document, fact, "");
        return write(fact ? TransactionDefinition.ISOLATION_SERIALIZABLE : TransactionDefinition.ISOLATION_READ_COMMITTED, () -> {
            if (fact) {
                // Full unique-index range, including the empty-table supremum gap. Deadlocks lose with 409, never retry.
                List<String> paths = jdbc.queryForList("SELECT path FROM or_studio_fact_catalog FORCE INDEX (uk_studio_fact_path) ORDER BY path FOR UPDATE", String.class);
                List<String> candidate = FactDocuments.tokens(document.path("path").asText());
                for (String path : paths) {
                    List<String> existing = FactDocuments.tokens(path);
                    if (existing.equals(candidate)) throw FactDocuments.issue(409, "OR-FACT-CONFLICT", "Fact path already exists", "/path");
                    if (prefix(existing, candidate) || prefix(candidate, existing)) throw FactDocuments.config("Fact paths cannot overlap", "/path");
                }
                checkBinding(document, "");
                jdbc.update("INSERT INTO or_studio_fact_catalog(fact_id,path,provider_id,revision,document_json) VALUES(?,?,?,1,?)",
                        document.path("factId").asText(), document.path("path").asText(), providerId(document), codec.json(document));
            } else jdbc.update("INSERT INTO or_studio_fact_provider(provider_id,revision,document_json) VALUES(?,1,?)",
                    document.path("providerId").asText(), codec.json(document));
            return find(fact, document.path(fact ? "factId" : "providerId").asText(), false);
        });
    }
    public Resource update(boolean fact, String id, JsonNode body) {
        FactDocuments.id(id);
        // Range errors are request errors, even when the schema's digit pattern accepts the literal.
        long revision = FactDocuments.positive(body.path("expectedRevision").isTextual() ? body.path("expectedRevision").asText() : null);
        documents.validate(fact ? "FactUpdate" : "ProviderUpdate", body);
        JsonNode document = body.path("document");
        if (!id.equals(document.path(fact ? "factId" : "providerId").asText()))
            throw FactDocuments.issue(409, "OR-FACT-IDENTITY", "Identifier cannot change", "/document/" + (fact ? "factId" : "providerId"));
        documents.configuration(document, fact, "/document");
        return write(TransactionDefinition.ISOLATION_READ_COMMITTED, () -> {
            Resource current = find(fact, id, true);
            if (revision != Long.parseLong(current.revision())) throw new StudioProblem(409, "OR-REVISION-CONFLICT", "Revision changed");
            for (String field : fact ? List.of("path", "type") : List.of("type"))
                if (!document.path(field).equals(current.document().path(field)))
                    throw FactDocuments.issue(409, "OR-FACT-IDENTITY", "Field cannot change", "/document/" + field);
            if (revision == Long.MAX_VALUE) throw new StudioProblem(409, "OR-REVISION-CONFLICT", "Revision exhausted");
            if (fact) checkBinding(document, "/document");
            String sql = "UPDATE " + table(fact) + " SET document_json=?,revision=revision+1" + (fact ? ",provider_id=?" : "")
                    + " WHERE " + key(fact) + "=? AND revision=?";
            int changed = fact ? jdbc.update(sql, codec.json(document), providerId(document), id, revision)
                    : jdbc.update(sql, codec.json(document), id, revision);
            if (changed != 1) throw new StudioProblem(409, "OR-REVISION-CONFLICT", "Revision changed");
            return find(fact, id, false);
        });
    }
    public Page list(boolean fact, String q, String source, int page, int size) {
        if (page < 1 || size < 1 || size > 100 || q != null && q.codePointCount(0, q.length()) > 128
                || source != null && !Set.of("CALLER", "PROVIDER").contains(source))
            throw new StudioProblem(400, "OR-REQUEST-INVALID", "Invalid pagination or filter");
        return transaction(TransactionDefinition.ISOLATION_REPEATABLE_READ, true).execute(status -> {
            String like = "%" + (q == null ? "" : q).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            String match = "(CONVERT(" + key(fact) + " USING utf8mb4) LIKE ? ESCAPE '!' OR JSON_UNQUOTE(JSON_EXTRACT(document_json,'$.name')) LIKE ? ESCAPE '!'"
                    + (fact ? " OR path LIKE ? ESCAPE '!'" : "") + ")";
            List<Object> params = new ArrayList<>(List.of(like, like)); if (fact) params.add(like);
            if (fact && source != null) match += " AND provider_id IS " + (source.equals("CALLER") ? "NULL" : "NOT NULL");
            long total = jdbc.queryForObject("SELECT COUNT(*) FROM " + table(fact) + " WHERE " + match, Long.class, params.toArray());
            params.add(size); params.add(((long) page - 1) * size);
            List<Resource> items = jdbc.query("SELECT document_json,revision,updated_at FROM " + table(fact) + " WHERE " + match
                            + " ORDER BY " + key(fact) + " LIMIT ? OFFSET ?", (rs, n) -> new Resource(codec.parse(rs.getString(1)),
                            Long.toString(rs.getLong(2)), rs.getTimestamp(3).toInstant()), params.toArray());
            return new Page(List.copyOf(items), page, size, total);
        });
    }
    public Selection snapshot(List<String> paths, String mockProvider) {
        return transaction(TransactionDefinition.ISOLATION_REPEATABLE_READ, true).execute(status -> {
            Map<String, Resource> providers = new LinkedHashMap<>();
            if (mockProvider != null) providers.put(mockProvider, get(false, mockProvider));
            List<Resource> facts;
            if (mockProvider != null) facts = jdbc.query("SELECT document_json,revision,updated_at FROM or_studio_fact_catalog WHERE provider_id=? ORDER BY path",
                    (rs, n) -> new Resource(codec.parse(rs.getString(1)), Long.toString(rs.getLong(2)), rs.getTimestamp(3).toInstant()), mockProvider);
            else if (paths.isEmpty()) facts = List.of();
            else facts = jdbc.query("SELECT document_json,revision,updated_at FROM or_studio_fact_catalog WHERE path IN ("
                            + String.join(",", Collections.nCopies(paths.size(), "?")) + ") ORDER BY path",
                    (rs, n) -> new Resource(codec.parse(rs.getString(1)), Long.toString(rs.getLong(2)), rs.getTimestamp(3).toInstant()), paths.toArray());
            for (Resource fact : facts) {
                String id = providerId(fact.document());
                if (id != null) providers.computeIfAbsent(id, key -> get(false, key));
            }
            return new Selection(new FactConfiguration.Snapshot(facts.stream().map(f -> documents.binding(f.document(), f.revision())).toList(),
                    providers.values().stream().map(p -> documents.provider(p.document(), p.revision())).toList()),
                    mockProvider == null ? null : providers.get(mockProvider).revision());
        });
    }
    private void checkBinding(JsonNode document, String base) {
        String id = providerId(document);
        if (id != null && jdbc.queryForObject("SELECT COUNT(*) FROM or_studio_fact_provider WHERE provider_id=?", Integer.class, id) != 1)
            throw FactDocuments.config("Unknown Provider reference", base + "/binding/providerId");
    }
    private static boolean prefix(List<String> a, List<String> b) { return a.size() < b.size() && b.subList(0, a.size()).equals(a); }
    private static String providerId(JsonNode d) { return d.path("binding").isNull() ? null : d.path("binding").path("providerId").asText(); }
    private static String table(boolean fact) { return fact ? "or_studio_fact_catalog" : "or_studio_fact_provider"; }
    private static String key(boolean fact) { return fact ? "fact_id" : "provider_id"; }
}
