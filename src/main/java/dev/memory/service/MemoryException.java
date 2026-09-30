package dev.memory.service;

public class MemoryException extends RuntimeException {
    public enum Kind { NOT_FOUND, CONFLICT, PROVIDER_UNAVAILABLE, INVALID_PROVIDER_OUTPUT, INVALID_REQUEST }
    private final Kind kind;
    public MemoryException(Kind kind, String safeMessage) { super(safeMessage); this.kind = kind; }
    public Kind kind() { return kind; }
    public static MemoryException conflict() {
        return new MemoryException(Kind.CONFLICT, "Memory changed concurrently; reload and retry.");
    }
    public static MemoryException malformed() {
        return new MemoryException(Kind.INVALID_PROVIDER_OUTPUT, "AI provider returned invalid structured output.");
    }
}
