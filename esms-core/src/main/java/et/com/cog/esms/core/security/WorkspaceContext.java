package et.com.cog.esms.core.security;

import lombok.Getter;

import java.util.List;
import java.util.UUID;


public final class WorkspaceContext {

    private static final ThreadLocal<WorkspaceInfo> CONTEXT = new ThreadLocal<>();

    private WorkspaceContext() {}

    public static void set(UUID workspaceId, UUID userId, String roleCode,
                           List<String> permissions, boolean delegating) {
        CONTEXT.set(new WorkspaceInfo(workspaceId, userId, roleCode, permissions, delegating));
    }

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
