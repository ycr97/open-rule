package io.openrule.jdbc.studio;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.openrule.spring.studio.StudioDocumentCodec;
import io.openrule.spring.studio.StudioProblem;
import io.openrule.spring.studio.StudioStore;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/** Studio v2 tables are separate from the legacy or_flow data and enabled pointer. */
public final class JdbcStudioStore implements StudioStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public JdbcStudioStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(manager);
    }

    private static final RowMapper<Version> VERSION = (rs, n) -> new Version(
            rs.getString("flow_id"), rs.getString("flow_name"), rs.getLong("version"),
            rs.getString("status"), rs.getLong("revision"), rs.getString("document_json"),
            rs.getString("definition_checksum"), rs.getString("change_note"),
            rs.getTimestamp("updated_at").toInstant());

    private static FlowHead head(ResultSet rs, int row) throws SQLException {
        return new FlowHead(rs.getString("flow_id"), rs.getString("flow_name"),
                (Long) rs.getObject("current_draft_version"), (Long) rs.getObject("latest_published_version"));
    }

    @Override public Version createFlow(String flowId, String flowName, String initialJson) {
        try {
            return tx.execute(status -> {
                jdbc.update("INSERT INTO or_studio_flow(flow_id,flow_name,next_version,current_draft_version)"
                        + " VALUES(?,?,2,1)", flowId, flowName);
                jdbc.update("INSERT INTO or_studio_version(flow_id,version,flow_name,status,revision,document_json)"
                        + " VALUES(?,1,?,'DRAFT',1,?)", flowId, flowName, initialJson);
                return version(flowId, 1);
            });
        } catch (DataIntegrityViolationException ex) {
            throw new StudioProblem(409, "OR-LIFECYCLE-CONFLICT", "Flow already exists: " + flowId);
        }
    }

    @Override public Page<FlowHead> flows(String query, int page, int pageSize) {
        String q = query == null ? "" : query;
        String like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM or_studio_flow WHERE flow_id LIKE ? OR flow_name LIKE ?",
                Long.class, like, like);
        List<FlowHead> items = jdbc.query("SELECT flow_id,flow_name,current_draft_version,latest_published_version"
                + " FROM or_studio_flow WHERE flow_id LIKE ? OR flow_name LIKE ? ORDER BY flow_id LIMIT ? OFFSET ?",
                JdbcStudioStore::head, like, like, pageSize, (long) (page - 1) * pageSize);
        return new Page<>(items, page, pageSize, total);
    }

    @Override public Page<Version> versions(String flowId, int page, int pageSize) {
        requireFlow(flowId);
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM or_studio_version WHERE flow_id=?", Long.class, flowId);
        List<Version> items = jdbc.query(select() + " WHERE v.flow_id=? ORDER BY v.version DESC LIMIT ? OFFSET ?",
                VERSION, flowId, pageSize, (long) (page - 1) * pageSize);
        return new Page<>(items, page, pageSize, total);
    }

    @Override public Version version(String flowId, long version) {
        return jdbc.query(select() + " WHERE v.flow_id=? AND v.version=?", VERSION, flowId, version)
                .stream().findFirst().orElseThrow(() -> new StudioProblem(404, "OR-FLOW-NOT-FOUND",
                        "Flow version not found: " + flowId + "/" + version));
    }

    @Override public Version createDraft(String flowId, long sourceVersion, StudioDocumentCodec codec) {
        return tx.execute(status -> {
            FlowHead flow = lockedHead(flowId);
            if (flow.currentDraftVersion() != null)
                throw new StudioProblem(409, "OR-LIFECYCLE-CONFLICT",
                        "Draft already exists: " + flowId + "/" + flow.currentDraftVersion());
            Version source = version(flowId, sourceVersion);
            if (!source.status().equals("PUBLISHED"))
                throw new StudioProblem(409, "OR-LIFECYCLE-CONFLICT", "Source must be published");
            long next = jdbc.queryForObject("SELECT next_version FROM or_studio_flow WHERE flow_id=?", Long.class, flowId);
            ObjectNode copy = (ObjectNode) codec.parse(source.documentJson());
            copy.put("version", Long.toString(next));
            jdbc.update("INSERT INTO or_studio_version(flow_id,version,flow_name,status,revision,document_json)"
                    + " VALUES(?,?,?,'DRAFT',1,?)", flowId, next, source.flowName(), codec.json(copy));
            jdbc.update("UPDATE or_studio_flow SET next_version=?,current_draft_version=? WHERE flow_id=?",
                    next + 1, next, flowId);
            return version(flowId, next);
        });
    }

    @Override public Version save(String flowId, long version, long expectedRevision,
                                  String flowName, String json) {
        return tx.execute(status -> {
            int updated = jdbc.update("UPDATE or_studio_version SET document_json=?,flow_name=?,revision=revision+1"
                            + " WHERE flow_id=? AND version=? AND status='DRAFT' AND revision=?",
                    json, flowName, flowId, version, expectedRevision);
            if (updated != 1) conflict(flowId, version, expectedRevision);
            jdbc.update("UPDATE or_studio_flow SET flow_name=? WHERE flow_id=? AND current_draft_version=?",
                    flowName, flowId, version);
            return version(flowId, version);
        });
    }

    @Override public Version publish(String flowId, long version, long expectedRevision,
                                     String changeNote, StudioDocumentCodec codec) {
        return tx.execute(status -> {
            List<Version> locked = jdbc.query(select() + " WHERE v.flow_id=? AND v.version=? FOR UPDATE",
                    VERSION, flowId, version);
            if (locked.isEmpty()) throw new StudioProblem(404, "OR-FLOW-NOT-FOUND", "Flow version not found");
            Version current = locked.getFirst();
            if (!current.status().equals("DRAFT"))
                throw new StudioProblem(409, "OR-LIFECYCLE-CONFLICT", "Published version is immutable");
            if (current.revision() != expectedRevision)
                throw new StudioProblem(409, "OR-REVISION-CONFLICT", "Revision changed");
            var document = codec.parse(current.documentJson());
            codec.requireDraft(document, flowId, Long.toString(version));
            codec.executable(document);
            String checksum = codec.checksum(document);
            int updated = jdbc.update("UPDATE or_studio_version SET status='PUBLISHED',revision=revision+1,"
                    + "definition_checksum=?,change_note=? WHERE flow_id=? AND version=? AND status='DRAFT' AND revision=?",
                    checksum, changeNote, flowId, version, expectedRevision);
            if (updated != 1) throw new StudioProblem(409, "OR-REVISION-CONFLICT", "Revision changed");
            int head = jdbc.update("UPDATE or_studio_flow SET current_draft_version=NULL,latest_published_version=?"
                    + " WHERE flow_id=? AND current_draft_version=?", version, flowId, version);
            if (head != 1) throw new IllegalStateException("Studio flow head lost its draft pointer");
            return version(flowId, version);
        });
    }

    private void conflict(String flowId, long version, long expectedRevision) {
        Version current = version(flowId, version);
        if (!current.status().equals("DRAFT"))
            throw new StudioProblem(409, "OR-LIFECYCLE-CONFLICT", "Published version is immutable");
        throw new StudioProblem(409, "OR-REVISION-CONFLICT", "Expected revision " + expectedRevision
                + ", current revision " + current.revision());
    }

    private void requireFlow(String flowId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM or_studio_flow WHERE flow_id=?", Integer.class, flowId);
        if (count == null || count == 0) throw new StudioProblem(404, "OR-FLOW-NOT-FOUND", "Flow not found: " + flowId);
    }

    private FlowHead lockedHead(String flowId) {
        return jdbc.query("SELECT flow_id,flow_name,current_draft_version,latest_published_version"
                + " FROM or_studio_flow WHERE flow_id=? FOR UPDATE", JdbcStudioStore::head, flowId)
                .stream().findFirst().orElseThrow(() -> new StudioProblem(404, "OR-FLOW-NOT-FOUND", "Flow not found"));
    }

    private static String select() {
        return "SELECT v.flow_id,v.flow_name,v.version,v.status,v.revision,v.document_json,"
                + "v.definition_checksum,v.change_note,v.updated_at FROM or_studio_version v"
                + " JOIN or_studio_flow f ON f.flow_id=v.flow_id";
    }
}
