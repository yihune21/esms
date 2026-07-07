package et.com.cog.esms.core.identity;

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

import java.util.*;


@Slf4j
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final JwtTokenProvider tokenProvider;
    private final OtpService otpService;
    private final SessionService sessionService;
    private final LockoutService lockoutService;
    private final PasswordEncoder passwordEncoder;
    private final UserRepository userRepository;
    private final WorkspaceMemberRepository memberRepository;
    private final AuditService auditService;



   @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest req,
                                   HttpServletRequest httpReq) {
        String ip = getClientIp(httpReq);
        if (lockoutService.isIpBlocked(ip)) {
            auditService.log(null, "AUTH", "WARN", "LOGIN_BLOCKED_IP", "User", null);
            return problem(429, "Too many requests from this IP");
        }

        if (lockoutService.isLocked(req.getUsername())) {
            auditService.log(null, "AUTH", "WARN", "LOGIN_ACCOUNT_LOCKED", "User", null);
            return problem(423, "Account locked");
        }

        var userOpt = userRepository.findByUsername(req.getUsername());
        if (userOpt.isEmpty()) {
            lockoutService.recordFailure(req.getUsername(), ip);
            auditService.log(null, "AUTH", "WARN", "LOGIN_UNKNOWN_USERNAME", "User", null);
            return problem(401, "Invalid credentials");
        }

        var user = userOpt.get();
        if (!"ACTIVE".equals(user.getStatus())) {
            auditService.log(null, "AUTH", "WARN", "LOGIN_ACCOUNT_DISABLED", "User", user.getId());
            return problem(423, "Account disabled");
        }

        if (user.getPasswordHash() == null ||
            !passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            boolean locked = lockoutService.recordFailure(req.getUsername(), ip);
            if (locked) {
                auditService.log(null, "AUTH", "CRITICAL", "LOGIN_ACCOUNT_LOCKED_OUT", "User", user.getId());
                return problem(423, "Account locked");
            }
            auditService.log(null, "AUTH", "WARN", "LOGIN_BAD_PASSWORD", "User", user.getId());
            return problem(401, "Invalid credentials");
        }

        String otp = otpService.generateAndStore(user.getId().toString());
        log.info("OTP generated for user {} (dev mode, OTP={})", user.getUsername(), otp);

        // orchestrator.sendOtpSms(user.getMobileDecrypted(), otp);

        String preAuthToken = tokenProvider.createPreAuthToken(user.getId(), user.getUsername());
        lockoutService.clearFailures(req.getUsername());

        auditService.log(null, "AUTH", "INFO", "LOGIN_OTP_SENT", "User", user.getId());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("preAuthToken", preAuthToken);
        body.put("otpSentTo", maskPhone(user.getMobileHash()));
        body.put("expiresIn", 180);
        
        return ResponseEntity.ok(body);
    }

    @PostMapping("/verify-otp")
    public ResponseEntity<?> verifyOtp(@Valid @RequestBody OtpRequest req,
                                       HttpServletResponse response) {
        Claims claims = tokenProvider.parseToken(req.getPreAuthToken());
        if (claims == null || !"pre_auth".equals(tokenProvider.getTokenType(claims))) {
            auditService.log(null, "AUTH", "WARN", "VERIFY_BAD_CLAIM", "User", null);
            return problem(401, "Invalid or expired pre-auth token");
        }

        UUID userId = tokenProvider.getUserId(claims);
        String username = tokenProvider.getUsername(claims);

        var result = otpService.verify(userId.toString(), req.getOtp());
        return switch (result) {
            case VALID -> {
                var memberships = memberRepository.findByUserId(userId);
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

                Map<String, Object> body = new LinkedHashMap<>();
                body.put("accessToken", accessToken);
                body.put("expiresIn", tokenProvider.getAccessTokenTtl().toSeconds());
                body.put("refreshCookieSet", true);
                body.put("workspaces", workspaces);

                auditService.log(defaultWsId, "AUTH", "INFO", "LOGIN_SUCCESS", "User", userId);

                yield ResponseEntity.ok(body);
            }
            case INVALID -> {
                auditService.log(null, "AUTH", "WARN", "OTP_INVALID", "User", userId);
                yield problem(401, "Invalid OTP");
            }
            case EXPIRED -> {
                auditService.log(null, "AUTH", "WARN", "OTP_EXPIRED", "User", userId);
                yield problem(410, "OTP expired; restart login");
            }
            case MAX_ATTEMPTS -> {
                auditService.log(null, "AUTH", "CRITICAL", "OTP_MAX_ATTEMPTS", "User", userId);
                yield problem(410, "Maximum OTP attempts exceeded; restart login");
            }
        };
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


    @PostMapping("/resend-otp")
    public ResponseEntity<?> resendOtp(@Valid @RequestBody ResendOtpRequest req) {
        Claims claims = tokenProvider.parseToken(req.getPreAuthToken());
        if (claims == null || !"pre_auth".equals(tokenProvider.getTokenType(claims))) {
            auditService.log(null, "AUTH", "WARN", "RESEND_OTP_INVALID_TOKEN", "User", null);
            return problem(401, "Invalid or expired pre-auth token");
        }

        UUID userId = tokenProvider.getUserId(claims);
        if (!otpService.canResend(userId.toString())) {
            auditService.log(null, "AUTH", "WARN", "RESEND_OTP_RATE_LIMITED", "User", userId);
            return problem(429, "Please wait before requesting another OTP");
        }

        String otp = otpService.generateAndStore(userId.toString());
        otpService.markResendCooldown(userId.toString());
        log.info("OTP resent for user {} (dev mode, OTP={})", userId, otp);

        auditService.log(null, "AUTH", "INFO", "OTP_RESENT", "User", userId);

        return ResponseEntity.ok(Map.of("message", "OTP resent", "expiresIn", 180));
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

    private String maskPhone(String hash) {
        return hash != null && hash.length() > 4 ? "****" + hash.substring(hash.length() - 4) : "****";
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
    public static class OtpRequest {
        @NotBlank private String preAuthToken;
        @NotBlank private String otp;
    }

    @Data
    public static class ResendOtpRequest {
        @NotBlank private String preAuthToken;
    }

    @Data
    public static class SwitchWorkspaceRequest {
        private UUID workspaceId;
    }
}
