package com.reconcile.shared;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;

/**
 * Monotonic, lexicographically sortable identifiers.
 *
 * <p>Every primary key in this system is a prefixed ULID (Crockford base32, 26 characters):
 * {@code pay_01K4…}, {@code ltx_…}, {@code len_…}. Two properties earn the format its place.
 *
 * <ul>
 *   <li><b>Time-ordered.</b> The first 48 bits are a millisecond timestamp, so keys sort by
 *       creation time. Index locality matters most here: the hot queries in this system are
 *       "everything for this payment, newest first", and random UUIDv4 keys would scatter those
 *       inserts across the whole B-tree instead of filling the rightmost leaf.</li>
 *   <li><b>Self-describing in a log.</b> {@code ltx_01K4Q8Z…} tells you what row was touched
 *       without a second lookup. When a reconciliation case is being reconstructed from logs at
 *       2am, that difference is real.</li>
 * </ul>
 *
 * <p>Randomness comes from {@link SecureRandom} because these are externally visible, and the cost
 * is irrelevant next to a database round trip. Monotonicity within the same millisecond is <i>not</i>
 * guaranteed by this class — if two ids collide in the last 80 bits the primary key constraint
 * rejects the insert, which is the correct outcome to want rather than a silent retry loop.
 */
public final class Ulid {

    private static final char[] CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final int ENCODED_LENGTH = 26;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ulid() {
    }

    /** A new identifier with no entity prefix, e.g. {@code 01K4Q8Z…}. */
    public static String next() {
        return next(Clock.systemUTC());
    }

    /** A new identifier stamped from {@code clock}, for tests that need a fixed instant. */
    public static String next(Clock clock) {
        long timestamp = clock.instant().toEpochMilli();

        byte[] randomness = new byte[10]; // 80 bits, exactly 16 base32 characters
        RANDOM.nextBytes(randomness);

        char[] out = new char[ENCODED_LENGTH];
        // 48 bits of timestamp across the first 10 characters.
        for (int i = 9; i >= 0; i--) {
            out[i] = CROCKFORD[(int) (timestamp & 0x1F)];
            timestamp >>>= 5;
        }
        // 80 bits of randomness across the remaining 16, read five bits at a time from the
        // most-significant end so the encoded value sorts the same way as the randomness does.
        for (int i = 25; i >= 10; i--) {
            out[i] = CROCKFORD[read5Bits(randomness, (25 - i) * 5)];
        }
        return new String(out);
    }

    /**
     * Reads five consecutive bits starting at {@code bitOffset}, most-significant bit first.
     *
     * <p>Bits that fall past the end of {@code source} read as zero. For a whole number of bytes
     * that never happens - 80 bits is exactly 16 groups of 5 - so the guard exists only to make the
     * helper total, not to paper over a sizing mistake.
     */
    private static int read5Bits(byte[] source, int bitOffset) {
        int value = 0;
        for (int bit = 0; bit < 5; bit++) {
            int position = bitOffset + bit;
            int byteIndex = position / 8;
            int bitInByte = 7 - (position % 8);
            int set = byteIndex < source.length ? ((source[byteIndex] >> bitInByte) & 1) : 0;
            value = (value << 1) | set;
        }
        return value;
    }

    /**
     * A new identifier for an entity, prefixed so logs and stack traces name what they refer to.
     *
     * @param prefix entity tag such as {@code pay}; rendered verbatim and never parsed back
     */
    public static String of(String prefix) {
        return of(prefix, Clock.systemUTC());
    }

    public static String of(String prefix, Clock clock) {
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("id prefix must not be blank");
        }
        return prefix + "_" + next(clock);
    }

    /** Whether {@code value} looks like a ULID with the given prefix. */
    public static boolean hasPrefix(String value, String prefix) {
        return value != null && value.length() == prefix.length() + 1 + ENCODED_LENGTH
                && value.startsWith(prefix + "_");
    }

    /** The millisecond timestamp encoded in a ULID, for range queries and diagnostics. */
    public static Instant timestampOf(String ulid) {
        requireUnprefixed(ulid);
        long timestamp = 0L;
        for (int i = 0; i < 10; i++) {
            int value = Character.digit(ulid.charAt(i), 32);
            if (value < 0 || value >= 32) {
                throw new IllegalArgumentException("not a ULID: " + ulid);
            }
            timestamp = (timestamp << 5) | value;
        }
        return Instant.ofEpochMilli(timestamp);
    }

    private static void requireUnprefixed(String ulid) {
        if (ulid == null || ulid.length() != ENCODED_LENGTH) {
            throw new IllegalArgumentException("not a ULID: " + ulid);
        }
    }
}