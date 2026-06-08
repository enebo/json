package json.ext;

import org.jcodings.specific.USASCIIEncoding;
import org.jcodings.specific.UTF8Encoding;
import org.jruby.Ruby;
import org.jruby.RubyArray;
import org.jruby.RubyBignum;
import org.jruby.RubyClass;
import org.jruby.RubyFloat;
import org.jruby.RubyHash;
import org.jruby.RubyModule;
import org.jruby.RubyString;
import org.jruby.exceptions.RaiseException;
import org.jruby.runtime.Block;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.util.ByteList;

import static json.ext.MainParser.ParserState.*;
import static json.ext.Ryu.ryuS2dFromParts;
import static json.ext.Utils.newException;

/**
 *
 * Non-recursive design:
 * <p/>
 * This design uses an artificial stack for maintaining info vs depending on the actual stack
 * for fear of stack exhaustion.  It tries to minimize the cost of this by still passing
 * as much through functions to try and only effectively use the stack for pops.  It also
 * micro optimizes the cost of allocating the stack element by reusing them.  For small
 * parses the cost of the stack profiles high enough to reduce this cost.
 */
public class MainParser {
    private static final boolean DEBUG = false;
    private static final int EOF = -1;
    private static final int STARTING_STACK_SIZE = 4;
    private static final int STACK_GROWTH_FACTOR = 2;
    private static final byte[] NULL = new byte[]{'n', 'u', 'l', 'l'};
    private static final byte[] TRUE = new byte[]{'t', 'r', 'u', 'e'};
    private static final byte[] FALSE = new byte[]{'f', 'a', 'l', 's', 'e'};
    private static final byte[] NAN = new byte[]{'N', 'a', 'N'};
    private static final byte[] INFINITY = new byte[]{'I', 'n', 'f', 'i', 'n', 'i', 't', 'y'};

    protected final ParserConfig options;
    private final int size;
    private final byte[] source;
    int sourceIndex;
    private StateElement[] stateStack = new StateElement[STARTING_STACK_SIZE];
    // This is used to manage both accessing stateStack, and secondarily it knows nesting depth.
    int stateStackIndex = 0;
    int emittedDeprecations = 0;

    public MainParser(ByteList src, ParserConfig options) {
        this.source = src.unsafeBytes();
        int begin = src.getBegin();
        size = begin + src.length(); // really index of last element
        sourceIndex = begin;
        this.options = options;
        fillNewStackValues(0, STARTING_STACK_SIZE);
        stateStack[0].state = ELEMENT;
    }

    // StateStack Methods

    protected static class StateElement {
        ParserState state;
        IRubyObject key;
        Object value;
    }

    private void ensureStackSize() {
        if (stateStackIndex >= stateStack.length) {
            int oldSize = stateStack.length;
            int newSize = oldSize * STACK_GROWTH_FACTOR;
            StateElement[] newStack = new StateElement[newSize];
            System.arraycopy(stateStack, 0, newStack, 0, oldSize);
            stateStack = newStack;
            fillNewStackValues(oldSize, newSize);
        }
    }

    private void fillNewStackValues(int oldSize, int newSize) {
        for (int i = oldSize; i < newSize; i++) {
            stateStack[i] = new StateElement();
        }
    }

    private StateElement pushStack(ThreadContext context, ParserState state, IRubyObject value) {
        if (DEBUG) System.out.println("pushing " + state);

        // When we exit the array or object parse the result of that element will be a value
        // for the parent to deal with.  *_COMMA would be natural next state for simple values.
        StateElement current = stateStack[stateStackIndex];
        switch (current.state) {
            case ARRAY_COMMA: case OBJECT_COLON:
                throw parserError(context, "missing ','");
            case OBJECT_VALUE:
                current.state = OBJECT_COMMA;
                break;
            case ARRAY_VALUE:
                current.state = ARRAY_COMMA;
                break;
        }

        stateStackIndex++;
        if (options.maxNesting > 0 && stateStackIndex > options.maxNesting) {
            throw newException(context, Utils.M_NESTING_ERROR, "nesting of " + stateStackIndex + " is too deep");
        }

        ensureStackSize();
        StateElement element = stateStack[stateStackIndex];
        element.state = state;
        element.value = value;
        return element;
    }

