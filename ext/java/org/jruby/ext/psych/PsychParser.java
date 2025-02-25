/***** BEGIN LICENSE BLOCK *****
 * Version: EPL 1.0/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Eclipse Public
 * License Version 1.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of
 * the License at http://www.eclipse.org/legal/epl-v10.html
 *
 * Software distributed under the License is distributed on an "AS
 * IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * rights and limitations under the License.
 *
 * Copyright (C) 2010 Charles O Nutter <headius@headius.com>
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either of the GNU General Public License Version 2 or later (the "GPL"),
 * or the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the EPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the EPL, the GPL or the LGPL.
 ***** END LICENSE BLOCK *****/
package org.jruby.ext.psych;

import org.jcodings.Encoding;
import org.jcodings.specific.UTF16BEEncoding;
import org.jcodings.specific.UTF16LEEncoding;
import org.jcodings.specific.UTF8Encoding;
import org.jcodings.unicode.UnicodeEncoding;
import org.jruby.Ruby;
import org.jruby.RubyArray;
import org.jruby.RubyBoolean;
import org.jruby.RubyClass;
import org.jruby.RubyEncoding;
import org.jruby.RubyFixnum;
import org.jruby.RubyIO;
import org.jruby.RubyKernel;
import org.jruby.RubyModule;
import org.jruby.RubyObject;
import org.jruby.RubyString;
import org.jruby.anno.JRubyMethod;
import org.jruby.runtime.Block;
import org.jruby.runtime.Helpers;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.runtime.callsite.CachingCallSite;
import org.jruby.runtime.callsite.FunctionalCachingCallSite;
import org.jruby.util.ByteList;
import org.jruby.util.IOInputStream;
import org.jruby.util.io.EncodingUtils;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.api.LoadSettingsBuilder;
import org.snakeyaml.engine.v2.common.Anchor;
import org.snakeyaml.engine.v2.common.FlowStyle;
import org.snakeyaml.engine.v2.common.ScalarStyle;
import org.snakeyaml.engine.v2.common.SpecVersion;
import org.snakeyaml.engine.v2.events.AliasEvent;
import org.snakeyaml.engine.v2.events.DocumentEndEvent;
import org.snakeyaml.engine.v2.events.DocumentStartEvent;
import org.snakeyaml.engine.v2.events.Event;
import org.snakeyaml.engine.v2.events.ImplicitTuple;
import org.snakeyaml.engine.v2.events.MappingStartEvent;
import org.snakeyaml.engine.v2.events.ScalarEvent;
import org.snakeyaml.engine.v2.events.SequenceStartEvent;
import org.snakeyaml.engine.v2.exceptions.Mark;
import org.snakeyaml.engine.v2.exceptions.MarkedYamlEngineException;
import org.snakeyaml.engine.v2.exceptions.ParserException;
import org.snakeyaml.engine.v2.exceptions.ReaderException;
import org.snakeyaml.engine.v2.exceptions.ScannerException;
import org.snakeyaml.engine.v2.exceptions.YamlEngineException;
import org.snakeyaml.engine.v2.parser.Parser;
import org.snakeyaml.engine.v2.parser.ParserImpl;
import org.snakeyaml.engine.v2.scanner.ScannerImpl;
import org.snakeyaml.engine.v2.scanner.StreamReader;
import org.snakeyaml.engine.v2.schema.CoreSchema;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.MalformedInputException;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

import static org.jruby.ext.psych.PsychLibrary.YAMLEncoding.*;
import static org.jruby.runtime.Helpers.arrayOf;
import static org.jruby.runtime.Helpers.invoke;

public class PsychParser extends RubyObject {

    public static final String JRUBY_CALL_SITES = "_jruby_call_sites";

    public static void initPsychParser(Ruby runtime, RubyModule psych) {
        RubyClass psychParser = runtime.defineClassUnder("Parser", runtime.getObject(), PsychParser::new, psych);

        CachingCallSite[] sites =
                Arrays.stream(Call.values())
                .map((call) -> new FunctionalCachingCallSite(call.name()))
                .toArray(CachingCallSite[]::new);
        psychParser.setInternalVariable(JRUBY_CALL_SITES, sites);

        runtime.getLoadService().require("psych/syntax_error");
        psychParser.defineConstant("ANY", runtime.newFixnum(YAML_ANY_ENCODING.ordinal()));
        psychParser.defineConstant("UTF8", runtime.newFixnum(YAML_UTF8_ENCODING.ordinal()));
        psychParser.defineConstant("UTF16LE", runtime.newFixnum(YAML_UTF16LE_ENCODING.ordinal()));
        psychParser.defineConstant("UTF16BE", runtime.newFixnum(YAML_UTF16BE_ENCODING.ordinal()));

        psychParser.defineAnnotatedMethods(PsychParser.class);
    }

