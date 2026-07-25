package et.com.cog.esms.core.identity;

import et.com.cog.esms.core.audit.AuditService;
import et.com.cog.esms.core.security.JwtTokenProvider;
import et.com.cog.esms.core.util.PhoneNumbers;
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
    private final OtpSmsService otpSmsService;
    private final et.com.cog.esms.core.security.MobileCipher mobileCipher;

    /**
     * The single account whose OTP may be written to the server log instead of
     * sent, and only while it still has no mobile number, so a freshly seeded
     * deployment can be entered once to configure one. The OTP step itself is
     * never skipped. Set to an empty value to disable this entirely.
     */
    @org.springframework.beans.factory.annotation.Value("${app.security.otp.bootstrap-username:superadmin}")
    private String bootstrapAdminUsername;



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

        // The OTP is never logged for a normal account. It used to be written
        // to the application log for EVERY user in place of being sent, which
        // made every account reachable by anyone who could read logs. The one
        // remaining exception is the first-run case below.
        String mobile = mobileCipher.decrypt(user.getMobileEnc());
        boolean canSms = PhoneNumbers.isValidE164(mobile);

        // First-run only. The seeded administrator has no number, and a number
        // can only be set from inside the application, so a deployment would
        // otherwise be locked out of its own admin account. The OTP step is
        // still enforced in full - the code is simply written to the server log
        // instead of being sent, so whoever is standing up the system can read
        // it there. Signing in therefore still requires server access, which a
        // password-only bypass would not.
        //
        // Deliberately narrow: only the configured bootstrap username, only
        // while that account has no number, and audited CRITICAL each time. The
        // moment a number is saved this stops applying and the code is never
        // logged again.
        boolean firstRunLoggedOtp = !canSms
                && user.getUsername().equalsIgnoreCase(bootstrapAdminUsername);

        if (!canSms && !firstRunLoggedOtp) {
            auditService.log(null, "AUTH", "CRITICAL", "LOGIN_NO_MOBILE_ON_FILE", "User", user.getId());
            log.error("User {} has no usable mobile number on file - cannot deliver an OTP", user.getUsername());
            return problem(409, "No mobile number is registered for this account. Contact your administrator.");
        }

        String otp = otpService.generateAndStore(user.getId().toString());

        if (firstRunLoggedOtp) {
            // Format kept greppable so the documented
            // "docker compose logs esms-core | grep -i OTP" still works.
            log.warn("FIRST-RUN LOGIN for {} - no mobile number on file, so the code was not sent. "
                    + "OTP={} — enter it to finish signing in, then set a mobile number from the "
                    + "user screen. Once set, codes go by SMS and are never logged again.",
                    user.getUsername(), otp);
            auditService.log(null, "AUTH", "CRITICAL", "LOGIN_OTP_WRITTEN_TO_LOG_NO_MOBILE",
                    "User", user.getId());
        } else {
            try {
                otpSmsService.send(user.getId(), mobile, otp);
            } catch (Exception e) {
                // Do not leave a live OTP sitting in Redis for a code that was
                // never delivered.
                otpService.invalidate(user.getId().toString());
                auditService.log(null, "AUTH", "CRITICAL", "LOGIN_OTP_SEND_FAILED", "User", user.getId());
                log.error("Failed to queue the OTP SMS for user {}: {}", user.getUsername(), e.getMessage(), e);
                return problem(503, "Could not send the verification code. Please try again.");
            }
        }

        String preAuthToken = tokenProvider.createPreAuthToken(user.getId(), user.getUsername());
        lockoutService.clearFailures(req.getUsername());

        auditService.log(null, "AUTH", "INFO", "LOGIN_OTP_SENT", "User", user.getId());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("preAuthToken", preAuthToken);
        // Masks the real number. This used to mask mobile_hash, so it returned
        // the last four characters of a SHA-256 digest.
        body.put("otpSentTo", firstRunLoggedOtp
                ? "the esms-core server log (first-run setup)"
                : PhoneNumbers.mask(mobile));
        body.put("otpRequired", true);
        // Lets the UI prompt for a number once this session starts; without one
        // the next login would fall back to the log again.
        body.put("mobileRequired", firstRunLoggedOtp);
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
            case VALID -> issueSession(userId, username, response);

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

        var user = userRepository.findById(userId).orElse(null);
        String mobile = user != null ? mobileCipher.decrypt(user.getMobileEnc()) : null;
        boolean canSms = PhoneNumbers.isValidE164(mobile);
        // Same first-run rule as /auth/login, so "Resend OTP" keeps working
        // during setup instead of dead-ending on a 409.
        boolean firstRunLoggedOtp = !canSms && user != null
                && user.getUsername().equalsIgnoreCase(bootstrapAdminUsername);

        if (!canSms && !firstRunLoggedOtp) {
            auditService.log(null, "AUTH", "CRITICAL", "RESEND_OTP_NO_MOBILE", "User", userId);
            return problem(409, "No mobile number is registered for this account. Contact your administrator.");
        }

        String otp = otpService.generateAndStore(userId.toString());
        if (firstRunLoggedOtp) {
            log.warn("FIRST-RUN LOGIN for {} - resent code not sent (no mobile number on file). OTP={}",
                    user.getUsername(), otp);
            auditService.log(null, "AUTH", "CRITICAL", "RESEND_OTP_WRITTEN_TO_LOG_NO_MOBILE", "User", userId);
        } else {
            try {
                otpSmsService.send(userId, mobile, otp);
            } catch (Exception e) {
                otpService.invalidate(userId.toString());
                auditService.log(null, "AUTH", "CRITICAL", "RESEND_OTP_SEND_FAILED", "User", userId);
                log.error("Failed to queue the resent OTP SMS for user {}: {}", userId, e.getMessage(), e);
                return problem(503, "Could not send the verification code. Please try again.");
            }
        }
        otpService.markResendCooldown(userId.toString());

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


    /**
     * Issues the access token, refresh cookie and session for a user who has
     * cleared the OTP step.
     *
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