    private StateElement popStack() {
        if (DEBUG) System.out.println("popping " + stateStack[stateStackIndex].state + " -> " + stateStack[stateStackIndex - 1].state);
        return stateStack[--stateStackIndex];
    }

    // Source reading methods

    public int advance() {
        return advance(1);
    }

    public int advance(int amount) {
        sourceIndex += amount;

        return sourceIndex >= size ? EOF : source[sourceIndex] & 0xff;
    }

    public int peek(int amount) {
        return sourceIndex >= size ? EOF : source[sourceIndex + amount] & 0xff;
    }

    public boolean startsWith(byte[] str) {
        if (size < str.length + sourceIndex) return false;

        int to = sourceIndex;
        int po = 0;
        int pc = str.length;

        while (--pc >= 0) if (source[to++] != str[po++]) return false;
        sourceIndex = to - 1;
        return true;
    }

    enum ParserState {
        ELEMENT,        // Start of document (also to never have empty stack)
        OBJECT_KEY,     // "key"
        OBJECT_COLON,   // ':'
        OBJECT_VALUE,   // (string, number, object, array, true, false, null)
        OBJECT_COMMA,   // ','
        ARRAY_VALUE,    // (string, number, object, array, true, false, null)
        ARRAY_COMMA,    // ','
    }

    public IRubyObject parse(ThreadContext context) {
        if (size == 0) throw parserError(context, "empty source");
        IRubyObject result = parseInner(context);
        if (result == null) throw parserError(context, "no JSON present");
        skipWhitespace(context, advance(), stateStack[stateStackIndex]);
        if (peek(0) != EOF) throw parserError(context, "unexpected extra stuff");

        return result;
    }

    public IRubyObject parseInner(ThreadContext context) {
        StateElement holder = stateStack[stateStackIndex];
        IRubyObject key = null;
        IRubyObject value = null;

        for (int c = peek(0); true; c = advance()) {
            c = skipWhitespace(context, c, holder);

            if (DEBUG) System.out.println("advance " + (char) c + " ,STATE: " + holder.state + " ,KEY: " + key + " ,VALUE: " + value);
            switch (c) {
                case '{':
                    holder = pushStack(context, OBJECT_KEY, RubyHash.newHash(context.runtime));
                    break;
                case '}': {
                    RubyHash hash = closeObject(context, holder);
                    // last key/value-pair of the hash
                    if (key != null && value != null) setHashKeypair(context, hash, key, value);

                    value = finishObject(context, hash);
                    holder = popStack();
                    key = holder.key;
                    break;
                }
                case ':':
                    if (holder.state != OBJECT_COLON) throw parserError(context, "Expected ':' but got " + holder.state);
                    holder.state = OBJECT_VALUE;
                    break;
                case '[':
                    holder = pushStack(context, ParserState.ARRAY_VALUE, RubyArray.newArray(context.runtime));
                    break;
                case ']': {
                    RubyArray<?> array = (RubyArray<?>) holder.value;
                    if (value != null) {
                        array.append(value);
                    } else if (!options.allowTrailingComma && !array.isEmpty()) {
                        throw parserError(context, "Trailing comma");
                    }
                    if (holder.state == OBJECT_KEY) throw parserError(context, "mismatched object with list");
                    value = finishArray(context, array);
                    holder = popStack();
                    key = holder.key;
                    break;
                }
                case '"': {
                    IRubyObject v = onLoad(context, parseString(context, holder));
                    // Double switch so key and value can stay local vs being stored on holder.
                    switch (holder.state) {
                        case ELEMENT:
                            value = v;
                            break;
                        case OBJECT_KEY:
                            holder.key = key = v;
                            holder.state = OBJECT_COLON;
                            break;
                        case OBJECT_VALUE:
                            value = v;
                            holder.state = OBJECT_COMMA;
                            break;
                        case ARRAY_VALUE:
                            value = v;
                            holder.state = ARRAY_COMMA;
                            break;
                    }
                    break;
                }
                case ',':
                    switch (holder.state) {
                        case ARRAY_COMMA:
                            ((RubyArray<?>) holder.value).add(value);
                            holder.state = ARRAY_VALUE;
                            break;
                        case OBJECT_COMMA:
                            setHashKeypair(context, (RubyHash) holder.value, key, value);
                            holder.state = OBJECT_KEY;
                            break;
                        default:
                            throw parserError(context, "unexpected comma: " + holder.state);
                    }
                    key = null;
                    value = null;
                    break;
                case 'I':
                case 'N':
                    value = onLoad(context, parseSpecialNumbers(context, holder, c, false));
                    break;
                case '-':
                    value = onLoad(context, parseNegativeNumber(context, holder));
                    break;
                case '0': case '1': case '2': case '3': case '4': case '5': case '6': case '7': case '8': case '9':
                    value = onLoad(context, parseNumber(context, holder, c, false));
                    break;
                case 't':
                    value = parseTrue(context, holder);
                    break;
                case 'f':
                    value = parseFalse(context, holder);
                    break;
                case 'n':
                    value = parseNull(context, holder);
                    break;
                case EOF:
                    if (holder.state != ELEMENT) throw parserError(context, "missing some closing elements");
                    break;
                default:
                    throw parserError(context, "unexpected token at '" + sourceLine() + "'");
            }

            if (holder.state == ELEMENT) return value;
        }
    }