    public PsychParser(Ruby runtime, RubyClass klass) {
        super(runtime, klass);

        CachingCallSite[] sites = (CachingCallSite[]) klass.getInternalVariable(JRUBY_CALL_SITES);
        this.path = sites[Call.path.ordinal()];
        this.event_location = sites[Call.event_location.ordinal()];
        this.start_stream = sites[Call.start_stream.ordinal()];
        this.start_document = sites[Call.start_document.ordinal()];
        this.end_document = sites[Call.end_document.ordinal()];
        this.alias = sites[Call.alias.ordinal()];
        this.scalar = sites[Call.scalar.ordinal()];
        this.start_sequence = sites[Call.start_sequence.ordinal()];
        this.end_sequence = sites[Call.end_sequence.ordinal()];
        this.start_mapping = sites[Call.start_mapping.ordinal()];
        this.end_mapping = sites[Call.end_mapping.ordinal()];
        this.end_stream = sites[Call.end_stream.ordinal()];
        this.loadSettingsBuilder = LoadSettings.builder().setSchema(new CoreSchema());
    }

    private IRubyObject stringOrNilForAnchor(ThreadContext context, Optional<Anchor> value) {
        if (!value.isPresent()) return context.nil;

        return stringFor(context, value.get().getValue());
    }

    private IRubyObject stringOrNilFor(ThreadContext context, Optional<String> value) {
        if (!value.isPresent()) return context.nil;

        return stringFor(context, value.get());
    }
    
    private IRubyObject stringFor(ThreadContext context, String value) {
        Ruby runtime = context.runtime;

        boolean isUTF8 = true;
        Charset charset = RubyEncoding.UTF8;

        Encoding encoding = runtime.getDefaultInternalEncoding();
        if (encoding == null) {
            encoding = UTF8Encoding.INSTANCE;
            charset = RubyEncoding.UTF8;
        } else {
            Charset encodingCharset = encoding.getCharset();
            if (encodingCharset != null) {
                isUTF8 = encodingCharset == RubyEncoding.UTF8;
                charset = encodingCharset;
            }
        }

        ByteList bytes = new ByteList(
                isUTF8 ?
                        RubyEncoding.encodeUTF8(value) :
                        RubyEncoding.encode(value, charset),
                encoding);
        RubyString string = RubyString.newString(runtime, bytes);
        
        return string;
    }
    
    private StreamReader readerFor(ThreadContext context, IRubyObject yaml, LoadSettings loadSettings) {
        if (yaml instanceof RubyString) {
            return readerForString(context, (RubyString) yaml, loadSettings);
        }

        // fall back on IOInputStream, using default charset
        return readerForIO(context, yaml, loadSettings);
    }

    private static StreamReader readerForIO(ThreadContext context, IRubyObject yaml, LoadSettings loadSettings) {
        boolean isIO = yaml instanceof RubyIO;
        if (isIO || yaml.respondsTo("read")) {
            // default to UTF8 unless RubyIO has UTF16 as encoding
            Charset charset = RubyEncoding.UTF8;

            if (isIO) {
                Encoding enc = ((RubyIO) yaml).getReadEncoding();

                // libyaml treats non-utf encodings as utf-8 and hopes for the best.
                if (enc instanceof UTF16LEEncoding || enc instanceof UTF16BEEncoding) {
                    charset = enc.getCharset();
                }
            }

            CharsetDecoder decoder = charset.newDecoder();
            decoder.onMalformedInput(CodingErrorAction.REPORT);

            return new StreamReader(loadSettings, new InputStreamReader(new IOInputStream(yaml), decoder));
        } else {
            Ruby runtime = context.runtime;

            throw runtime.newTypeError(yaml, runtime.getIO());
        }
    }

    private static StreamReader readerForString(ThreadContext context, RubyString string, LoadSettings loadSettings) {
        ByteList byteList = string.getByteList();
        Encoding enc = byteList.getEncoding();

        // if not unicode, transcode to UTF8
        if (!(enc instanceof UnicodeEncoding)) {
            byteList = EncodingUtils.strConvEnc(context, byteList, enc, UTF8Encoding.INSTANCE);
            enc = UTF8Encoding.INSTANCE;
        }

        ByteArrayInputStream bais = new ByteArrayInputStream(byteList.getUnsafeBytes(), byteList.getBegin(), byteList.getRealSize());

        Charset charset = enc.getCharset();

        assert charset != null : "charset for encoding " + enc + " should not be null";

        InputStreamReader isr = new InputStreamReader(bais, charset);

        return new StreamReader(loadSettings, isr);
    }

