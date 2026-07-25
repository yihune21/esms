package et.com.cog.esms.common.sms;

import et.com.cog.esms.common.enums.Encoding;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The core records segment_count and the sender decides what to submit. These
 * pin the shared rule both now use, so the two cannot drift apart again.
 */
class SmsSegmentsTest {

    private static final String AMHARIC = "\u1230\u120B\u121D";

    @Test
    @DisplayName("a GSM7-declared template carrying Amharic still resolves to UCS-2")
    void amharicOverridesDeclaredGsm7() {
        assertEquals(Encoding.UCS2, SmsSegments.resolveEncoding(AMHARIC, "GSM7"),
                "this is the mismatch: core recorded GSM7 while the sender sent UCS-2");
        assertEquals(Encoding.GSM7, SmsSegments.resolveEncoding("plain ascii", "GSM7"));
    }

    @Test
    @DisplayName("an explicit UCS2 declaration is honoured even for ASCII")
    void explicitUcs2IsHonoured() {
        assertEquals(Encoding.UCS2, SmsSegments.resolveEncoding("plain ascii", "UCS2"));
    }

    @Test
    @DisplayName("no declaration falls back to detecting from the body")
    void nullDeclarationAutoDetects() {
        assertEquals(Encoding.GSM7, SmsSegments.resolveEncoding("plain ascii", null));
        assertEquals(Encoding.UCS2, SmsSegments.resolveEncoding(AMHARIC, null));
    }

    @Test
    @DisplayName("Amharic is counted in UCS-2 units, not the old 160/153 char rule")
    void amharicSegmentCountUsesUcs2Limits() {
        String body = AMHARIC.repeat(40);          // 120 chars
        assertEquals(120, body.length());

        // Old core behaviour: GSM7 rules over raw length -> ceil(120/153) = 1.
        // Real send: UCS-2, 120 units over a 70 limit -> 2 segments.
        assertEquals(2, SmsSegments.count(body, SmsSegments.resolveEncoding(body, "GSM7")));
    }

    @Test
    @DisplayName("boundaries: one segment up to the limit, two just past it")
    void boundaries() {
        assertEquals(1, SmsSegments.count("a".repeat(160), Encoding.GSM7));
        assertEquals(2, SmsSegments.count("a".repeat(161), Encoding.GSM7));
        assertEquals(1, SmsSegments.count(AMHARIC.repeat(23) + "a", Encoding.UCS2)); // 70 units
        assertEquals(2, SmsSegments.count(AMHARIC.repeat(23) + "ab", Encoding.UCS2)); // 71 units
    }

    @Test
    @DisplayName("extension characters are counted as two septets, not one char")
    void extensionCharactersCostTwoSeptets() {
        String body = "[".repeat(81);              // 81 chars but 162 septets
        assertEquals(81, body.length());
        assertEquals(162, Gsm0338.septetLength(body));
        // Old rule: 81 chars <= 160 -> 1 segment. Truth: 162 septets -> 2.
        assertEquals(2, SmsSegments.count(body, Encoding.GSM7));
    }

    @Test
    @DisplayName("the count equals the number of segments actually submitted")
    void countMatchesTheSplitterTheGatewayUses() {
        String body = "a[".repeat(100);            // 300 septets
        assertEquals(Gsm0338.splitBySeptets(body, SmsSegments.SEG_GSM7).size(),
                SmsSegments.count(body, Encoding.GSM7));

        String wide = AMHARIC.repeat(100);         // 300 UCS-2 units
        assertEquals(Gsm0338.splitByCodeUnits(wide, SmsSegments.SEG_UCS2).size(),
                SmsSegments.count(wide, Encoding.UCS2));
    }

    @Test
    @DisplayName("empty and null bodies still cost one segment")
    void emptyBodyIsOneSegment() {
        assertEquals(1, SmsSegments.count("", Encoding.GSM7));
        assertEquals(1, SmsSegments.count(null, Encoding.GSM7));
    }
}
