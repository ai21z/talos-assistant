package dev.loqj.runtime;

/**
 * Default approval gate that always approves.
 * Used in V1 where no sensitive actions exist yet.
 */
public final class NoOpApprovalGate implements ApprovalGate {
    @Override
    public boolean approve(String description, String detail) {
        return true;
    }
}
