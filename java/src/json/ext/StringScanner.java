package json.ext;

import java.nio.ByteBuffer;

/**
 * Scans the body of a JSON string for its closing quote, reporting whether the
 * body can be copied verbatim (the "plain" fast path) or must be handed to
 * {@link StringDecoder} for escape expansion and UTF-8/control validation.
 *
 * <p>The default implementation is SWAR (8 bytes per step). A vectorized
 * subclass ({@code VectorizedStringScanner}) is loaded reflectively when the
 * {@code jruby.json.useVectorizedParser} system property is set and the
 * {@code jdk.incubator.vector} module is available; otherwise this SWAR
 * implementation is used. Instances are stateless and therefore shared.
 */
class StringScanner {
    /** Set in the returned bits when the whole body is plain printable ASCII. */
    static final long PLAIN_BIT = 1L << 32;
    /** Returned when no closing quote is found before {@code end}. */
    static final long NOT_FOUND = -1L;

    private static final long HIGH_BITS = 0x8080808080808080L;
    private static final long ONES      = 0x0101010101010101L;

    private static final long SPACES = 0x2020202020202020L;
    private static final long DOUBLE_QUOTES = 0x2222222222222222L;
    private static final long BACKSLASHES = 0x5C5C5C5C5C5C5C5CL;

    private static final String VECTORIZED_SCANNER_CLASS = "json.ext.VectorizedStringScanner";
    private static final String USE_VECTORIZED_PARSER_PROP = "jruby.json.useVectorizedParser";
    private static final String USE_VECTORIZED_PARSER_DEFAULT = "false";

    private static final StringScanner INSTANCE;

    static {
        StringScanner scanner = new StringScanner();
        String enable = System.getProperty(USE_VECTORIZED_PARSER_PROP, USE_VECTORIZED_PARSER_DEFAULT);
        if ("true".equalsIgnoreCase(enable) || "1".equals(enable)) {
            try {
                Class<?> vectorized = StringScanner.class.getClassLoader()
                    .loadClass(VECTORIZED_SCANNER_CLASS);
                scanner = (StringScanner) vectorized.getDeclaredConstructor().newInstance();
            } catch (Throwable t) {
                // jdk.incubator.vector unavailable (or any load failure):
                // keep the SWAR implementation.
                scanner = new StringScanner();
            }
        }
        INSTANCE = scanner;
    }

    static StringScanner getInstance() {
        return INSTANCE;
    }

    /**
     * Scans {@code data[start..end)} for the closing quote, honouring backslash 
     * escapes.
     *
     * @param chunks a little-endian {@link ByteBuffer} over {@code data}, used
     *               for the 8-byte SWAR reads (the vectorized subclass reads
     *               {@code data} directly and ignores it).
     * @return packed result: the low 32 bits hold the index of the closing
     *         quote, or {@code -1} ({@link #NOT_FOUND}) when none is found
     *         before {@code end}; {@link #PLAIN_BIT} is set when the entire body
     *         is plain printable ASCII (no escape, no ASCII control character,
     *         and no non-ASCII byte) and can be copied verbatim.
     */
    long scan(byte[] data, ByteBuffer chunks, int start, int end) {
        int p = start;
        boolean plain = true;

        outer:
        while (true) {
            // SWAR: skip 8-byte chunks that contain nothing interesting.
            while (p + 8 <= end) {
                long x = chunks.getLong(p);
                // Due to the byte-by-byte handling if we match an interesting byte,
                // if we already know this is a non-ASCII-only string, we simply
                // look for quotes and backslashes.
                long m = plain ? stringScanMask(x) : quoteBackslashMask(x);
                if (m == 0) {
                    p += 8;
                } else {
                    p += Long.numberOfTrailingZeros(m) >>> 3;
                    break;
                }
            }
            // If we match on a byte above and/or tail handling for <8 remaining bytes.
            while (p < end) {
                int b = data[p] & 0xFF;
                if (b == '"') {
                    return ((long) p) | (plain ? PLAIN_BIT : 0L);
                }
                if (b == '\\') {
                    plain = false;
                    p += 2; // skip the backslash and the escaped byte
                    continue outer;
                }
                if (b < 0x20 || b >= 0x80) {
                    plain = false;
                    p++;
                    continue outer;
                }
                p++;
            }
            return NOT_FOUND;
        }
    }

    /**
     * Returns a mask whose high bit (0x80) is set in every lane of {@code x}
     * that needs scalar attention: an ASCII control character (&lt; 0x20), a
     * double quote, a backslash, or a non-ASCII byte (high bit set). Returns 0
     * when the whole 8-byte chunk is printable ASCII copyable verbatim.
     */
    private static long stringScanMask(long x) {
        long control = (x - SPACES) & ~x; // bytes < 0x20 (ASCII)
        long high    = x;                              // bit 0x80 set iff non-ASCII
        long q       = x ^ DOUBLE_QUOTES;
        long quote   = (q - ONES) & ~q;
        long s       = x ^ BACKSLASHES;
        long bslash  = (s - ONES) & ~s;
        return (control | high | quote | bslash) & HIGH_BITS;
    }

    /**
     * Like {@link #stringScanMask} but only flags double quotes and backslashes.
     * Used once a string is known to require the decoder, so the remaining scan
     * for the closing quote still skips clean chunks (including multi-byte
     * UTF-8) eight bytes at a time.
     */
    private static long quoteBackslashMask(long x) {
        long q      = x ^ DOUBLE_QUOTES;
        long quote  = (q - ONES) & ~q;
        long s      = x ^ BACKSLASHES;
        long bslash = (s - ONES) & ~s;
        return (quote | bslash) & HIGH_BITS;
    }
}
