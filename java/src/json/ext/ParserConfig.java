package json.ext;

import org.jcodings.Encoding;
import org.jcodings.specific.ASCIIEncoding;
import org.jcodings.specific.UTF8Encoding;
import org.jruby.Ruby;
import org.jruby.RubyClass;
import org.jruby.RubyHash;
import org.jruby.RubyObject;
import org.jruby.RubyProc;
import org.jruby.RubyString;
import org.jruby.RubySymbol;
import org.jruby.anno.JRubyMethod;
import org.jruby.runtime.Block;
import org.jruby.runtime.ObjectAllocator;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.Visibility;
import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.util.ByteList;
import org.jruby.util.TypeConverter;

import static org.jruby.RubyNumeric.fix2int;
import static org.jruby.util.StringSupport.CR_7BIT;

/**
 * <p>The <code>JSON::Ext::Parser</code> class.</p>
 *
 * <p>This class contains the options of the parser and it will construct a parser
 * and parse using those options.</p>
 */
public class ParserConfig extends RubyObject {
    // Any special features we want to parse with?
    public int maxNesting = 100;
    public boolean allowNaN = false;
    public boolean allowTrailingComma = false;
    public boolean allowControlCharacters = false;
    public boolean allowInvalidEscape = false;
    public boolean allowDuplicateKey = false;
    public boolean deprecateDuplicateKey = true;
    public boolean symbolizeNames = false;
    public boolean freeze = false;
    public RubyProc onLoad = null;
    public RubyClass decimalClass = null;

    static final ObjectAllocator ALLOCATOR = ParserConfig::new;

    public ParserConfig(Ruby runtime, RubyClass metaClass) {
        super(runtime, metaClass);
    }

    @JRubyMethod(name = "new", meta = true)
    public static IRubyObject newInstance(IRubyObject clazz, IRubyObject options, Block block) {
        ParserConfig parser = (ParserConfig) ((RubyClass) clazz).allocate();
        parser.callInit(options, block);
        return parser;
    }

    /**
     * Perform <code>JSON.parse(source, opts={})</code>
     * @param context the current thread context
     * @param src the source to be parsed
     * @return the resulting JSON as a Ruby object
     */
    @JRubyMethod
    public IRubyObject parse(ThreadContext context, IRubyObject src) {
        ByteList source = convertEncoding(context, src.convertToString()).getByteList();
        return new MainParser(source, this).parse(context);
    }

    final static RubyHash.VisitorWithState<ParserConfig> OptionsVisitor = new RubyHash.VisitorWithState<ParserConfig>() {
        public void visit(ThreadContext context, RubyHash self, IRubyObject key, IRubyObject value, int index, ParserConfig options) {
            if (!(key instanceof RubySymbol)) return;

            switch (key.asJavaString()) {
                case "allow_trailing_comma": options.allowTrailingComma = value.isTrue(); break;
                case "allow_nan": options.allowNaN = value.isTrue(); break;
                case "max_nesting": options.maxNesting = !value.isTrue() ? 0 : fix2int(value); break;
                case "freeze": options.freeze = value.isTrue(); break;
                case "symbolize_names": options.symbolizeNames = value.isTrue(); break;
                case "decimal_class": options.decimalClass = getClassOption(value); break;
                case "allow_control_characters": options.allowControlCharacters = value.isTrue(); break;
                case "allow_invalid_escape": options.allowInvalidEscape = value.isTrue(); break;
                case "on_load": options.onLoad = getProcOption(context, value); break;
                case "allow_duplicate_key": {
                    options.allowDuplicateKey = value.isTrue();
                    options.deprecateDuplicateKey = false;
                    break;
                }
            }
        }
    };

    private static RubyProc getProcOption(ThreadContext context, IRubyObject value) {
        return value.isNil() ? null : (RubyProc) TypeConverter.convertToType(value, context.runtime.getProc(), "to_proc");
    }

    private static RubyClass getClassOption(IRubyObject value) {
        return value.isNil() ? null : (RubyClass) value; // FIXME: this and old code crashes is non-class passed in.
    }

    private void processOptions(ThreadContext context, IRubyObject opts) {
        if (opts.isNil()) return;

        RubyHash hashOpts = opts.convertToHash();
        if (hashOpts.isNil() || hashOpts.isEmpty()) return;

        hashOpts.visitAll(context, OptionsVisitor, this);
    }

    @JRubyMethod(visibility = Visibility.PRIVATE)
    public IRubyObject initialize(ThreadContext context, IRubyObject opts) {
        checkFrozen();
        processOptions(context, opts);

        return this;
    }

    /**
     * Checks the given string's encoding. If a non-UTF-8 encoding is detected,
     * a converted copy is returned.
     * Returns the source string if no conversion is needed.
     */
    private static RubyString convertEncoding(ThreadContext context, RubyString source) {
      Encoding encoding = source.getEncoding();
      if (encoding == UTF8Encoding.INSTANCE) return source;
      return convertOtherEncoding(context, encoding, source);
    }

    private static RubyString convertOtherEncoding(ThreadContext context, Encoding encoding, RubyString source) {
        if (encoding == ASCIIEncoding.INSTANCE) {
            source = (RubyString) source.dup();
            source.setEncoding(UTF8Encoding.INSTANCE);
            if (source.getCodeRange() != CR_7BIT) source.clearCodeRange();
        } else if (encoding != UTF8Encoding.INSTANCE) {
            source = (RubyString) source.encode(context, context.runtime.getEncodingService().convertEncodingToRubyEncoding(UTF8Encoding.INSTANCE));
        }
        return source;
    }
}

