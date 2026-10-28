package io.openrule.spring.fact;

import java.util.List;

/** Values are internal; only FactResolutionReport may be projected to wire output. */
public record FactBatchResult(List<Field> fields, boolean attempted, String errorCode) {
    public FactBatchResult { fields = List.copyOf(fields); }
    public enum Status { resolved, missing, failed }
    public record Field(String path, Status status, Object value, String errorCode) {
        public Field {
            java.util.Objects.requireNonNull(path); java.util.Objects.requireNonNull(status);
            if (status == Status.resolved && errorCode != null || status != Status.resolved && errorCode == null)
                throw new IllegalArgumentException("Invalid field result");
            value = status == Status.resolved ? FactValues.freeze(value) : null;
        }
        @Override public String toString() { return "Field[path=" + path + ",status=" + status + "]"; }
    }
    public static FactBatchResult failed(FactBatchRequest request, String code, boolean attempted) {
        return new FactBatchResult(request.fields().stream().map(f -> new Field(f.path(), Status.failed, null, code)).toList(), attempted, code);
    }
}
