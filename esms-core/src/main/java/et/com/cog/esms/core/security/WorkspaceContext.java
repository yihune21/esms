package et.com.cog.esms.core.security;

import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

/**
 * Thread-local holder for the current request's workspace context.
 * Populated by WorkspaceFilter from the JWT workspace_id claim.
 * Used by tenant-aware repositories to scope all queries.
 */
public final class WorkspaceContext {

    private static final ThreadLocal<WorkspaceInfo> CONTEXT = new ThreadLocal<>();

    private WorkspaceContext() {}

    public static void set(UUID workspaceId, UUID userId, String roleCode, List<String> permissions) {
        CONTEXT.set(new WorkspaceInfo(workspaceId, userId, roleCode, permissions));
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

    public static void clear() {
        CONTEXT.remove();
    }

    @Getter
    @Setter
    public static class WorkspaceInfo {
        private final UUID workspaceId;
        private final UUID userId;
        private final String roleCode;
        private final List<String> permissions;

        public WorkspaceInfo(UUID workspaceId, UUID userId, String roleCode, List<String> permissions) {
            this.workspaceId = workspaceId;
            this.userId = userId;
            this.roleCode = roleCode;
            this.permissions = permissions;
        }
    }
}
