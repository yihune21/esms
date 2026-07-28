package et.com.cog.esms.core.identity;

import et.com.cog.esms.core.activeDirectory.AdAuthResult;
import et.com.cog.esms.core.activeDirectory.ActiveDirectoryAuthenticator;
import et.com.cog.esms.core.activeDirectory.AdUserProvisioningService;
import et.com.cog.esms.core.audit.AuditService;
import et.com.cog.esms.core.security.JwtTokenProvider;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;


/**
 * Sign-in is a single step: credentials in, session out.
 *
 * The SMS OTP that used to sit between them is gone. eSMS is reachable only
 * from the NIC LAN, so the second factor was protecting a door that is already
 * behind the perimeter — while costing a live SMS per login, making the login
 * path depend on the SMSC being up, and requiring every member of staff to have
 * a mobile number on file before they could work.
 *
 * Credentials are checked against Active Directory first
 * ({@link ActiveDirectoryAuthenticator}), with the local BCrypt hash as a
 * fallback for accounts AD does not know about — the seeded superadmin, and any
 * account created before the domain was wired up. See
 * {@link AdAuthResult#mayFallBackToLocal()} for exactly when that fallback is
 * allowed: never for an account AD knows and rejected.
 */
@Slf4j
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final JwtTokenProvider tokenProvider;
    private final SessionService sessionService;
    private final LockoutService lockoutService;
    private final PasswordEncoder passwordEncoder;
    private final UserRepository userRepository;
    private final WorkspaceMemberRepository memberRepository;
    private final AuditService auditService;
    private final ActiveDirectoryAuthenticator adAuthenticator;
    private final AdUserProvisioningService adProvisioningService;


    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest req,
                                   HttpServletRequest httpReq,
                                   HttpServletResponse response) {
        String ip = getClientIp(httpReq);
        if (lockoutService.isIpBlocked(ip)) {
            auditService.log(null, "AUTH", "WARN", "LOGIN_BLOCKED_IP", "User", null);
            return problem(429, "Too many requests from this IP");
        }

        if (lockoutService.isLocked(req.getUsername())) {
            auditService.log(null, "AUTH", "WARN", "LOGIN_ACCOUNT_LOCKED", "User", null);
            return problem(423, "Account locked");
        }

        AdAuthResult ad = adAuthenticator.authenticate(req.getUsername(), req.getPassword());

        AppUser user;
        if (ad.status() == AdAuthResult.Status.AUTHENTICATED) {
            // AD said yes. Create or refresh the local row that carries the
            // workspace membership and permissions.
            user = adProvisioningService.provision(ad.user());
            auditService.log(null, "AUTH", "INFO", "LOGIN_AD_BIND_OK", "User", user.getId());

        } else if (ad.mayFallBackToLocal()) {
            // AD does not hold this account, is switched off, or is unreachable.
            var localResult = authenticateLocally(req, ip);
            if (localResult.rejection() != null) return localResult.rejection();
            user = localResult.user();

        } else if (ad.status() == AdAuthResult.Status.ACCOUNT_DISABLED) {
            auditService.log(null, "AUTH", "WARN", "LOGIN_AD_ACCOUNT_DISABLED", "User", null);
            return problem(423, "Your domain account is disabled or its password has expired. "
                    + "Contact the IT department.");

        } else {
            // AD knows this account and rejected the password. Deliberately no
            // fallback to the local hash — a stale local password must not
            // outlive the directory's own answer.
            boolean locked = lockoutService.recordFailure(req.getUsername(), ip);
            auditService.log(null, "AUTH", locked ? "CRITICAL" : "WARN",
                    locked ? "LOGIN_ACCOUNT_LOCKED_OUT" : "LOGIN_AD_BAD_PASSWORD", "User", null);
            return locked ? problem(423, "Account locked") : problem(401, "Invalid credentials");
        }

        if (!"ACTIVE".equals(user.getStatus())) {
            // Deactivating someone in eSMS keeps them out even while their
            // domain account stays perfectly valid.
            auditService.log(null, "AUTH", "WARN", "LOGIN_ACCOUNT_DISABLED", "User", user.getId());
            return problem(423, "Account disabled");
        }

        lockoutService.clearFailures(req.getUsername());
        return issueSession(user.getId(), user.getUsername(), response);
    }

    /**
     * The pre-AD path, still used for accounts the directory does not hold.
     *
     * @return either the authenticated user or the response to send back —
     *         exactly one is non-null.
     */
    private LocalAuthOutcome authenticateLocally(LoginRequest req, String ip) {
        var userOpt = userRepository.findByUsername(req.getUsername());
        if (userOpt.isEmpty()) {
            lockoutService.recordFailure(req.getUsername(), ip);
            auditService.log(null, "AUTH", "WARN", "LOGIN_UNKNOWN_USERNAME", "User", null);
            return LocalAuthOutcome.rejected(problem(401, "Invalid credentials"));
        }

        var user = userOpt.get();

        // Once an account has bound against AD successfully, the directory owns
        // it — and keeps owning it while the DC is unreachable. Otherwise
        // anything that takes the domain controller off the network (including
        // an attacker who can) would silently re-enable a local password that
        // AD had already superseded, for every account in the system. The
        // break-glass path is preserved: the seeded superadmin, and anything
        // else AD has never held, has no ad_sam and still signs in here.
        if (adAuthenticator.isEnabled() && user.getAdSam() != null) {
            log.warn("Refusing local-password sign-in for directory account '{}' — "
                    + "Active Directory is authoritative for it and could not be reached",
                    user.getUsername());
            auditService.log(null, "AUTH", "CRITICAL", "LOGIN_AD_UNREACHABLE_NO_LOCAL_FALLBACK",
                    "User", user.getId());
            return LocalAuthOutcome.rejected(problem(503,
                    "Cannot reach the domain controller to verify your account. Please try again shortly."));
        }

        if (user.getPasswordHash() == null
                || !passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            boolean locked = lockoutService.recordFailure(req.getUsername(), ip);
            if (locked) {
                auditService.log(null, "AUTH", "CRITICAL", "LOGIN_ACCOUNT_LOCKED_OUT", "User", user.getId());
                return LocalAuthOutcome.rejected(problem(423, "Account locked"));
            }
            auditService.log(null, "AUTH", "WARN", "LOGIN_BAD_PASSWORD", "User", user.getId());
            return LocalAuthOutcome.rejected(problem(401, "Invalid credentials"));
        }

        if (adAuthenticator.isEnabled()) {
            // Worth seeing in the log: with AD on, every local-password login is
            // an account the directory does not hold.
            log.info("User '{}' signed in with a local password — no Active Directory entry",
                    user.getUsername());
        }
        return LocalAuthOutcome.authenticated(user);
    }

    private record LocalAuthOutcome(AppUser user, ResponseEntity<Map<String, Object>> rejection) {
        static LocalAuthOutcome authenticated(AppUser user) {
            return new LocalAuthOutcome(user, null);
        }
        static LocalAuthOutcome rejected(ResponseEntity<Map<String, Object>> rejection) {
            return new LocalAuthOutcome(null, rejection);
        }
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(HttpServletRequest request) {
        String refreshToken = extractRefreshCookie(request);
        if (refreshToken == null) {
            auditService.log(null, "AUTH", "WARN", "REFRESH_TOKEN_MISSING", "User", null);
            return problem(401, "Refresh token missing");
        }

        Claims claims = tokenProvider.parseToken(refreshToken);
        if (claims == null || !"refresh".equals(tokenProvider.getTokenType(claims))) {
            auditService.log(null, "AUTH", "WARN", "REFRESH_TOKEN_INVALID", "User", null);
            return problem(401, "Refresh token invalid or revoked");
        }

        String refreshJti = tokenProvider.getJti(claims);
        if (!sessionService.isRefreshTokenValid(refreshJti)) {
            auditService.log(null, "AUTH", "WARN", "REFRESH_TOKEN_REVOKED", "User", tokenProvider.getUserId(claims));
            return problem(401, "Refresh token invalid or revoked");
        }

        UUID userId = tokenProvider.getUserId(claims);

        if (!sessionService.isSessionActive(userId)) {
            sessionService.revokeRefreshToken(refreshJti);
            auditService.log(null, "AUTH", "WARN", "SESSION_IDLE_TIMEOUT", "User", userId);
            return ResponseEntity.status(440)
                    .header("Content-Type", "application/problem+json")
                    .body(Map.of("type", "/errors/auth", "title", "Idle timeout", "status", 440));
        }

        var memberships = memberRepository.findByUserId(userId);
        UUID wsId = memberships.isEmpty() ? null : memberships.get(0).getWorkspace().getId();
        String role = memberships.isEmpty() ? null : memberships.get(0).getRole().getCode();
        List<String> perms = memberships.isEmpty() ? List.of()
                : memberships.get(0).getRole().getPermissionCodes();
        String username = memberships.isEmpty() ? userId.toString()
                : userRepository.findById(userId).map(u -> u.getUsername()).orElse(userId.toString());

        String newAccessToken = tokenProvider.createAccessToken(userId, username, wsId, role, perms);
        sessionService.touchIdle(userId);

        auditService.log(wsId, "AUTH", "INFO", "TOKEN_REFRESHED", "User", userId);

        return ResponseEntity.ok(Map.of(
                "accessToken", newAccessToken,
                "expiresIn", tokenProvider.getAccessTokenTtl().toSeconds()
        ));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest request, HttpServletResponse response) {
        UUID userId = null;

        String refreshToken = extractRefreshCookie(request);
        if (refreshToken != null) {
            Claims claims = tokenProvider.parseToken(refreshToken);
            if (claims != null) {
                userId = tokenProvider.getUserId(claims);
                sessionService.revokeRefreshToken(tokenProvider.getJti(claims));
            }
        }

        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            Claims accessClaims = tokenProvider.parseToken(authHeader.substring(7));
            if (accessClaims != null) {
                if (userId == null) {
                    userId = tokenProvider.getUserId(accessClaims);
                }
                sessionService.denyAccessToken(tokenProvider.getJti(accessClaims),
                        tokenProvider.getAccessTokenTtl());
            }
        }

        Cookie cookie = new Cookie("refreshToken", "");
        cookie.setHttpOnly(true);
        cookie.setSecure(true);
        cookie.setPath("/auth");
        cookie.setMaxAge(0);
        response.addCookie(cookie);

        auditService.log(null, "AUTH", "INFO", "LOGOUT", "User", userId);

        return ResponseEntity.ok(Map.of("message", "Logged out"));
    }

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> me() {
        UUID userId = et.com.cog.esms.core.security.WorkspaceContext.currentUserId();
        if (userId == null) {
            return problem(401, "Not authenticated");
        }

        var userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty()) {
            return problem(404, "User not found");
        }
        var user = userOpt.get();
        var memberships = memberRepository.findByUserId(userId);

        List<Map<String, Object>> workspaces = memberships.stream().map(m -> {
            Map<String, Object> ws = new LinkedHashMap<>();
            ws.put("id",       m.getWorkspace().getId());
            ws.put("name",     m.getWorkspace().getName());
            ws.put("code",     m.getWorkspace().getCode());
            ws.put("division", m.getWorkspace().getDivision());
            ws.put("role",     m.getRole().getCode());
            ws.put("status",   m.getWorkspace().getStatus());
            return ws;
        }).toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id",          user.getId());
        body.put("username",    user.getUsername());
        body.put("displayName", user.getDisplayName());
        body.put("email",       user.getEmail());
        body.put("status",      user.getStatus());
        body.put("lastLoginAt", user.getLastLoginAt());
        // Lets the UI hide password-change controls for directory-backed
        // accounts, whose password lives in AD and cannot be changed here.
        body.put("directoryAccount", user.getAdSam() != null);
        body.put("workspaces",  workspaces);
        body.put("currentWorkspaceId",
                et.com.cog.esms.core.security.WorkspaceContext.currentWorkspaceId());

        return ResponseEntity.ok(body);
    }


    @PostMapping("/switch-workspace")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> switchWorkspace(
            @Valid @RequestBody SwitchWorkspaceRequest req) {

        UUID userId = et.com.cog.esms.core.security.WorkspaceContext.currentUserId();
        if (userId == null) {
            return problem(401, "Not authenticated");
        }

        var memberships = memberRepository.findByUserId(userId);

        var targetMembership = memberships.stream()
                .filter(m -> m.getWorkspace().getId().equals(req.getWorkspaceId()))
                .findFirst();

        if (targetMembership.isEmpty()) {
            auditService.log(req.getWorkspaceId(), "AUTH", "WARN",
                    "SWITCH_WORKSPACE_NOT_MEMBER", "Workspace", req.getWorkspaceId());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("title", "You are not a member of that workspace"));
        }

        var membership = targetMembership.get();
        var workspace  = membership.getWorkspace();
        if (!"ACTIVE".equals(workspace.getStatus())) {
            auditService.log(workspace.getId(), "AUTH", "WARN",
                    "SWITCH_WORKSPACE_INACTIVE", "Workspace", workspace.getId());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("title", "Workspace is not active"));
        }

        var user = userRepository.findById(userId).orElseThrow();
        List<String> perms = membership.getRole().getPermissionCodes();

        String newAccessToken = tokenProvider.createAccessToken(
                userId, user.getUsername(),
                workspace.getId(), membership.getRole().getCode(), perms);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accessToken",  newAccessToken);
        body.put("expiresIn",    tokenProvider.getAccessTokenTtl().toSeconds());
        body.put("workspaceId",  workspace.getId());
        body.put("workspaceName",workspace.getName());
        body.put("role",         membership.getRole().getCode());

        auditService.log(workspace.getId(), "AUTH", "INFO",
                "SWITCH_WORKSPACE", "Workspace", workspace.getId());

        return ResponseEntity.ok(body);
    }


    /**
     * Issues the access token, refresh cookie and session for a user who has
     * cleared authentication.
     */
    private ResponseEntity<?> issueSession(UUID userId, String username,
                                           HttpServletResponse response) {
        var memberships = memberRepository.findByUserId(userId);

        // A user has exactly one workspace membership (SUPER_ADMIN has none).
        // If that workspace was deactivated, reject before ever issuing an
        // access token - otherwise the user would sign in successfully only to
        // be blocked on the very next request.
        boolean isSuperAdmin = memberships.stream()
                .anyMatch(m -> "SUPER_ADMIN".equals(m.getRole().getCode()));
        if (!isSuperAdmin && !memberships.isEmpty()
                && !"ACTIVE".equals(memberships.get(0).getWorkspace().getStatus())) {
            auditService.log(memberships.get(0).getWorkspace().getId(), "AUTH", "WARN",
                    "LOGIN_WORKSPACE_DEACTIVATED", "User", userId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .header("Content-Type", "application/problem+json")
                    .body(Map.of("type", "/errors/auth", "status", 403, "code", "WORKSPACE_DEACTIVATED",
                            "title", "Your workspace has been deactivated. Contact your administrator."));
        }

        List<Map<String, Object>> workspaces = memberships.stream()
                .map(m -> {
                    Map<String, Object> ws = new LinkedHashMap<>();
                    ws.put("id", m.getWorkspace().getId());
                    ws.put("code", m.getWorkspace().getCode());
                    ws.put("role", m.getRole().getCode());
                    return ws;
                }).toList();

        UUID defaultWsId = memberships.isEmpty() ? null : memberships.get(0).getWorkspace().getId();
        String defaultRole = memberships.isEmpty() ? null : memberships.get(0).getRole().getCode();
        List<String> perms = memberships.isEmpty() ? List.of()
                : memberships.get(0).getRole().getPermissionCodes();

        String accessToken = tokenProvider.createAccessToken(userId, username,
                defaultWsId, defaultRole, perms);
        String refreshToken = tokenProvider.createRefreshToken(userId);
        Claims refreshClaims = tokenProvider.parseToken(refreshToken);
        String refreshJti = tokenProvider.getJti(refreshClaims);

        sessionService.createSession(userId, refreshJti);

        Cookie cookie = new Cookie("refreshToken", refreshToken);
        cookie.setHttpOnly(true);
        cookie.setSecure(true);
        cookie.setPath("/auth");
        cookie.setMaxAge((int) tokenProvider.getRefreshTokenTtl().toSeconds());
        response.addCookie(cookie);

        userRepository.findById(userId).ifPresent(u -> {
            u.setLastLoginAt(Instant.now());
            userRepository.save(u);
        });

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accessToken", accessToken);
        body.put("expiresIn", tokenProvider.getAccessTokenTtl().toSeconds());
        body.put("refreshCookieSet", true);
        body.put("workspaces", workspaces);
        // An AD account that no administrator has placed in a workspace yet can
        // sign in but holds no permissions. Saying so explicitly stops the UI
        // rendering an empty dashboard with no explanation.
        body.put("awaitingWorkspaceAssignment", workspaces.isEmpty());
        auditService.log(defaultWsId, "AUTH", "INFO", "LOGIN_SUCCESS", "User", userId);

        return ResponseEntity.ok(body);
    }

    private String extractRefreshCookie(HttpServletRequest request) {
        if (request.getCookies() != null) {
            for (Cookie c : request.getCookies()) {
                if ("refreshToken".equals(c.getName())) {
                    return c.getValue();
                }
            }
        }
        return null;
    }

    private String getClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        return xff != null ? xff.split(",")[0].trim() : request.getRemoteAddr();
    }

    private ResponseEntity<Map<String, Object>> problem(int status, String title) {
        return ResponseEntity.status(status)
                .header("Content-Type", "application/problem+json")
                .body(Map.of("type", "/errors/auth", "title", title, "status", status));
    }


    @Data
    public static class LoginRequest {
        @NotBlank private String username;
        @NotBlank private String password;
    }

    @Data
    public static class SwitchWorkspaceRequest {
        private UUID workspaceId;
    }
}
