package et.com.cog.esms.core.activeDirectory;

/**
 * What Active Directory knows about someone who just signed in. Only the
 * attributes eSMS mirrors onto app_user — AD stays the system of record for
 * all of them.
 *
 * @param samAccountName the login name; unique in the domain and what we key
 *                       app_user.ad_sam on
 * @param displayName    human name for the UI, never null (falls back to the
 *                       account name when AD has no displayName set)
 * @param email          mail attribute, or null
 * @param distinguishedName the DN the authenticating bind succeeded against
 */
public record AdUser(String samAccountName,
                     String displayName,
                     String email,
                     String distinguishedName) {
}
