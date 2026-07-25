package et.com.cog.esms.common.sms;

import et.com.cog.esms.common.enums.Encoding;

/**
 * The single place that decides which alphabet a message is sent with and how
 * many segments that costs.
 *
 * Core and sender used to answer both questions independently and disagree:
 * the core counted segments from the TEMPLATE's declared encoding using
 * String.length(), while the sender ignored that entirely and re-detected the
 * alphabet from the body. An Amharic message on a GSM7-flagged template was
 * therefore recorded as 160-character GSM segments and actually sent as
 * 70-character UCS-2 ones, so message.segment_count -- which is what billing
 * and reporting read -- was wrong for exactly the traffic an Ethiopian insurer
 * sends most.
 *
 * Both modules now call these methods, so they cannot drift again.
 */
public final class SmsSegments {

    private SmsSegments() {}

    /** Single-segment limits. */
    public static final int MAX_GSM7 = 160;
    public static final int MAX_UCS2 = 70;

    /** Per-segment limits once a message is concatenated. */
    public static final int SEG_GSM7 = 153;
    public static final int SEG_UCS2 = 67;

    /**
     * The alphabet this body will actually go out with.
     *
     * A declared UCS2 is honoured as an explicit choice. A declared GSM7 is
     * only a request: text the GSM alphabet cannot represent is upgraded to
     * UCS-2 rather than being mangled, which is what the sender has always
     * done in practice.
     *
     * @param declared the template's encoding, or null when none was set
     */
    public static Encoding resolveEncoding(String body, String declared) {
        if ("UCS2".equalsIgnoreCase(declared)) {
            return Encoding.UCS2;
        }
        return Gsm0338.isEncodable(body) ? Encoding.GSM7 : Encoding.UCS2;
    }

    /** Segment count for the alphabet this body resolves to on its own. */
    public static int count(String body) {
        return count(body, resolveEncoding(body, null));
    }

    /**
     * Segment count under a known alphabet.
     *
     * Derived from the same splitters the gateway submits with, rather than a
     * ceil() over the length: a GSM segment that would end on a dangling ESC
     * gives up a septet to keep the pair together, so arithmetic alone can
     * undercount. This returns exactly the number of submit_sm calls the send
     * will make.
     */
    public static int count(String body, Encoding encoding) {
        if (body == null || body.isEmpty()) return 1;

        if (encoding == Encoding.UCS2) {
            return body.length() <= MAX_UCS2
                    ? 1
                    : Gsm0338.splitByCodeUnits(body, SEG_UCS2).size();
        }
        return Gsm0338.septetLength(body) <= MAX_GSM7
                ? 1
                : Gsm0338.splitBySeptets(body, SEG_GSM7).size();
    }
}