    protected IRubyObject finishArray(ThreadContext context, IRubyObject array) {
        if (options.freeze) array.setFrozen(true);
        return onLoad(context, array);
    }

    protected IRubyObject finishObject(ThreadContext context, RubyHash hash) {
        if (options.freeze) hash.setFrozen(true);
        return onLoad(context, hash);
    }

    private static RaiseException parserError(ThreadContext context, String message) {
        return newException(context, "ParserError", message);
    }

    private int skipWhitespace(ThreadContext context, int c, StateElement holder) {
        while (true) {
            switch (c) {
                case ' ':
                case '\t':
                case '\n':
                case '\r':
                    c = advance();
                    break;
                case '/':
                    c = parseComment(context, holder);
                    continue;
                default:
                    return c;
            }
        }
    }

    static void valueCheck(ThreadContext context, StateElement holder) {
        switch (holder.state) {
            case ELEMENT:       // only contents of payload is a single number
                break;
            case OBJECT_VALUE:  // number is value of a hash element
                holder.state = OBJECT_COMMA;
                break;
            case ARRAY_VALUE:   // number is first element of an array
                holder.state = ARRAY_COMMA;
                break;
            case OBJECT_KEY:    // number is trying to be key of a hash element
                throw parserError(context, "cannot have a number for an object key");
            default:
                throw parserError(context, "string unexpected state: " + holder.state);
        }
    }

    private IRubyObject parseNull(ThreadContext context, StateElement holder) {
        if (!startsWith(NULL)) throw parserError(context, "'null' expected");
        valueCheck(context, holder);
        return context.nil;
    }

    private IRubyObject parseFalse(ThreadContext context, StateElement holder) {
        if (!startsWith(FALSE)) throw parserError(context, "'false' expected");
        valueCheck(context, holder);
        return context.fals;
    }

    private IRubyObject parseTrue(ThreadContext context, StateElement holder) {
        if (!startsWith(TRUE)) throw parserError(context, "'true' expected");
        valueCheck(context, holder);
        return context.tru;
    }

    private int parseComment(ThreadContext context, StateElement holder) {
        if (!options.allowComments) noCommentsHandler(context, holder);

        int c = advance();
        switch (c) {
            case '*':       // /* ... */ - C comment
                for (c = advance(); c != EOF; c = advance()) {
                    if (c == '*' && peek(1) == '/') return advance(2);
                }
                throw parserError(context, "unterminated comment, expected closing '*/'");
            case '/': // // Java/C++ comment
                for (c = advance(); true; c = advance()) {
                    switch (c) {
                        case '\n':
                        case '\f':
                        case '\r':
                            return advance();
                        case EOF:
                            break;
                    }
                }
            default:
                throw parserError(context, "unexpected char " + c);
        }
    }

    // Lack of a good name here but we either error because we do not allow comments or we grudglingly accept them with a warning.
    private void noCommentsHandler(ThreadContext context, StateElement holder) {
        if (holder.state == ELEMENT) throw parserError(context, "comment for no JSON");

        if (options.deprecateComments) {
            if (options.deprecateDuplicateKey && emittedDeprecations < 5) {
                emittedDeprecations++;
                context.runtime.getWarnings().warning(
                        "Encountered comment in JSON. This will raise an error in json 3.0 unless enabled via `allow_comments: true`"
                );
            }
        } else {
            throw parserError(context, "unexpected comment");
        }
    }

