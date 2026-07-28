package et.com.cog.esms.core.activeDirectory;

import et.com.cog.esms.core.audit.AuditService;
import et.com.cog.esms.core.identity.AppUser;
import et.com.cog.esms.core.identity.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mirrors an authenticated AD account onto the local {@code app_user} row that
 * the rest of the platform keys off.
 *
 * AD owns identity; eSMS owns authorisation. So a first successful bind creates
 * the row and nothing else — no workspace membership, therefore no permissions.
 * The account shows up in /users straight away and an administrator grants it a
 * workspace and role from there. Signing in and being able to do something are
 * deliberately two separate decisions: anyone in the NIC domain can pass the
 * bind, and that must not by itself grant access to a workspace's contacts or
 * the ability to send.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdUserProvisioningService {

    private final UserRepository userRepo;
    private final AuditService auditService;

    /**
     * Finds, links or creates the local row for an AD account, and refreshes
     * the attributes AD is authoritative for.
     */
    @Transactional
    public AppUser provision(AdUser adUser) {
        String sam = adUser.samAccountName();

        AppUser user = userRepo.findByAdSam(sam)
                // Not linked yet. An administrator may have pre-created this
                // person with a matching username (that is how every account
                // worked before AD); adopt that row rather than creating a
                // second one, which would fail the unique username constraint
                // and orphan their existing workspace membership.
                .or(() -> userRepo.findByUsername(sam))
                .orElse(null);

        if (user == null) {
            user = AppUser.builder()
                    .adSam(sam)
                    .username(sam)
                    .displayName(adUser.displayName())
                    .email(adUser.email())
                    .status("ACTIVE")
                    .failedLogins((short) 0)
                    // No passwordHash: this account authenticates against AD
                    // and has no local credential to guess or leak.
                    .build();
            AppUser created = userRepo.save(user);
            log.info("Provisioned new user from AD: sam={} id={} — no workspace membership yet, "
                    + "an administrator must assign one before they can do anything", sam, created.getId());
            auditService.log(null, "AUTH", "WARN", "USER_PROVISIONED_FROM_AD", "User", created.getId());
            return created;
        }

        boolean changed = false;
        if (user.getAdSam() == null) {
            user.setAdSam(sam);
            changed = true;
            log.info("Linked existing local account '{}' to AD account '{}'", user.getUsername(), sam);
            auditService.log(null, "AUTH", "WARN", "USER_LINKED_TO_AD", "User", user.getId());
        }
        // AD is the system of record for these, so a rename or a new mailbox
        // there lands here on the person's next sign-in.
        if (adUser.displayName() != null && !adUser.displayName().equals(user.getDisplayName())) {
            user.setDisplayName(adUser.displayName());
            changed = true;
        }
        if (adUser.email() != null && !adUser.email().equals(user.getEmail())) {
            user.setEmail(adUser.email());
            changed = true;
        }

        return changed ? userRepo.save(user) : user;
    }
}
