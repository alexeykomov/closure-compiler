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
package com.google.javascript.jscomp;


import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.Node;

/** Converts {@code super()} calls. This has to run after typechecking. */
public final class Es6ConvertSuperConstructorCalls
implements NodeTraversal.Callback, HotSwapCompilerPass {
  private final AbstractCompiler compiler;

  public Es6ConvertSuperConstructorCalls(AbstractCompiler compiler) {
    this.compiler = compiler;
  }

  @Override
  public boolean shouldTraverse(NodeTraversal t, Node n, Node parent) {
    return true;
  }

  @Override
  public void visit(NodeTraversal t, Node n, Node parent) {
    if (n.isSuper()) {
      visitSuper(n, parent);
    }
  }

  private void visitSuper(Node node, Node parent) {
    // NOTE: When this pass runs:
    // -   ES6 classes have already been rewritten as ES5 functions.
    // -   All instances of super() that are not super constructor calls have been rewritten.
    // -   However, if the original call used spread (e.g. super(...list)), then spread
    //     transpilation will have turned that into something like
    //     super.apply(null, $jscomp$expanded$args).
    if (node.isFromExterns()) {
      // This class is defined in an externs file, so it's only a stub, not the actual
      // implementation that should be instantiated.
      // A call to super() shouldn't actually exist for a stub and is problematic to transpile,
      // so just drop it.
      NodeUtil.getEnclosingStatement(node).detach();
      compiler.reportCodeChange();
    } else if (parent.isCall()) {
      visitSuperCall(node, parent);
    } else {
      visitSuperApplyCall(node, parent);
    }
  }

  /**
   * Converts {@code super(..args..)} to {@code SuperClass.call(this, ..args..)}
   *
   * @param superNode
   * @param superCall
   */
  private void visitSuperCall(Node superNode, Node superCall) {
    checkArgument(superCall.isCall(), superCall);
    checkArgument(superCall.getFirstChild() == superNode, superCall);

    Node superClassQName = createSuperClassQNameNode(superCall);
    Node superClassDotCall = IR.getprop(superClassQName, IR.string("call"));
    superClassDotCall.useSourceInfoFromForTree(superNode);
    Node newSuperCall = NodeUtil.newCallNode(superClassDotCall, IR.thisNode());
    newSuperCall.useSourceInfoIfMissingFromForTree(superCall);
    superCall.removeFirstChild();
    newSuperCall.addChildrenToBack(superCall.removeChildren());
    superCall.getParent().replaceChild(superCall, newSuperCall);
    compiler.reportCodeChange();
  }

  /**
   * Converts {@code super.apply(null, ..args..)} to {@code SuperClass.apply(this, ..args..)}.
   *
   * @param superNode
   * @param superDotApply
   */
  private void visitSuperApplyCall(Node superNode, Node superDotApply) {
    // super.apply(null, ...)
    checkArgument(superDotApply.isGetProp(), superDotApply);
    Node applyNode = checkNotNull(superDotApply.getSecondChild());
    checkState(applyNode.getString().equals("apply"), applyNode);

    Node superCall = superDotApply.getParent();
    checkState(superCall.isCall(), superCall);
    checkState(superCall.getFirstChild() == superDotApply, superCall);
    Node nullNode = superCall.getSecondChild();
    checkState(nullNode.isNull(), nullNode);

    Node superClassQName = createSuperClassQNameNode(superCall);
    superClassQName.useSourceInfoFromForTree(superNode);
    superDotApply.replaceChild(superNode, superClassQName);
    Node thisNode = IR.thisNode().useSourceInfoFrom(nullNode);
    superCall.replaceChild(nullNode, thisNode);
    compiler.reportCodeChange();
  }

  private Node createSuperClassQNameNode(Node superCall) {
    // Find the $jscomp.inherits() call and take the super class name from there.
    Node enclosingConstructor = checkNotNull(NodeUtil.getEnclosingFunction(superCall));
    String className = NodeUtil.getNameNode(enclosingConstructor).getQualifiedName();
    Node constructorStatement = checkNotNull(NodeUtil.getEnclosingStatement(enclosingConstructor));

    for (Node statement = constructorStatement.getNext();
        statement != null;
        statement = statement.getNext()) {
      String superClassName = getSuperClassNameIfIsInheritsStatement(statement, className);
      if (superClassName != null) {
        return NodeUtil.newQName(compiler, superClassName);
      }
    }

    throw new IllegalStateException("$jscomp.inherits() call not found.");
  }

  private String getSuperClassNameIfIsInheritsStatement(Node statement, String className) {
    // $jscomp.inherits(ChildClass, SuperClass);
    if (!statement.isExprResult()) {
      return null;
    }
    Node callNode = statement.getFirstChild();
    if (!callNode.isCall()) {
      return null;
    }
    Node jscompDotInherits = callNode.getFirstChild();
    if (!jscompDotInherits.matchesQualifiedName("$jscomp.inherits")) {
      return null;
    }
    Node classNameNode = checkNotNull(jscompDotInherits.getNext());
    if (classNameNode.matchesQualifiedName(className)) {
      Node superClass = checkNotNull(classNameNode.getNext());
      return superClass.getQualifiedName();
    } else {
      return null;
    }
  }

  @Override
  public void process(Node externs, Node root) {
    // Might need to synthesize constructors for ambient classes in .d.ts externs
    TranspilationPasses.processTranspile(compiler, externs, this);
    TranspilationPasses.processTranspile(compiler, root, this);
  }

  @Override
  public void hotSwapScript(Node scriptRoot, Node originalRoot) {
    TranspilationPasses.hotSwapTranspile(compiler, scriptRoot, this);
  }
}