    @JRubyMethod(name = "_native_parse")
    public IRubyObject parse(ThreadContext context, IRubyObject handler, IRubyObject yaml, IRubyObject path) {
        Ruby runtime = context.runtime;

        try {
            LoadSettings loadSettings = loadSettingsBuilder.build();
            parser = new ParserImpl(loadSettings, new ScannerImpl(loadSettings, readerFor(context, yaml, loadSettings)));

            if (path.isNil() && yaml.respondsTo("path")) {
                path = this.path.call(context, this, yaml);
            }

            while (parser.hasNext()) {
                event = parser.next();

                Mark start = event.getStartMark().orElseThrow(RuntimeException::new);
                IRubyObject start_line = runtime.newFixnum(start.getLine());
                IRubyObject start_column = runtime.newFixnum(start.getColumn());

                Mark end = event.getEndMark().orElseThrow(RuntimeException::new);
                IRubyObject end_line = runtime.newFixnum(end.getLine());
                IRubyObject end_column = runtime.newFixnum(end.getColumn());

                event_location.call(context, this, handler, start_line, start_column, end_line, end_column);

                switch (event.getEventId()) {
                    case StreamStart:
                        start_stream.call(context, this, handler, runtime.newFixnum(YAML_ANY_ENCODING.ordinal()));
                        break;
                    case DocumentStart:
                        handleDocumentStart(context, (DocumentStartEvent) event, handler);
                        break;
                    case DocumentEnd:
                        IRubyObject notExplicit = runtime.newBoolean(!((DocumentEndEvent) event).isExplicit());

                        end_document.call(context, this, handler, notExplicit);
                        break;
                    case Alias:
                        IRubyObject alias = stringOrNilForAnchor(context, ((AliasEvent) event).getAnchor());

                        this.alias.call(context, this, handler, alias);
                        break;
                    case Scalar:
                        handleScalar(context, (ScalarEvent) event, handler);
                        break;
                    case SequenceStart:
                        handleSequenceStart(context, (SequenceStartEvent) event, handler);
                        break;
                    case SequenceEnd:
                        end_sequence.call(context, this, handler);
                        break;
                    case MappingStart:
                        handleMappingStart(context, (MappingStartEvent) event, handler);
                        break;
                    case MappingEnd:
                        end_mapping.call(context, this, handler);
                        break;
                    case StreamEnd:
                        end_stream.call(context, this, handler);
                        break;
                }
            }
        } catch (ParserException pe) {
            parser = null;
            raiseParserException(context, pe, path);

        } catch (ScannerException se) {
            parser = null;
            StringBuilder message = new StringBuilder("syntax error");
            if (se.getProblemMark() != null) {
                message.append(se.getProblemMark().toString());
            }
            raiseParserException(context, se, path);

        } catch (ReaderException re) {
            parser = null;
            raiseParserException(context, re, path);

        } catch (YamlEngineException ye) {
            Throwable cause = ye.getCause();

            if (cause instanceof MalformedInputException) {
                // failure due to improperly encoded input
                raiseParserException(context, (MalformedInputException) cause, path);
            }

            throw ye;

        } catch (Throwable t) {
            Helpers.throwException(t);
            return this;
        }

        return this;
    }
    
    private void handleDocumentStart(ThreadContext context, DocumentStartEvent dse, IRubyObject handler) {
        Ruby runtime = context.runtime;

        Optional<SpecVersion> specVersion = dse.getSpecVersion();
        IRubyObject version = specVersion.isPresent() ?
                RubyArray.newArray(runtime, runtime.newFixnum(specVersion.get().getMajor()), runtime.newFixnum(specVersion.get().getMinor())) :
                RubyArray.newEmptyArray(runtime);

        Map<String, String> tagsMap = dse.getTags();
        RubyArray tags;
        int size;
        if (tagsMap != null  && (size = tagsMap.size()) > 0) {
            tags = RubyArray.newArray(runtime, size);
            for (Map.Entry<String, String> tag : tagsMap.entrySet()) {
                IRubyObject key = stringFor(context, tag.getKey());
                IRubyObject value = stringFor(context, tag.getValue());

                tags.append(RubyArray.newArray(runtime, key, value));
            }
        } else {
            tags = RubyArray.newEmptyArray(runtime);
        }

        IRubyObject notExplicit = runtime.newBoolean(!dse.isExplicit());

        start_document.call(context, this, handler, version, tags, notExplicit);
    }
    
