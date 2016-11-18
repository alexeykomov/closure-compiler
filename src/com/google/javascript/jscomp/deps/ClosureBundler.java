/*
 * Copyright 2014 The Closure Compiler Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.javascript.jscomp.deps;

import com.google.common.base.Strings;
import com.google.common.io.CharSource;
import com.google.common.io.Files;
import com.google.javascript.jscomp.transpile.TranspileResult;
import com.google.javascript.jscomp.transpile.Transpiler;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A utility class to assist in creating JS bundle files.
 */
public class ClosureBundler {

  private final Transpiler transpiler;

  private EvalMode mode = EvalMode.NORMAL;
  private String sourceUrl = null;
  private String path = "unknown_source";

  // TODO(sdh): This cache should be moved out into a higher level, but is
  // currently required due to the API that source maps must be accessible
  // via just a path (and not the file contents).
  private final Map<String, String> sourceMapCache = new ConcurrentHashMap<>();

  // TODO(sdh): This causes serious problems with edit-refresh if the bundler
  // is used in the wrong scope.  The logic must be moved outside this class.
  private boolean shouldSendRuntime = true;

  public ClosureBundler() {
    this(Transpiler.NULL);
  }

  public ClosureBundler(Transpiler transpiler) {
    this.transpiler = transpiler;
  }

  public final ClosureBundler useEval(boolean useEval) {
    this.mode = useEval ? EvalMode.EVAL : EvalMode.NORMAL;
    return this;
  }

  public final ClosureBundler withSourceUrl(String sourceUrl) {
    this.sourceUrl = sourceUrl;
    return this;
  }

  public final ClosureBundler withPath(String path) {
    this.path = path;
    return this;
  }

  /** Append the contents of the string to the supplied appendable. */
  public static void appendInput(
      Appendable out,
      DependencyInfo info,
      String contents) throws IOException {
    new ClosureBundler().appendTo(out, info, contents);
  }

  /** Append the contents of the string to the supplied appendable. */
  public void appendTo(
      Appendable out,
      DependencyInfo info,
      String content) throws IOException {
    appendTo(out, info, CharSource.wrap(content));
  }

  /** Append the contents of the file to the supplied appendable. */
  public void appendTo(
      Appendable out,
      DependencyInfo info,
      File content, Charset contentCharset) throws IOException {
    appendTo(out, info, Files.asCharSource(content, contentCharset));
  }

  /** Append the contents of the CharSource to the supplied appendable. */
  public void appendTo(
      Appendable out,
      DependencyInfo info,
      CharSource content) throws IOException {
    // TODO(sdh): Move this logic into the bundle manager.
    if (shouldSendRuntime) {
      String runtime = transpiler.runtime();
      if (!runtime.isEmpty()) {
        mode.appendTraditional(runtime, out, null);
      }
      shouldSendRuntime = false;
    }

    if (info.isModule()) {
      mode.appendGoogModule(transpile(content.read()), out, sourceUrl);
    } else {
      mode.appendTraditional(transpile(content.read()), out, sourceUrl);
    }
  }

  /**
   * Subclasses that need to provide a source map for any transformed input can return it with this
   * method.
   */
  public String getSourceMap(String path) {
    return Strings.nullToEmpty(sourceMapCache.get(path));
  }

  private String transpile(String s) {
    TranspileResult result = transpiler.transpile(path, s);
    sourceMapCache.put(path, result.sourceMap());
    return result.transpiled();
  }

  private enum EvalMode {
    EVAL {
      @Override
      void appendTraditional(String s, Appendable out, String sourceUrl) throws IOException {
        out.append("(0,eval(\"");
        EscapeMode.ESCAPED.append(s, out);
        appendSourceUrl(out, EscapeMode.ESCAPED, sourceUrl);
        out.append("\"));\n");
      }

      @Override
      void appendGoogModule(String s, Appendable out, String sourceUrl) throws IOException {
        out.append("goog.loadModule(\"");
        EscapeMode.ESCAPED.append(s, out);
        appendSourceUrl(out, EscapeMode.ESCAPED, sourceUrl);
        out.append("\");\n");
      }
    },
    NORMAL {
      @Override
      void appendTraditional(String s, Appendable out, String sourceUrl) throws IOException {
        EscapeMode.NORMAL.append(s, out);
        appendSourceUrl(out, EscapeMode.NORMAL, sourceUrl);
      }

      @Override
      void appendGoogModule(String s, Appendable out, String sourceUrl) throws IOException {
        // add the prefix on the first line so the line numbers aren't affected.
        out.append(
            "goog.loadModule(function(exports) {"
            + "'use strict';");
        EscapeMode.NORMAL.append(s, out);
        out.append(
            "\n" // terminate any trailing single line comment.
            + ";" // terminate any trailing expression.
            + "return exports;});\n");
        appendSourceUrl(out, EscapeMode.NORMAL, sourceUrl);
      }
    };

    abstract void appendTraditional(String s, Appendable out, String sourceUrl) throws IOException;
    abstract void appendGoogModule(String s, Appendable out, String sourceUrl) throws IOException;
  }

  private enum EscapeMode {
    ESCAPED {
      @Override void append(String s, Appendable out) throws IOException {
        out.append(SourceCodeEscapers.javascriptEscaper().escape(s));
      }
    },
    NORMAL {
      @Override void append(String s, Appendable out) throws IOException {
        out.append(s);
      }
    };

    abstract void append(String s, Appendable out) throws IOException;
  }

  private static void appendSourceUrl(Appendable out, EscapeMode mode, String sourceUrl)
      throws IOException {
    if (sourceUrl == null) {
      return;
    }
    String toAppend = "\n//# sourceURL=" + sourceUrl + "\n";
    // Don't go through #append. That method relies on #transformInput,
    // but source URLs generally aren't valid JS inputs.
    mode.append(toAppend, out);
  }
}
