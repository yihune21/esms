package et.com.cog.esms.common.enums;

/**
 * Carrier identity — used by both Core (to resolve at orchestration time)
 * and Sender (to pick the gateway adapter).
 */
public enum Carrier {
    /** NIB internal SMSC — SMPP 3.4 aggregator that routes to all Ethiopian carriers */
    NIB_SMSC
}
