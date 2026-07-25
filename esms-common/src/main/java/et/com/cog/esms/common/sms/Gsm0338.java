package et.com.cog.esms.common.sms;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * GSM 03.38 default alphabet codec.
 *
 * The sender used to decide a message was "GSM7-safe" against this alphabet
 * and then encode it with {@code getBytes(ISO_8859_1)}. The two are not the
 * same mapping: {@code @} is 0x00 in GSM 03.38 but 0x40 in Latin-1, the pound
 * sign is 0x01 vs 0xA3, {@code _} is 0x11 vs 0x5F, and every accented and
 * Greek character differs. Plain ASCII letters and digits happen to coincide,
 * which is why the defect survived testing - anything else arrived corrupted.
 *
 * Output is UNPACKED: one septet per octet, which is what an SMPP ESME sends
 * in {@code short_message} with {@code data_coding = 0}. The SMSC performs the
 * 7-bit packing onto the air interface.
 *
 * Extension-table characters (such as square and curly brackets, backslash,
 * caret, tilde, pipe and the euro sign) are emitted as an ESC (0x1B) pair and
 * therefore cost TWO septets - which is why length must always be measured
 * with {@link #septetLength(String)} and never with {@code String.length()}.
 */
public final class Gsm0338 {

    private Gsm0338() {}

    /** Escape prefix introducing a character from the extension table. */
    private static final byte ESC = 0x1B;

    /**
     * Basic table: the string index IS the GSM 03.38 code point, and the char
     * at that index is its Unicode equivalent. Written with unicode escapes so
     * the mapping cannot be corrupted by file-encoding round trips, and laid
     * out in rows of 16 so it can be read straight off against the spec table.
     *
     * Index 0x1B is the escape marker itself, not an encodable character.
     * Slots 0x0A and 0x0D are LF and CR, written with the ordinary char
     * escapes rather than unicode ones: a unicode escape for a line
     * terminator is expanded before parsing and would break the source line.
     */
    private static final String BASIC =
              "@\u00A3$\u00A5\u00E8\u00E9\u00F9\u00EC\u00F2\u00C7\n\u00D8\u00F8\r\u00C5\u00E5"   // 0x00-0x0F
            + "\u0394_\u03A6\u0393\u039B\u03A9\u03A0\u03A8\u03A3\u0398\u039E\u001B\u00C6\u00E6\u00DF\u00C9"   // 0x10-0x1F
            + " !\"#\u00A4%&'()*+,-./"   // 0x20-0x2F
            + "0123456789:;<=>?"   // 0x30-0x3F
            + "\u00A1ABCDEFGHIJKLMNO"   // 0x40-0x4F
            + "PQRSTUVWXYZ\u00C4\u00D6\u00D1\u00DC\u00A7"   // 0x50-0x5F
            + "\u00BFabcdefghijklmno"   // 0x60-0x6F
            + "pqrstuvwxyz\u00E4\u00F6\u00F1\u00FC\u00E0";  // 0x70-0x7F

    private static final Map<Character, Byte> BASIC_BY_CHAR    = new HashMap<>(160);
    private static final Map<Character, Byte> EXTENDED_BY_CHAR = new HashMap<>(16);

    static {
        if (BASIC.length() != 128) {
            throw new IllegalStateException(
                    "GSM 03.38 basic table must hold exactly 128 entries, found " + BASIC.length());
        }
        for (int i = 0; i < BASIC.length(); i++) {
            if (i == ESC) continue;   // escape marker, not an encodable character
            BASIC_BY_CHAR.put(BASIC.charAt(i), (byte) i);
        }
        EXTENDED_BY_CHAR.put('\f',      (byte) 0x0A);  // form feed
        EXTENDED_BY_CHAR.put('^',       (byte) 0x14);
        EXTENDED_BY_CHAR.put('{',       (byte) 0x28);
        EXTENDED_BY_CHAR.put('}',       (byte) 0x29);
        EXTENDED_BY_CHAR.put('\\',      (byte) 0x2F);  // backslash
        EXTENDED_BY_CHAR.put('[',       (byte) 0x3C);
        EXTENDED_BY_CHAR.put('~',       (byte) 0x3D);
        EXTENDED_BY_CHAR.put(']',       (byte) 0x3E);
        EXTENDED_BY_CHAR.put('|',       (byte) 0x40);
        EXTENDED_BY_CHAR.put('\u20AC', (byte) 0x65);  // euro sign
    }

    /** True when every character can be carried by the GSM default alphabet. */
    public static boolean isEncodable(String text) {
        if (text == null) return true;
        for (int i = 0; i < text.length(); i++) {
            if (septetCost(text.charAt(i)) == 0) return false;
        }
        return true;
    }

    /**
     * Length in septets, which is the unit SMS segment limits are expressed
     * in. Differs from {@code String.length()} whenever an extension-table
     * character is present, since each of those costs two.
     */
    public static int septetLength(String text) {
        if (text == null) return 0;
        int septets = 0;
        for (int i = 0; i < text.length(); i++) {
            septets += septetCost(text.charAt(i));
        }
        return septets;
    }

    /**
     * Encodes to unpacked septets.
     *
     * @throws IllegalArgumentException if the text is not GSM-encodable;
     *                                  callers must check {@link #isEncodable}
     *                                  first and fall back to UCS-2
     */
    public static byte[] encode(String text) {
        if (text == null) return new byte[0];
        byte[] out = new byte[septetLength(text)];
        int pos = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            Byte basic = BASIC_BY_CHAR.get(c);
            if (basic != null) {
                out[pos++] = basic;
                continue;
            }
            Byte extended = EXTENDED_BY_CHAR.get(c);
            if (extended != null) {
                out[pos++] = ESC;
                out[pos++] = extended;
                continue;
            }
            throw new IllegalArgumentException(
                    "Character not representable in GSM 03.38: U+"
                            + Integer.toHexString(c).toUpperCase());
        }
        return out;
    }

    /**
     * Splits into chunks of at most {@code maxSeptets}, never separating an
     * ESC from the character it introduces - a split there would corrupt both
     * segments.
     */
    public static List<String> splitBySeptets(String text, int maxSeptets) {
        List<String> parts = new ArrayList<>();
        if (text == null || text.isEmpty()) return parts;

        StringBuilder current = new StringBuilder();
        int septets = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int cost = septetCost(c);
            if (septets + cost > maxSeptets) {
                parts.add(current.toString());
                current.setLength(0);
                septets = 0;
            }
            current.append(c);
            septets += cost;
        }
        if (current.length() > 0) parts.add(current.toString());
        return parts;
    }

    /**
     * Splits UTF-16 text into chunks of at most {@code maxUnits} code units
     * without breaking a surrogate pair, which would turn one character into
     * two replacement glyphs across the segment boundary.
     */
    public static List<String> splitByCodeUnits(String text, int maxUnits) {
        List<String> parts = new ArrayList<>();
        if (text == null || text.isEmpty()) return parts;

        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + maxUnits, text.length());
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) {
                end--;
            }
            parts.add(text.substring(start, end));
            start = end;
        }
        return parts;
    }

    /** Septets consumed by one character: 1 basic, 2 extended, 0 if unsupported. */
    private static int septetCost(char c) {
        if (BASIC_BY_CHAR.containsKey(c))    return 1;
        if (EXTENDED_BY_CHAR.containsKey(c)) return 2;
        return 0;
    }
}
