package et.com.cog.esms.core.util;

/**
 * Ethiopian phone-number handling, shared by every path that accepts a number
 * typed or uploaded by a human, so a number entered in the admin screen lands
 * in the same E.164 shape as one imported from a policy spreadsheet.
 */
public final class PhoneNumbers {

    private PhoneNumbers() {}

    /** Local forms (09..., 07..., 251...) to E.164; E.164 input is unchanged. */
    public static String normalize(String phone) {
        if (phone == null) return "";
        phone = phone.replaceAll("[\\s\\-().]", "");
        if (phone.startsWith("09") || phone.startsWith("07")) {
            phone = "+251" + phone.substring(1);
        }
        if (phone.startsWith("251") && !phone.startsWith("+")) {
            phone = "+" + phone;
        }
        return phone;
    }

    public static boolean isValidE164(String phone) {
        return phone != null && phone.matches("\\+[1-9]\\d{7,14}");
    }

    /** +251911234567 -> +251****4567. Never returns the full number. */
    public static String mask(String phone) {
        if (phone == null || phone.length() < 8) return "****";
        return phone.substring(0, 4) + "****" + phone.substring(phone.length() - 4);
    }
}
