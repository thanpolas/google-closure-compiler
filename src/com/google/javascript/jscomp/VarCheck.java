/*
 * Copyright 2004 Google Inc.
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

package com.google.javascript.jscomp;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.javascript.jscomp.NodeTraversal.AbstractPostOrderCallback;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;


/**
 * Checks that all variables are declared, that file-private variables are
 * accessed only in the file that declares them, and that any var references
 * that cross module boundaries respect declared module dependencies.
 *
*
*
 */
class VarCheck extends AbstractPostOrderCallback implements CompilerPass {

  static final DiagnosticType UNDEFINED_VAR_ERROR = DiagnosticType.error(
      "JSC_UNDEFINED_VARIABLE",
      "variable {0} is undefined");

  static final DiagnosticType VIOLATED_MODULE_DEP_ERROR = DiagnosticType.error(
      "JSC_VIOLATED_MODULE_DEPENDENCY",
      "module {0} cannot reference {2}, defined in " +
      "module {1}, since {1} loads after {0}");

  static final DiagnosticType MISSING_MODULE_DEP_ERROR = DiagnosticType.warning(
      "JSC_MISSING_MODULE_DEPENDENCY",
      "missing module dependency; module {0} should depend " +
      "on module {1} because it references {2}");

  static final DiagnosticType STRICT_MODULE_DEP_ERROR = DiagnosticType.disabled(
      "JSC_STRICT_MODULE_DEPENDENCY",
      "module {0} cannot reference {2}, defined in " +
      "module {1}");

  static final DiagnosticType NAME_REFERENCE_IN_EXTERNS_ERROR =
    DiagnosticType.warning(
      "JSC_NAME_REFERENCE_IN_EXTERNS",
      "accessing name {0} in externs has no effect");

  static final DiagnosticType INVALID_FUNCTION_DECL =
    DiagnosticType.error("JSC_INVALID_FUNCTION_DECL",
        "Syntax error: function declaration must have a name");

  private CompilerInput synthesizedExternsInput = null;
  private Node synthesizedExternsRoot = null;

  private final AbstractCompiler compiler;

  // Whether this is the post-processing sanity check.
  private final boolean sanityCheck;

  VarCheck(AbstractCompiler compiler) {
    this(compiler, false);
  }

  VarCheck(AbstractCompiler compiler, boolean sanityCheck) {
    this.compiler = compiler;
    this.sanityCheck = sanityCheck;
  }

  /** {@inheritDoc} */
  public void process(Node externs, Node root) {
    NodeTraversal.traverse(compiler, externs, new NameRefInExternsCheck());
    NodeTraversal.traverseRoots(
        compiler, Lists.newArrayList(externs, root), this);
  }

  /** {@inheritDoc} */
  public void visit(NodeTraversal t, Node n, Node parent) {
    if (n.getType() != Token.NAME) {
      return;
    }

    if (NodeUtil.isLabelName(n)) {
      return;
    }

    String varName = n.getString();

    // Only a function can have an empty name.
    if (varName.isEmpty()) {
      Preconditions.checkState(NodeUtil.isFunction(parent));

      // A function declaration with an empty name passes Rhino,
      // but is supposed to be a syntax error according to the spec.
      if (!NodeUtil.isAnonymousFunction(parent)) {
        t.report(n, INVALID_FUNCTION_DECL);
      }
      return;
    }

    // Check that the var has been declared.
    Scope scope = t.getScope();
    Scope.Var var = scope.getVar(varName);
    if (var == null) {
      if (NodeUtil.isAnonymousFunction(parent)) {
        // e.g. [ function foo() {} ], it's okay if "foo" isn't defined in the
        // current scope.
      } else {
        t.report(n, UNDEFINED_VAR_ERROR, varName);

        if (sanityCheck) {
          throw new IllegalStateException("Unexpected variable " + varName);
        } else {
          // Create a new variable in a synthetic script. This will prevent
          // subsequent compiler passes from crashing.
          Node nameNode = Node.newString(Token.NAME, varName);
          getSynthesizedExternsRoot().addChildToBack(
              new Node(Token.VAR, nameNode));
          scope.getGlobalScope().declare(varName, nameNode,
              null, getSynthesizedExternsInput());
        }
      }
      return;
    }

    CompilerInput currInput = t.getInput();
    CompilerInput varInput = var.input;
    if (currInput == varInput || currInput == null || varInput == null) {
      // The variable was defined in the same file. This is fine.
      return;
    }

    // Check module dependencies.
    JSModule currModule = currInput.getModule();
    JSModule varModule = varInput.getModule();
    JSModuleGraph moduleGraph = compiler.getModuleGraph();
    if (varModule != currModule && varModule != null && currModule != null) {
      if (moduleGraph.dependsOn(currModule, varModule)) {
        // The module dependency was properly declared.
      } else {
        if (!sanityCheck && scope.isGlobal()) {
          if (moduleGraph.dependsOn(varModule, currModule)) {
            // The variable reference violates a declared module dependency.
            t.report(n, VIOLATED_MODULE_DEP_ERROR,
                     currModule.getName(), varModule.getName(), varName);
          } else {
            // The variable reference is between two modules that have no
            // dependency relationship. This should probably be considered an
            // error, but just issue a warning for now.
            t.report(n, MISSING_MODULE_DEP_ERROR,
                     currModule.getName(), varModule.getName(), varName);
          }
        } else {
          t.report(n, STRICT_MODULE_DEP_ERROR,
                   currModule.getName(), varModule.getName(), varName);
        }
      }
    }
  }

  /**
   * A check for name references in the externs inputs. These used to prevent
   * a variable from getting renamed, but no longer have any effect.
   */
  private class NameRefInExternsCheck extends AbstractPostOrderCallback {
    public void visit(NodeTraversal t, Node n, Node parent) {
      if (n.getType() == Token.NAME) {
        switch (parent.getType()) {
          case Token.VAR:
          case Token.FUNCTION:
          case Token.GETPROP:
          case Token.LP:
            // These are okay.
            break;
          default:
            t.report(n, NAME_REFERENCE_IN_EXTERNS_ERROR, n.getString());
            break;
        }
      }
    }
  }

  /** Lazily create a "new" externs input for undeclared variables. */
  private CompilerInput getSynthesizedExternsInput() {
    if (synthesizedExternsInput == null) {
      synthesizedExternsInput =
          compiler.newExternInput("{SyntheticVarsDeclar}");
    }
    return synthesizedExternsInput;
  }

  /** Lazily create a "new" externs root for undeclared variables. */
  private Node getSynthesizedExternsRoot() {
    if (synthesizedExternsRoot == null) {
      CompilerInput synthesizedExterns = getSynthesizedExternsInput();
      synthesizedExternsRoot = synthesizedExterns.getAstRoot(compiler);
    }
    return synthesizedExternsRoot;
  }
}
