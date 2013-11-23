/*
 * Copyright 2012 Google Inc. All Rights Reserved.
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

package com.google.errorprone;

import com.sun.source.util.TreePath;
import com.sun.tools.javac.code.Symbol.CompletionFailure;
import com.sun.tools.javac.comp.AttrContext;
import com.sun.tools.javac.comp.Env;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Log;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Used to run an error-prone analysis as a phase in the javac compiler.
 */
public class ErrorProneAnalyzer {

  private final Log log;
  private final Context context;
  private final Scanner errorProneScanner;
  // If matchListener != null, then we are in search mode.
  private final SearchResultsPrinter resultsPrinter;

  public ErrorProneAnalyzer(Log log, Context context) {
    this(log, context, null);
  }

  public ErrorProneAnalyzer(Log log, Context context, SearchResultsPrinter resultsPrinter) {
    this.log = log;
    this.context = context;
    this.resultsPrinter = resultsPrinter;
    this.errorProneScanner = context.get(Scanner.class);
    if (this.errorProneScanner == null) {
      throw new IllegalStateException(
          "No error-prone scanner registered in context. Is annotation processing enabled?");
    }
  }

  /**
   * Reports that a class (represented by the env) is ready for error-prone to analyze.
   */
  public void reportReadyForAnalysis(Env<AttrContext> env, boolean hasErrors) {
    try {
      TreePath path = new TreePath(env.toplevel);
      if (env.toplevel != env.tree) {
        path = TreePath.getPath(path, env.tree);
      }
      errorProneScanner.scan(path, createVisitorState(env));
    } catch (CompletionFailure e) {
      // A CompletionFailure can be triggered when error-prone tries to complete a symbol
      // that isn't on the compilation classpath. This can occur when a check performs an
      // instanceof test on a symbol, which requires inspecting the transitive closure of the
      // symbol's supertypes. If javac didn't need to check the symbol's assignability
      // then a normal compilation would have succeeded, and no diagnostics will have been
      // reported yet, but we don't want to crash javac.
      StringWriter message = new StringWriter();
      e.printStackTrace(new PrintWriter(message));
      // TODO(cushon): use CompletionFailure#getDetailValue() once we no longer care about JDK6.
      log.error("proc.cant.access", e.sym, e.errmsg, message.toString());
    } catch (RuntimeException e) {
      // If there is a RuntimeException in an analyzer, swallow it if there are other compiler
      // errors.  This prevents javac from exiting with code 4, Abnormal Termination.
      if (!hasErrors) {
        throw e;
      }
    }
  }

  /**
   * Create a VisitorState object from an environment.
   */
  private VisitorState createVisitorState(Env<AttrContext> env) {
    if (resultsPrinter != null) {
      resultsPrinter.setCompilationUnit(env.toplevel.sourcefile);
      return new VisitorState(context, resultsPrinter);
    } else {
      DescriptionListener logReporter = new JavacErrorDescriptionListener(log,
          env.toplevel.endPositions,
          env.enclClass.sym.sourcefile != null
          ? env.enclClass.sym.sourcefile
              : env.toplevel.sourcefile,
              context);
      return new VisitorState(context, logReporter);
    }
  }
}
