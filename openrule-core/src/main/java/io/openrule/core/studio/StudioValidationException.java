package io.openrule.core.studio;

/** A compiler failure with a source location, independent of HTTP/Jackson. */
public final class StudioValidationException extends IllegalArgumentException {
    private final String pointer;
    private final String stageId;
    private final String nodeId;
    private final String itemId;

    public StudioValidationException(String message, String pointer, String stageId, String nodeId, String itemId) {
        super("OR-DEF-VALIDATION: " + message);
        this.pointer = pointer;
        this.stageId = stageId;
        this.nodeId = nodeId;
        this.itemId = itemId;
    }

    public String pointer() { return pointer; }
    public String stageId() { return stageId; }
    public String nodeId() { return nodeId; }
    public String itemId() { return itemId; }
}
