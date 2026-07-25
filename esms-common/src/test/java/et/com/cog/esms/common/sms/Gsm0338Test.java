package et.com.cog.esms.common.sms;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the GSM 03.38 table against the two ways it is easy to break:
 * dropping the escape slot at 0x1B (which silently shifts every code point
 * above it), and re-introducing the ISO-8859-1 shortcut this codec replaces.
 *
 * Non-ASCII characters are written as unicode escapes on purpose, so the
 * expectations survive any file-encoding round trip.
 */
class Gsm0338Test {

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02X", b));
        return sb.toString();
    }

    @Test
    @DisplayName("characters whose GSM code point differs from Latin-1 encode correctly")
    void encodesCharactersLatin1GotWrong() {
        assertEquals("00", hex(Gsm0338.encode("@")),   "@ is 0x00 in GSM but 0x40 in Latin-1");
        assertEquals("01", hex(Gsm0338.encode("\u00A3")), "pound is 0x01 in GSM but 0xA3 in Latin-1");
        assertEquals("11", hex(Gsm0338.encode("_")),   "_ is 0x11 in GSM but 0x5F in Latin-1");
        assertEquals("04", hex(Gsm0338.encode("\u00E8")), "e-grave is 0x04 in GSM but 0xE8 in Latin-1");
        assertEquals("10", hex(Gsm0338.encode("\u0394")), "Delta is 0x10 in GSM, unmappable in Latin-1");
        assertEquals("24", hex(Gsm0338.encode("\u00A4")), "currency is 0x24 in GSM but 0xA4 in Latin-1");
        assertEquals("40", hex(Gsm0338.encode("\u00A1")), "inverted-! is 0x40 in GSM but 0xA1 in Latin-1");
        assertEquals("5B", hex(Gsm0338.encode("\u00C4")), "A-umlaut is 0x5B in GSM but 0xC4 in Latin-1");
    }

    @Test
    @DisplayName("the escape slot sits at 0x1B, so letters and digits keep ASCII values")
    void tableIsNotShifted() {
        assertEquals("41", hex(Gsm0338.encode("A")));
        assertEquals("5A", hex(Gsm0338.encode("Z")));
        assertEquals("61", hex(Gsm0338.encode("a")));
        assertEquals("30", hex(Gsm0338.encode("0")));
        assertEquals("20", hex(Gsm0338.encode(" ")));
        assertEquals("1F", hex(Gsm0338.encode("\u00C9")), "E-acute sits immediately below space");
    }

    @Test
    @DisplayName("plain ASCII is unchanged from the previous ISO-8859-1 output")
    void asciiIsByteIdenticalToOldBehaviour() {
        String body = "NIB Insurance: policy 12345 expires in 30 days. Call 0911223344.";
        assertEquals(hex(body.getBytes(StandardCharsets.ISO_8859_1)), hex(Gsm0338.encode(body)));
    }

    @Test
    @DisplayName("extension characters are ESC pairs costing two septets")
    void extensionCharacters() {
        assertEquals("1B3C", hex(Gsm0338.encode("[")));
        assertEquals("1B65", hex(Gsm0338.encode("\u20AC")));
        assertEquals(2, Gsm0338.septetLength("["));
        assertEquals(6, Gsm0338.septetLength("ab[cd"));
        assertEquals(5, "ab[cd".length(), "String.length() is not a septet count");
    }

    @Test
    @DisplayName("non-GSM text is rejected so callers fall back to UCS-2")
    void detectsNonGsmText() {
        assertFalse(Gsm0338.isEncodable("\u1200\u1208"), "Amharic must route to UCS-2");
        assertTrue(Gsm0338.isEncodable("caf\u00E9 \u00C7"));
        assertTrue(Gsm0338.isEncodable("a[b]c"));
        assertThrows(IllegalArgumentException.class, () -> Gsm0338.encode("\u1200"));
    }

    @Test
    @DisplayName("segments respect the septet limit and never end on a dangling ESC")
    void splitsBySeptetsNotChars() {
        String body = "a[".repeat(100);            // 100 chars, 300 septets
        List<String> segments = Gsm0338.splitBySeptets(body, 153);

        assertEquals(2, segments.size());
        assertEquals(body, String.join("", segments), "split must be lossless");
        for (String segment : segments) {
            assertTrue(Gsm0338.septetLength(segment) <= 153);
            byte[] encoded = Gsm0338.encode(segment);
            assertNotEquals(0x1B, encoded[encoded.length - 1], "ESC must keep its pair");
        }
    }

    @Test
    @DisplayName("UCS-2 splitting never breaks a surrogate pair")
    void splitsUcs2SafelyAcrossSurrogates() {
        String body = "hi \uD83D\uDE00 there";
        List<String> segments = Gsm0338.splitByCodeUnits(body, 4);

        assertEquals(body, String.join("", segments), "split must be lossless");
        for (String segment : segments) {
            assertFalse(Character.isHighSurrogate(segment.charAt(segment.length() - 1)));
        }
    }
}