    protected IRubyObject parseNegativeNumber(ThreadContext context, StateElement holder) {
        int c = advance();
        if (c == 'I' || c == 'N') return parseSpecialNumbers(context, holder, c, true);
        if (!Character.isDigit(c)) throw parserError(context, "unexpected char " + (char) c);

        return parseNumber(context, holder, c, true);
    }

    private RubyBignum bigNumValue(ThreadContext context, int start) {
        return RubyBignum.newBignum(context.runtime, new String(source, start, sourceIndex - start + 1));
    }
    
    private IRubyObject decimalClassValue(ThreadContext context, int start) {
        Ruby runtime = context.runtime;
        RubyString meat = runtime.newString(new ByteList(source, start, sourceIndex - start + 1, USASCIIEncoding.INSTANCE, false));
        RubyClass decimalClass = options.decimalClass;

        return decimalClass == null || decimalClass == runtime.getClass("BigDecimal") ?
                runtime.getKernel().callMethod(context, "BigDecimal", meat) :
                options.decimalClass.newInstance(context, meat, Block.NULL_BLOCK);
    }

    protected IRubyObject parseSpecialNumbers(ThreadContext context, StateElement holder, int c, boolean negative) {
        if (options.allowNaN) {
            switch (c) {
                case 'N':
                    if (!negative && startsWith(NAN)) {
                        valueCheck(context, holder);
                        return nan(context);
                    }
                    break;
                case 'I':
                    if (startsWith(INFINITY)) {
                        valueCheck(context, holder);
                        return infinity(context, negative);
                    }
                    break;
            }
        }

        throw parserError(context, "unexpected token at '" + sourceLine() + "'");
    }

    protected IRubyObject parseString(ThreadContext context, StateElement holder) {
        boolean isObjectKey = holder.state == OBJECT_KEY;
        int c = advance();
        int stringContextStart = sourceIndex;

        for (; '"' != c; c = advance()) {
            switch (c) {
                case EOF:
                    throw parserError(context, "Unexpected end of string");
                case '\0':
                    if (options.allowControlCharacters) break;
                    throw parserError(context, "hit EOF before end of string");
                case '\\': // slow path we have to process escapes and cannot just track indices for str creation.
                    return parseEscapedString(context, stringContextStart, isObjectKey);
                default:
                    if (c < 0x20) {
                        if (!options.allowControlCharacters) {
                            if (c == '\n')
                                throw parserError(context, "Invalid unescaped newline character (\\n) in string");
                            throw parserError(context, "invalid ASCII control character in string");
                        }
                    }
                    break;
            }
        }

        switch (holder.state) {
            case ELEMENT:       // only contents of payload is a single string
            case OBJECT_KEY:    // string is key of a hash element
            case OBJECT_VALUE:  // string is value of a hash element
            case ARRAY_VALUE:   // string is first element of an array
                break;
            default:
                throw parserError(context, "string unexpected state: " + holder.state);
        }

        return createNewString(context, stringContextStart, isObjectKey);
    }

    private IRubyObject parseEscapedString(ThreadContext context, int stringStartIndex, boolean isObjectKey) {
        int prefaceLength = sourceIndex - stringStartIndex;

        ByteList buf;
        if (prefaceLength <= 0) {
            buf = new ByteList(50);
            buf.setEncoding(UTF8Encoding.INSTANCE);
        } else {
            buf = new ByteList(source, stringStartIndex, prefaceLength, UTF8Encoding.INSTANCE, true);
        }

        // We got here by seeing a '\'.  Examine next value.
        parseEscapeCharacter(context, advance(), buf);

        for (int c = advance(); '"' != c; c = advance()) {
            switch (c) {
                case EOF:
                    if (options.allowControlCharacters) {
                        buf.append(c);
                        break;
                    }
                    throw parserError(context, "hit EOF before end of string");
                case '\\':
                    parseEscapeCharacter(context, advance(), buf);
                    break;
                default:
                    if (c < 0x20) {
                        if (!options.allowControlCharacters) {
                            if (c == '\n')
                                throw parserError(context, "Invalid unescaped newline character (\\n) in string");
                            throw parserError(context, "invalid ASCII control character in string");
                        }
                    }
                    buf.append(c);
                    break;
            }
        }

        return createNewString(context, buf, isObjectKey);
    }

