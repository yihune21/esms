package et.com.cog.esms.common.enums;

/**
 * Canonical message status — used across Core, Sender, and the DLR flow.
 */
public enum MessageStatus {
    PENDING,
    QUEUED,
    SENT,
    DELIVERED,
    FAILED,
    EXPIRED
}