    private void handleMappingStart(ThreadContext context, MappingStartEvent mse, IRubyObject handler) {
        Ruby runtime = context.runtime;
        IRubyObject anchor = stringOrNilForAnchor(context, mse.getAnchor());
        IRubyObject tag = stringOrNilFor(context, mse.getTag());
        IRubyObject implicit = runtime.newBoolean(mse.isImplicit());
        IRubyObject style = runtime.newFixnum(translateFlowStyle(mse.getFlowStyle()));

        start_mapping.call(context, this, handler, anchor, tag, implicit, style);
    }
        
    private void handleScalar(ThreadContext context, ScalarEvent se, IRubyObject handler) {
        Ruby runtime = context.runtime;

        IRubyObject anchor = stringOrNilForAnchor(context, se.getAnchor());
        IRubyObject tag = stringOrNilFor(context, se.getTag());
        ImplicitTuple implicit = se.getImplicit();
        IRubyObject plain_implicit = runtime.newBoolean(implicit.canOmitTagInPlainScalar());
        IRubyObject quoted_implicit = runtime.newBoolean(implicit.canOmitTagInNonPlainScalar());
        IRubyObject style = runtime.newFixnum(translateStyle(se.getScalarStyle()));
        IRubyObject val = stringFor(context, se.getValue());

        scalar.call(context, this, handler, val, anchor, tag, plain_implicit,
                quoted_implicit, style);
    }
    
    private void handleSequenceStart(ThreadContext context, SequenceStartEvent sse, IRubyObject handler) {
        Ruby runtime = context.runtime;
        IRubyObject anchor = stringOrNilForAnchor(context, sse.getAnchor());
        IRubyObject tag = stringOrNilFor(context, sse.getTag());
        IRubyObject implicit = runtime.newBoolean(sse.isImplicit());
        IRubyObject style = runtime.newFixnum(translateFlowStyle(sse.getFlowStyle()));

        start_sequence.call(context, this, handler, anchor, tag, implicit, style);
    }

    private static void raiseParserException(ThreadContext context, ReaderException re, IRubyObject rbPath) {
        Ruby runtime = context.runtime;
        RubyClass se;
        IRubyObject exception;

        se = (RubyClass) runtime.getModule("Psych").getConstant("SyntaxError");

        exception = se.newInstance(context,
                new IRubyObject[] {
                    rbPath,
                    RubyFixnum.zero(runtime),
                    RubyFixnum.zero(runtime),
                    runtime.newFixnum(re.getPosition()),
                    (null == re.getName() ? context.nil : runtime.newString(re.getName())),
                    (null == re.toString() ? context.nil : runtime.newString(re.toString()))
                },
                Block.NULL_BLOCK);

        RubyKernel.raise(context, runtime.getKernel(), new IRubyObject[] { exception }, Block.NULL_BLOCK);
    }

    private static void raiseParserException(ThreadContext context, MarkedYamlEngineException mye, IRubyObject rbPath) {
        Ruby runtime = context.runtime;
        Mark mark;
        RubyClass se;
        IRubyObject exception;

        se = (RubyClass)runtime.getModule("Psych").getConstant("SyntaxError");

        mark = mye.getProblemMark().get();

        exception = se.newInstance(context,
                new IRubyObject[] {
                    rbPath,
                    runtime.newFixnum(mark.getLine() + 1),
                    runtime.newFixnum(mark.getColumn() + 1),
                    runtime.newFixnum(mark.getIndex()),
                    (null == mye.getProblem() ? context.nil : runtime.newString(mye.getProblem())),
                    (null == mye.getContext() ? context.nil : runtime.newString(mye.getContext()))
                },
                Block.NULL_BLOCK);

        RubyKernel.raise(context, runtime.getKernel(), new IRubyObject[] { exception }, Block.NULL_BLOCK);
    }

