package io.openrule.spring.studio;

import java.util.List;

public final class StudioProblem extends RuntimeException {
    private final int status;
    private final String code;
    private final List<StudioDocumentCodec.Issue> issues;
    private final Object execution;

    public StudioProblem(int status, String code, String message) {
        this(status, code, message, List.of());
    }

    public StudioProblem(int status, String code, String message, List<StudioDocumentCodec.Issue> issues) {
        this(status, code, message, issues, null);
    }

    public StudioProblem(int status, String code, String message, List<StudioDocumentCodec.Issue> issues,
                         Object execution) {
        super(message);
        this.status = status;
        this.code = code;
        this.issues = List.copyOf(issues);
        this.execution = execution;
    }

    public int status() { return status; }
    public String code() { return code; }
    public List<StudioDocumentCodec.Issue> issues() { return issues; }
    public Object execution() { return execution; }
}
