package io.openrule.spring.studio;

import java.time.Instant;
import java.util.List;

public interface StudioStore {
    record FlowHead(String flowId, String flowName, Long currentDraftVersion, Long latestPublishedVersion) { }
    record Version(String flowId, String flowName, long version, String status, long revision,
                   String documentJson, String checksum, String changeNote, Instant updatedAt) { }
    record Page<T>(List<T> items, int page, int pageSize, long total) { }

    Version createFlow(String flowId, String flowName, String initialJson);
    Page<FlowHead> flows(String query, int page, int pageSize);
    Page<Version> versions(String flowId, int page, int pageSize);
    Version version(String flowId, long version);
    Version createDraft(String flowId, long sourceVersion, StudioDocumentCodec codec);
    Version save(String flowId, long version, long expectedRevision, String flowName, String json);
    Version publish(String flowId, long version, long expectedRevision, String changeNote,
                    StudioDocumentCodec codec);
}