    /* ( ^(["\\]|0..0x1f) | '\\'["\\/bfnrt] | '\\u'[0-9a-fA-F]{4} | '\\'^(["\\/bfnrtu]|0..0x1f) */
    private void parseEscapeCharacter(ThreadContext context, int s, ByteList buf) {
        switch (s) {
            case '"':
                buf.append('"');
                break;
            case '/':
                buf.append('/');
                break;
            case '\\':
                buf.append('\\');
                break;
            case 'n':
                buf.append('\n');
                break;
            case 'r':
                buf.append('\r');
                break;
            case 't':
                buf.append('\t');
                break;
            case 'b':
                buf.append('\b');
                break;
            case 'f':
                buf.append('\f');
                break;
            case 'u':
                parseUnicodeEscapeCharacter(context, buf);
                break;
            case '\n':
                throw parserError(context, "line escape not supported");
            case 'x':
                s = parseHexValue(context);
                if (s >= 0 && s < 0x1f) throw parserError(context, "control characters in string");
                buf.append(s);
                break;
            case '0':
                s = parseEscapeCharacter(context);
                // falls through
            default:
                if (s < 0x20) {
                    if (!options.allowControlCharacters) {
                        if (s == '\n')
                            throw parserError(context, "Invalid unescaped newline character (\\n) in string");
                        throw parserError(context, "invalid ASCII control character in string");
                    }
                }
                if (!options.allowInvalidEscape) throw parserError(context, "control characters in string");
                buf.append(s);
                break;
        }
    }

    private int parseEscapeCharacter(ThreadContext context) {
        int value = 0;
        for (int i = 0; i < 2; i++) {
            int c = advance();
            value <<= 2;
            switch (c) {
                case '0':
                case '1':
                case '2':
                case '3':
                case '4':
                case '5':
                case '6':
                case '7':
                    value += (c - '0');
                    break;
                default:
                    throw parserError(context, "Invalid hex value");
            }
        }
        return value;
    }

    private int parseHexValue(ThreadContext context) {
        int value = 0;

        for (int i = 0; i < 4; i++) {
            int c = advance();
            value <<= 4;
            switch (c) {
                case '0':
                case '1':
                case '2':
                case '3':
                case '4':
                case '5':
                case '6':
                case '7':
                case '8':
                case '9':
                    value += (c - '0');
                    break;
                case 'a':
                case 'b':
                case 'c':
                case 'd':
                case 'e':
                case 'f':
                    value += (c - 'a' + 10);
                    break;
                case 'A':
                case 'B':
                case 'C':
                case 'D':
                case 'E':
                case 'F':
                    value += (c - 'A' + 10);
                    break;
                default:
                    throw parserError(context, "Invalid hex value");
            }
        }

        return value;
    }

    private int parseUnicodeSurrogateCharacter(ThreadContext context, int codepoint) {
        if (peek(1) != '\\' || peek(2) != 'u') throw parserError(context, "incomplete surrogate pair");

        advance(2);
        int sur = parseHexValue(context);

        if ((sur & 0xFC00) != 0xDC00) throw parserError(context, "invalid surrogate pair");

        return ((codepoint & 0x3F) << 10) | ((((codepoint >> 6) & 0xF) + 1) << 16) | (sur & 0x3FF);
    }

    private void parseUnicodeEscapeCharacter(ThreadContext context, ByteList buf) {
        int codepoint = parseHexValue(context);

        if (Character.isHighSurrogate((char) codepoint)) {
            codepoint = parseUnicodeSurrogateCharacter(context, codepoint);
        }

        if (codepoint <= 0x7F) {
            buf.append((byte) codepoint);
        } else {
            appendMultipleByteUnicodeCharacter(buf, codepoint);
        }
    }

