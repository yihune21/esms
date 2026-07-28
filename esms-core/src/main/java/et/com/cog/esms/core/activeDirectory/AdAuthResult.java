package et.com.cog.esms.core.activeDirectory;

/**
 * Outcome of an Active Directory sign-in attempt.
 *
 * The distinction that matters is NOT_IN_DIRECTORY versus BAD_CREDENTIALS. AD
 * is authoritative for anyone it knows: if the account exists there and the
 * password was wrong, the attempt is rejected outright. Falling through to the
 * local password hash in that case would mean a stale local password kept
 * working after AD had been updated, which is exactly what directory-backed
 * auth is supposed to prevent. Only NOT_IN_DIRECTORY — the seeded superadmin,
 * or a service account that never existed in AD — is allowed to fall back.
 */
public record AdAuthResult(Status status, AdUser user) {

    public enum Status {
        /** Bind succeeded; {@link #user()} is populated. */
        AUTHENTICATED,
        /** No such sAMAccountName under the search base. Caller may fall back. */
        NOT_IN_DIRECTORY,
        /** The account exists but the password was rejected. Caller must not fall back. */
        BAD_CREDENTIALS,
        /** The account exists but AD has it disabled, expired or locked out. */
        ACCOUNT_DISABLED,
        /** The directory could not be reached or the service account itself failed to bind. */
        DIRECTORY_UNAVAILABLE,
        /** AD integration is switched off (app.ad.enabled=false). Caller falls back. */
        DISABLED
    }

    public static AdAuthResult authenticated(AdUser user) {
        return new AdAuthResult(Status.AUTHENTICATED, user);
    }

    public static AdAuthResult of(Status status) {
        return new AdAuthResult(status, null);
    }

    /** True when the caller is allowed to try the local password hash instead. */
    public boolean mayFallBackToLocal() {
        return status == Status.NOT_IN_DIRECTORY
                || status == Status.DISABLED
                || status == Status.DIRECTORY_UNAVAILABLE;
    }
}
