package et.com.cog.esms.common.enums;

/**
 * Message encoding — determines SMS segment calculation.
 * GSM7: 160 chars/segment. UCS2: 70 chars/segment (Amharic/non-Latin).
 */
public enum Encoding {
    GSM7,
    UCS2
}