    private static void raiseParserException(ThreadContext context, MalformedInputException mie, IRubyObject rbPath) {
        Ruby runtime = context.runtime;
        RubyClass se;
        IRubyObject exception;

        se = (RubyClass)runtime.getModule("Psych").getConstant("SyntaxError");

        mie.getInputLength();

        exception = se.newInstance(context,
                arrayOf(
                        rbPath,
                        RubyFixnum.minus_one(runtime),
                        RubyFixnum.minus_one(runtime),
                        runtime.newFixnum(mie.getInputLength()),
                        context.nil,
                        context.nil
                ),
                Block.NULL_BLOCK);

        RubyKernel.raise(context, runtime.getKernel(), new IRubyObject[] { exception }, Block.NULL_BLOCK);
    }

    private static int translateStyle(ScalarStyle style) {
        if (style == null) return 0; // any

        switch (style) {
            case PLAIN: return 1; // plain
            case SINGLE_QUOTED: return 2; // single-quoted
            case DOUBLE_QUOTED: return 3; // double-quoted
            case LITERAL: return 4; // literal
            case FOLDED: return 5; // folded
            default: return 0; // any
        }
    }
    
    private static int translateFlowStyle(FlowStyle flowStyle) {
        switch (flowStyle) {
            case AUTO: return 0;
            case BLOCK: return 1;
            case FLOW:
            default: return 2;
        }
    }

    @JRubyMethod
    public IRubyObject mark(ThreadContext context) {
        Ruby runtime = context.runtime;

        Event event = null;

        Parser parser = this.parser;
        if (parser != null) {
            if (parser.hasNext()) {
                event = parser.peekEvent();
            } else {
                event = this.event;
            }
        }

        if (event == null) {
            return ((RubyClass) runtime.getClassFromPath("Psych::Parser::Mark")).newInstance(
                    context,
                    RubyFixnum.zero(runtime),
                    RubyFixnum.zero(runtime),
                    RubyFixnum.zero(runtime),
                    Block.NULL_BLOCK
            );
        }

        Mark mark = event.getStartMark().orElseThrow(RuntimeException::new);

        return ((RubyClass) runtime.getClassFromPath("Psych::Parser::Mark")).newInstance(
                context,
                RubyFixnum.zero(runtime),
                runtime.newFixnum(mark.getLine()),
                runtime.newFixnum(mark.getColumn()),
                Block.NULL_BLOCK
        );
    }

    @JRubyMethod(name = "max_aliases_for_collections=")
    public IRubyObject max_aliases_for_collections_set(IRubyObject max) {
        loadSettingsBuilder.setMaxAliasesForCollections(max.convertToInteger().getIntValue());

        return max;
    }

    @JRubyMethod(name = "max_aliases_for_collections")
    public IRubyObject max_aliases_for_collections(ThreadContext context) {
        return context.runtime.newFixnum(buildSettings().getMaxAliasesForCollections());
    }

    @JRubyMethod(name = "allow_duplicate_keys=")
    public IRubyObject allow_duplicate_keys_set(IRubyObject allow) {
        loadSettingsBuilder.setAllowDuplicateKeys(allow.isTrue());

        return allow;
    }

    @JRubyMethod(name = "allow_duplicate_keys")
    public IRubyObject allow_duplicate_keys(ThreadContext context) {
        return RubyBoolean.newBoolean(context, buildSettings().getAllowDuplicateKeys());
    }

    @JRubyMethod(name = "allow_recursive_keys=")
    public IRubyObject allow_recursive_keys_set(IRubyObject allow) {
        loadSettingsBuilder.setAllowRecursiveKeys(allow.isTrue());

        return allow;
    }

    @JRubyMethod(name = "allow_recursive_keys")
    public IRubyObject allow_recursive_keys(ThreadContext context) {
        return RubyBoolean.newBoolean(context, buildSettings().getAllowRecursiveKeys());
    }

    @JRubyMethod(name = "code_point_limit=")
    public IRubyObject code_point_limit_set(IRubyObject limit) {
        loadSettingsBuilder.setCodePointLimit(limit.convertToInteger().getIntValue());

        return limit;
    }

    @JRubyMethod(name = "code_point_limit")
    public IRubyObject code_point_limit(ThreadContext context) {
        return context.runtime.newFixnum(buildSettings().getCodePointLimit());
    }

    private LoadSettings buildSettings() {
        return loadSettingsBuilder.build();
    }

    private Parser parser;
    private Event event;
    private final LoadSettingsBuilder loadSettingsBuilder;

    private enum Call {
        path, event_location, start_stream, start_document, end_document, alias, scalar, start_sequence, end_sequence, start_mapping, end_mapping, end_stream
    }

    private final CachingCallSite path, event_location, start_stream, start_document, end_document, alias, scalar, start_sequence, end_sequence, start_mapping, end_mapping, end_stream;
}