    private static void appendMultipleByteUnicodeCharacter(ByteList buf, int codepoint) {
        if (codepoint <= 0x07FF) {
            buf.append((codepoint >> 6) | 0xC0);
            buf.append((codepoint & 0x3F) | 0x80);
        } else if (codepoint <= 0xFFFF) {
            buf.append((codepoint >> 12) | 0xE0);
            buf.append(((codepoint >> 6) & 0x3F) | 0x80);
            buf.append((codepoint & 0x3F) | 0x80);
        } else if (codepoint <= 0x1FFFFF) {
            buf.append((codepoint >> 18) | 0xF0);
            buf.append(((codepoint >> 12) & 0x3F) | 0x80);
            buf.append(((codepoint >> 6) & 0x3F) | 0x80);
            buf.append((codepoint & 0x3F) | 0x80);
        }
    }

    private RubyHash closeObject(ThreadContext context, StateElement holder) {
        RubyHash hash = (RubyHash) holder.value;
        switch (holder.state) {
            case ELEMENT:        // We see an extra '}' after valid JSON payload
                throw parserError(context, "missing element close");
            case OBJECT_COMMA:   // One or more key/value pairs
                break;
            case OBJECT_KEY:     // Ok. Empty Object '{}'
                if (!options.allowTrailingComma && !hash.isEmpty()) {
                    throw parserError(context, "Trailing comma found");
                }
                break;
            default:
                throw parserError(context, "unexpected token: " + holder.state);
        }

        return hash;
    }

    // For simple strings without escaping.  We want to avoid copies if it is a key since that will be deduped anyway.
    private IRubyObject createNewString(ThreadContext context, int stringContextStart, boolean isObjectKey) {
        IRubyObject str = isObjectKey ?
                RubyString.newStringNoCopy(context.runtime, source, stringContextStart, sourceIndex - stringContextStart, UTF8Encoding.INSTANCE) :
                RubyString.newString(context.runtime, source, stringContextStart, sourceIndex - stringContextStart, UTF8Encoding.INSTANCE);

        if (isObjectKey || options.freeze) str = freezeString(context, str);

        return str;
    }

    protected IRubyObject freezeString(ThreadContext context, IRubyObject str) {
        return context.runtime.freezeAndDedupString((RubyString) str);
    }

    // For escaped strings so we have already allocated a ByteList and do not want to copy.
    private IRubyObject createNewString(ThreadContext context, ByteList contents, boolean isObjectKey) {
        IRubyObject str = RubyString.newString(context.runtime, contents);

        if (isObjectKey || options.freeze) str = freezeString(context, str);

        return str;
    }

    protected void setHashKeypair(ThreadContext context, RubyHash hash, IRubyObject key, IRubyObject value) {
        IRubyObject symbol = options.symbolizeNames ? ((RubyString) key).intern() : key;

        if (!options.allowDuplicateKey) duplicateKeyCheck(context, key, hash, symbol);

        hash.fastASet(symbol, value);
    }

    protected void duplicateKeyCheck(ThreadContext context, IRubyObject key, RubyHash hash, IRubyObject symbol) {
        if (hash.hasKey(symbol)) {
            if (options.deprecateDuplicateKey) {
                if (emittedDeprecations < 5) {
                    emittedDeprecations++;
                    context.runtime.getWarnings().warning("detected duplicate key " + key.inspect() +
                            " in JSON object. This will raise an error in json 3.0 unless enabled via `allow_duplicate_key: true`");
                }
            } else {
                throw parserError(context, "duplicate key" + key.inspect());
            }
        }
    }

    private String sourceLine() {
        int startIndex = 0;
        for (int i = sourceIndex; i >= 0; --i) {
            int c = peek(-i);
            if (c == '\n') {
                startIndex = i + 1;
                break;
            }
        }

        int endIndex = -1;
        for (int i = 0; i + sourceIndex < size; i++) {
            int c = peek(i);

            if (c == '\n') {
                endIndex = sourceIndex + i;
                break;
            }
        }

        if (endIndex == -1) endIndex = size;

        int tokenLength = endIndex - startIndex;

        if (tokenLength >= 60) tokenLength = 32;

        return new String(source, startIndex, tokenLength);
    }

    IRubyObject infinity(ThreadContext context, boolean negative) {
        return getJSONConstant(context, negative ? "MinusInfinity" : "Infinity");
    }

