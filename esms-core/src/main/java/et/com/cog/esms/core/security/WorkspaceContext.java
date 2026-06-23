package et.com.cog.esms.core.security;

import lombok.Getter;

import java.util.List;
import java.util.UUID;

/**
 * Thread-local holder for the current request's workspace context.
 * Populated by JwtAuthenticationFilter from the JWT workspace_id claim.
 * Used by tenant-aware repositories to scope all queries.
 *
 * The {@code effectivePermissions} list may be wider than what the JWT originally
 * contained if the user is an active delegate (Option B delegation injection).
 * Use {@code isDelegating()} to distinguish this at call sites if needed.
 */
public final class WorkspaceContext {

    private static final ThreadLocal<WorkspaceInfo> CONTEXT = new ThreadLocal<>();

    private WorkspaceContext() {}

    /** Called by JwtAuthenticationFilter after delegation injection. */
    public static void set(UUID workspaceId, UUID userId, String roleCode,
                           List<String> permissions, boolean delegating) {
        CONTEXT.set(new WorkspaceInfo(workspaceId, userId, roleCode, permissions, delegating));
    }

    /** Backwards-compat overload — no delegation in effect. */
    public static void set(UUID workspaceId, UUID userId, String roleCode, List<String> permissions) {
        set(workspaceId, userId, roleCode, permissions, false);
    }

    public static WorkspaceInfo current() {
        return CONTEXT.get();
    }

    public static UUID currentWorkspaceId() {
        WorkspaceInfo info = CONTEXT.get();
        return info != null ? info.getWorkspaceId() : null;
    }

    public static UUID currentUserId() {
        WorkspaceInfo info = CONTEXT.get();
        return info != null ? info.getUserId() : null;
    }

    public static boolean hasPermission(String permissionCode) {
        WorkspaceInfo info = CONTEXT.get();
        return info != null && info.getPermissions() != null
                && info.getPermissions().contains(permissionCode);
    }

    /**
     * Returns true if this request's authority set was augmented by an active
     * Delegation record (i.e. the user is acting as someone's delegate).
     * Useful for audit logging or conditional UI hints.
     */
    public static boolean isDelegating() {
        WorkspaceInfo info = CONTEXT.get();
        return info != null && info.isDelegating();
    }

    public static void clear() {
        CONTEXT.remove();
    }

    @Getter
    public static class WorkspaceInfo {
        private final UUID         workspaceId;
        private final UUID         userId;
        private final String       roleCode;
        private final List<String> permissions;
        private final boolean      delegating;

        public WorkspaceInfo(UUID workspaceId, UUID userId, String roleCode,
                             List<String> permissions, boolean delegating) {
            this.workspaceId = workspaceId;
            this.userId      = userId;
            this.roleCode    = roleCode;
            this.permissions = permissions;
            this.delegating  = delegating;
        }
    }
}