    IRubyObject nan(ThreadContext context) {
        return getJSONConstant(context, "NaN");
    }

    IRubyObject getJSONConstant(ThreadContext context, String name) {
        return ((RubyModule) context.runtime.getObject().getConstantAt("JSON")).getConstant(name);
    }

    IRubyObject onLoad(ThreadContext context, IRubyObject result) {
        return options.onLoad != null ?
                options.onLoad.call(context, result) :
                result;
    }

    IRubyObject parseNumber(ThreadContext context, StateElement holder, int c, boolean negative) {
        int start = sourceIndex - (negative ? 1 : 0);
        boolean integer = true;
        int first_digit = c;

        // Variables for Ryu optimization - extract digits during parsing
        long exponent = 0;
        int decimal_point_pos = -1;

        int digitsStart = sourceIndex;
        long mantissa = 0;
        for (; c >= '0' && c <= '9'; c = advance()) {
            mantissa = mantissa * 10 + (c - '0');
        }
        int mantissa_digits = (sourceIndex - digitsStart);

        if ((first_digit == '0' && mantissa_digits > 1) || (negative && mantissa_digits == 0)) {
            throw parserError(context, "invalid number");
        }

        // Parse fractional part
        if (peek(0) == '.') {
            integer = false;
            decimal_point_pos = mantissa_digits;  // Remember position of decimal point
            c = advance();

            digitsStart = sourceIndex;
            for (; c >= '0' && c <= '9'; c = advance()) {
                mantissa = mantissa * 10 + (c - '0');
            }
            int fractional_digits = (sourceIndex - digitsStart);
            mantissa_digits += fractional_digits;

            if (fractional_digits == 0) throw parserError(context, "invalid number");
        }

        // Parse exponent
        c = peek(0);
        if (c == 'e' || c == 'E') {
            c = advance();
            integer = false;
            boolean negative_exponent = c == '-';
            if (negative_exponent || c == '+') c = advance();

            long abs_exponent = 0;
            digitsStart = sourceIndex;
            for (; c >= '0' && c <= '9'; c = advance()) {
                abs_exponent = abs_exponent * 10 + (c - '0');
            }
            int exponent_digits = (sourceIndex - digitsStart);

            if (exponent_digits == 0) throw parserError(context, "invalid number");

            if (exponent_digits >= 20 || Long.compareUnsigned(abs_exponent, Long.MAX_VALUE) > 0) {
                exponent = negative_exponent ? Long.MIN_VALUE : Long.MAX_VALUE;
            } else {
                exponent = negative_exponent ? -abs_exponent : abs_exponent;
            }
        }
        advance(-1);

        if (integer) {
            valueCheck(context, holder);
            return decodeInteger(context, mantissa, mantissa_digits, negative, start);
        }

        // Adjust exponent based on decimal point position
        if (decimal_point_pos >= 0) exponent -= (mantissa_digits - decimal_point_pos);

        valueCheck(context, holder);
        return decodeFloat(context, mantissa, mantissa_digits, exponent, negative, start);
    }

    static final int MAX_FAST_INTEGER_SIZE = 18;

    IRubyObject decodeInteger(ThreadContext context, long mantissa, int mantissa_digits, boolean negative, int start) {
        return mantissa_digits < MAX_FAST_INTEGER_SIZE ?
                context.runtime.newFixnum(negative ? -mantissa : mantissa) :
                bigNumValue(context, start);
    }

    IRubyObject decodeFloat(ThreadContext context, long mantissa, int mantissa_digits, long exponent, boolean negative, int start) {
        if (options.decimalClass != null) return decimalClassValue(context, start);
        if (exponent > Integer.MAX_VALUE) return infinity(context, negative);
        if (exponent < Integer.MIN_VALUE) return RubyFloat.newFloat(context.runtime, negative ? -0.0 : 0.0);

        // Ryu has rounding issues with subnormals around 1e-310 (< 2.225e-308)
        if (mantissa_digits > 17 || mantissa_digits + exponent < -307) {
            return RubyFloat.newFloat(context.runtime, Double.parseDouble(new String(source, start, sourceIndex - start + 1)));
        }

        return RubyFloat.newFloat(context.runtime, ryuS2dFromParts(mantissa, mantissa_digits, (int) exponent, negative));
    }
}