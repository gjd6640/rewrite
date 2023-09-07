/*
 * Copyright 2022 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.kotlin.internal;

import org.jetbrains.annotations.NotNull;
import org.openrewrite.Cursor;
import org.openrewrite.PrintOutputCapture;
import org.openrewrite.Tree;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.java.JavaPrinter;
import org.openrewrite.java.marker.ImplicitReturn;
import org.openrewrite.java.marker.OmitParentheses;
import org.openrewrite.java.marker.TrailingComma;
import org.openrewrite.java.tree.*;
import org.openrewrite.kotlin.KotlinVisitor;
import org.openrewrite.kotlin.marker.*;
import org.openrewrite.kotlin.tree.K;
import org.openrewrite.kotlin.tree.KContainer;
import org.openrewrite.kotlin.tree.KRightPadded;
import org.openrewrite.kotlin.tree.KSpace;
import org.openrewrite.marker.Marker;
import org.openrewrite.marker.Markers;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;

public class KotlinPrinter<P> extends KotlinVisitor<PrintOutputCapture<P>> {
    private final KotlinJavaPrinter<P> delegate;

    public KotlinPrinter() {
        delegate = delegate();
    }

    protected KotlinJavaPrinter<P> delegate() {
        return new KotlinJavaPrinter<>(this);
    }

    @Override
    public J visit(@Nullable Tree tree, PrintOutputCapture<P> p) {
        if (!(tree instanceof K)) {
            // re-route printing to the java printer
            return delegate.visit(tree, p);
        } else {
            return super.visit(tree, p);
        }
    }

    @Override
    public J visitCompilationUnit(K.CompilationUnit sourceFile, PrintOutputCapture<P> p) {
        beforeSyntax(sourceFile, Space.Location.COMPILATION_UNIT_PREFIX, p);

        visit(sourceFile.getAnnotations(), p);

        JRightPadded<J.Package> pkg = sourceFile.getPadding().getPackageDeclaration();
        if (pkg != null) {
            visitRightPadded(pkg, p);
        }

        for (JRightPadded<J.Import> import_ : sourceFile.getPadding().getImports()) {
            visitRightPadded(import_, p);
        }

        for (JRightPadded<Statement> statement : sourceFile.getPadding().getStatements()) {
            visitRightPadded(statement, p);
        }

        visitSpace(sourceFile.getEof(), Space.Location.COMPILATION_UNIT_EOF, p);
        afterSyntax(sourceFile, p);
        return sourceFile;
    }

    @Override
    public J visitAnnotatedExpression(K.AnnotatedExpression annotatedExpression, PrintOutputCapture<P> p) {
        visit(annotatedExpression.getAnnotations(), p);
        visit(annotatedExpression.getExpression(), p);
        afterSyntax(annotatedExpression, p);
        return annotatedExpression;
    }

    @Override
    public J visitBinary(K.Binary binary, PrintOutputCapture<P> p) {
        beforeSyntax(binary, Space.Location.BINARY_PREFIX, p);
        String keyword = "";
        switch (binary.getOperator()) {
            case Contains:
                keyword = "in";
                break;
            case NotContains:
                keyword = "!in";
                break;
            case IdentityEquals:
                keyword = "===";
                break;
            case IdentityNotEquals:
                keyword = "!==";
                break;
            case RangeTo:
                keyword = "..";
                break;
            case RangeUntil:
                keyword = "..<";
                break;
        }
        visit(binary.getLeft(), p);
        visitSpace(binary.getPadding().getOperator().getBefore(), KSpace.Location.BINARY_OPERATOR, p);
        p.append(keyword);
        if (binary.getOperator() == K.Binary.Type.Get) {
            p.append("[");
        }

        visit(binary.getRight(), p);

        visitSpace(binary.getAfter(), KSpace.Location.BINARY_SUFFIX, p);
        if (binary.getOperator() == K.Binary.Type.Get) {
            p.append("]");
        }
        afterSyntax(binary, p);
        return binary;
    }

    @Override
    public J visitDestructuringDeclaration(K.DestructuringDeclaration destructuringDeclaration, PrintOutputCapture<P> p) {
        beforeSyntax(destructuringDeclaration, KSpace.Location.DESTRUCTURING_DECLARATION_PREFIX, p);
        visit(destructuringDeclaration.getInitializer().getLeadingAnnotations(), p);
        for (J.Modifier m : destructuringDeclaration.getInitializer().getModifiers()) {
            delegate.visitModifier(m, p);
            if (m.getType().equals(J.Modifier.Type.Final)) {
                p.append("val");
            }
        }
        visitSpace(destructuringDeclaration.getPadding().getAssignments().getBefore(), Space.Location.LANGUAGE_EXTENSION, p);
        p.append("(");
        List<JRightPadded<J.VariableDeclarations.NamedVariable>> elements = destructuringDeclaration.getPadding().getAssignments().getPadding().getElements();
        for (int i = 0; i < elements.size(); i++) {
            JRightPadded<J.VariableDeclarations.NamedVariable> element = elements.get(i);
            visit(element.getElement().getName(), p);
            visitSpace(element.getAfter(), Space.Location.LANGUAGE_EXTENSION, p);
            p.append(i == elements.size() - 1 ? ")" : ",");
        }

        if (!destructuringDeclaration.getInitializer().getVariables().isEmpty() &&
            destructuringDeclaration.getInitializer().getVariables().get(0).getPadding().getInitializer() != null) {
            visitSpace(Objects.requireNonNull(destructuringDeclaration.getInitializer().getVariables().get(0).getPadding()
                    .getInitializer()).getBefore(), Space.Location.LANGUAGE_EXTENSION, p);
            p.append("=");
            visit(Objects.requireNonNull(destructuringDeclaration.getInitializer().getVariables().get(0).getPadding().getInitializer()).getElement(), p);
        }
        afterSyntax(destructuringDeclaration, p);
        return destructuringDeclaration;
    }

    @Override
    public J visitFunctionType(K.FunctionType functionType, PrintOutputCapture<P> p) {
        beforeSyntax(functionType, KSpace.Location.FUNCTION_TYPE_PREFIX, p);
        visit(functionType.getLeadingAnnotations(), p);
        for (J.Modifier modifier : functionType.getModifiers()) {
            delegate.visitModifier(modifier, p);
        }

        if (functionType.getReceiver() != null) {
            visitRightPadded(functionType.getReceiver(), p);
            p.append(".");
        }
        visit(functionType.getTypedTree(), p);
        afterSyntax(functionType, p);
        return functionType;
    }

    @Override
    public J visitKReturn(K.KReturn kReturn, PrintOutputCapture<P> p) {
        // backwards compatibility: leave this in until `K.KReturn#annotations` has been deleted
        visit(kReturn.getAnnotations(), p);
        J.Return return_ = kReturn.getExpression();
        if (kReturn.getLabel() != null) {
            beforeSyntax(return_, Space.Location.RETURN_PREFIX, p);
            p.append("return");
            p.append("@");
            visit(kReturn.getLabel(), p);
            if (return_.getExpression() != null) {
                visit(return_.getExpression(), p);
            }
            afterSyntax(return_, p);
        } else {
            visit(kReturn.getExpression(), p);
        }
        return kReturn;
    }

    @Override
    public J visitKString(K.KString kString, PrintOutputCapture<P> p) {
        beforeSyntax(kString, KSpace.Location.KSTRING_PREFIX, p);

        String delimiter = kString.getDelimiter();
        p.append(delimiter);

        visit(kString.getStrings(), p);
        p.append(delimiter);

        afterSyntax(kString, p);
        return kString;
    }

    @Override
    public J visitKThis(K.KThis kThis, PrintOutputCapture<P> p) {
        beforeSyntax(kThis, KSpace.Location.KTHIS_PREFIX, p);

        p.append("this");
        if (kThis.getLabel() != null) {
            p.append("@");
            visit(kThis.getLabel(), p);
        }

        afterSyntax(kThis, p);
        return kThis;
    }

    @Override
    public J visitKStringValue(K.KString.Value value, PrintOutputCapture<P> p) {
        beforeSyntax(value, KSpace.Location.KSTRING_PREFIX, p);
        if (value.isEnclosedInBraces()) {
            p.append("${");
        } else {
            p.append("$");
        }
        visit(value.getTree(), p);
        if (value.isEnclosedInBraces()) {
            visitSpace(value.getAfter(), KSpace.Location.KSTRING_SUFFIX, p);
            p.append('}');
        }
        afterSyntax(value, p);
        return value;
    }

    @Override
    public J visitListLiteral(K.ListLiteral listLiteral, PrintOutputCapture<P> p) {
        beforeSyntax(listLiteral, KSpace.Location.LIST_LITERAL_PREFIX, p);
        visitContainer("[", listLiteral.getPadding().getElements(), KContainer.Location.LIST_LITERAL_ELEMENTS,
                "]", p);
        afterSyntax(listLiteral, p);
        return listLiteral;
    }

    @Override
    public J visitProperty(K.Property property, PrintOutputCapture<P> p) {
        beforeSyntax(property, KSpace.Location.PROPERTY_PREFIX, p);

        J.VariableDeclarations vd = property.getVariableDeclarations();
        visit(vd.getLeadingAnnotations(), p);
        for (J.Modifier m : vd.getModifiers()) {
            delegate.visitModifier(m, p);
            if (m.getType().equals(J.Modifier.Type.Final)) {
                p.append("val");
            }
        }

        delegate.visitContainer("<", property.getPadding().getTypeParameters(), JContainer.Location.TYPE_PARAMETERS, ",", ">", p);

        Extension extension = vd.getMarkers().findFirst(Extension.class).orElse(null);
        if (extension != null) {
            if (property.getSetter() != null &&
                !property.getSetter().getParameters().isEmpty() &&
                property.getSetter().getParameters().get(0) instanceof J.VariableDeclarations) {
                visit(((J.VariableDeclarations) property.getSetter().getParameters().get(0)).getTypeExpression(), p);
                delegate.visitSpace(property.getSetter().getPadding().getParameters().getPadding().getElements().get(0).getAfter(), Space.Location.LANGUAGE_EXTENSION, p);
                p.append(".");
            } else if (property.getGetter() != null &&
                       !property.getGetter().getParameters().isEmpty() &&
                       property.getGetter().getParameters().get(0) instanceof J.VariableDeclarations) {
                visit(((J.VariableDeclarations) property.getGetter().getParameters().get(0)).getTypeExpression(), p);
                delegate.visitSpace(property.getGetter().getPadding().getParameters().getPadding().getElements().get(0).getAfter(), Space.Location.LANGUAGE_EXTENSION, p);
                p.append(".");
            }
        }

        if (!vd.getVariables().isEmpty()) {
            J.VariableDeclarations.NamedVariable nv = vd.getVariables().get(0);
            beforeSyntax(nv, Space.Location.VARIABLE_PREFIX, p);
            visit(nv.getName(), p);
            if (vd.getTypeExpression() != null) {
                vd.getMarkers().findFirst(TypeReferencePrefix.class).ifPresent(tref -> visitSpace(tref.getPrefix(), KSpace.Location.TYPE_REFERENCE_PREFIX, p));
                p.append(":");
                visit(vd.getTypeExpression(), p);
            }

            if (nv.getInitializer() != null) {
                String equals = getEqualsText(vd);

                visitSpace(Objects.requireNonNull(nv.getPadding().getInitializer()).getBefore(), Space.Location.VARIABLE_INITIALIZER, p);
                p.append(equals);
            }
            visit(nv.getInitializer(), p);
        }

        if (property.isSetterFirst()) {
            visit(property.getSetter(), p);
            visit(property.getGetter(), p);
        } else {
            visit(property.getGetter(), p);
            visit(property.getSetter(), p);
        }

        afterSyntax(property, p);
        return property;
    }

    @Override
    public J visitWhen(K.When when, PrintOutputCapture<P> p) {
        beforeSyntax(when, KSpace.Location.WHEN_PREFIX, p);
        p.append("when");
        visit(when.getSelector(), p);
        visit(when.getBranches(), p);

        afterSyntax(when, p);
        return when;
    }

    @Override
    public J visitWhenBranch(K.WhenBranch whenBranch, PrintOutputCapture<P> p) {
        beforeSyntax(whenBranch, KSpace.Location.WHEN_BRANCH_PREFIX, p);
        visitContainer("", whenBranch.getPadding().getExpressions(), KContainer.Location.WHEN_BRANCH_EXPRESSION, "->", p);
        visit(whenBranch.getBody(), p);
        afterSyntax(whenBranch, p);
        return whenBranch;
    }

    public static class KotlinJavaPrinter<P> extends JavaPrinter<P> {
        KotlinPrinter<P> kotlinPrinter;

        public KotlinJavaPrinter(KotlinPrinter<P> kp) {
            kotlinPrinter = kp;
        }

        @Override
        public J visit(@Nullable Tree tree, PrintOutputCapture<P> p) {
            if (tree instanceof K) {
                // re-route printing back up to groovy
                return kotlinPrinter.visit(tree, p);
            } else {
                return super.visit(tree, p);
            }
        }

        @Override
        public J visitAnnotation(J.Annotation annotation, PrintOutputCapture<P> p) {
            beforeSyntax(annotation, Space.Location.ANNOTATION_PREFIX, p);
            boolean isKModifier = annotation.getMarkers().findFirst(Modifier.class).isPresent();
            if (!isKModifier) {
                p.append("@");
            }

            AnnotationCallSite callSite = annotation.getMarkers().findFirst(AnnotationCallSite.class).orElse(null);
            if (callSite != null) {
                p.append(callSite.getName());
                kotlinPrinter.visitSpace(callSite.getSuffix(), KSpace.Location.FILE_ANNOTATION_SUFFIX, p);
                p.append(":");
            }
            visit(annotation.getAnnotationType(), p);
            if (!isKModifier) {
                visitContainer("(", annotation.getPadding().getArguments(), JContainer.Location.ANNOTATION_ARGUMENTS, ",", ")", p);
            }
            afterSyntax(annotation, p);
            return annotation;
        }

        @Override
        public J visitBinary(J.Binary binary, PrintOutputCapture<P> p) {
            String keyword = "";
            switch (binary.getOperator()) {
                case Addition:
                    keyword = "+";
                    break;
                case Subtraction:
                    keyword = "-";
                    break;
                case Multiplication:
                    keyword = "*";
                    break;
                case Division:
                    keyword = "/";
                    break;
                case Modulo:
                    keyword = "%";
                    break;
                case LessThan:
                    keyword = "<";
                    break;
                case GreaterThan:
                    keyword = ">";
                    break;
                case LessThanOrEqual:
                    keyword = "<=";
                    break;
                case GreaterThanOrEqual:
                    keyword = ">=";
                    break;
                case Equal:
                    keyword = "==";
                    break;
                case NotEqual:
                    keyword = "!=";
                    break;
                case BitAnd:
                    keyword = "&";
                    break;
                case BitOr:
                    keyword = "|";
                    break;
                case BitXor:
                    keyword = "^";
                    break;
                case LeftShift:
                    keyword = "<<";
                    break;
                case RightShift:
                    keyword = ">>";
                    break;
                case UnsignedRightShift:
                    keyword = ">>>";
                    break;
                case Or:
                    keyword = (binary.getMarkers().findFirst(LogicalComma.class).isPresent()) ? "," : "||";
                    break;
                case And:
                    keyword = "&&";
                    break;
            }
            beforeSyntax(binary, Space.Location.BINARY_PREFIX, p);
            visit(binary.getLeft(), p);
            visitSpace(binary.getPadding().getOperator().getBefore(), Space.Location.BINARY_OPERATOR, p);
            p.append(keyword);
            visit(binary.getRight(), p);
            afterSyntax(binary, p);
            return binary;
        }

        @Override
        public J visitBlock(J.Block block, PrintOutputCapture<P> p) {
            beforeSyntax(block, Space.Location.BLOCK_PREFIX, p);

            if (block.isStatic()) {
                p.append("init");
                visitRightPadded(block.getPadding().getStatic(), JRightPadded.Location.STATIC_INIT, p);
            }

            boolean singleExpressionBlock = block.getMarkers().findFirst(SingleExpressionBlock.class).isPresent();
            if (singleExpressionBlock) {
                p.append("=");
            }

            boolean omitBraces = block.getMarkers().findFirst(OmitBraces.class).isPresent();
            if (!omitBraces) {
                p.append("{");
            }
            visitStatements(block.getPadding().getStatements(), JRightPadded.Location.BLOCK_STATEMENT, p);
            visitSpace(block.getEnd(), Space.Location.BLOCK_END, p);
            if (!omitBraces) {
                p.append("}");
            }
            afterSyntax(block, p);
            return block;
        }

        @Override
        public J visitBreak(J.Break breakStatement, PrintOutputCapture<P> p) {
            beforeSyntax(breakStatement, Space.Location.BREAK_PREFIX, p);
            p.append("break");
            if (breakStatement.getLabel() != null) {
                p.append("@");
            }
            visit(breakStatement.getLabel(), p);
            afterSyntax(breakStatement, p);
            return breakStatement;
        }

        @Override
        public void visitContainer(String before, @Nullable JContainer<? extends J> container, JContainer.Location location, String suffixBetween, @Nullable String after, PrintOutputCapture<P> p) {
            super.visitContainer(before, container, location, suffixBetween, after, p);
        }

        @Override
        public <J2 extends J> JContainer<J2> visitContainer(@Nullable JContainer<J2> container, JContainer.Location loc, PrintOutputCapture<P> pPrintOutputCapture) {
            return super.visitContainer(container, loc, pPrintOutputCapture);
        }

        @Override
        public J visitClassDeclaration(J.ClassDeclaration classDecl, PrintOutputCapture<P> p) {
            beforeSyntax(classDecl, Space.Location.CLASS_DECLARATION_PREFIX, p);
            visit(classDecl.getLeadingAnnotations(), p);
            for (J.Modifier m : classDecl.getModifiers()) {
                visitModifier(m, p);
            }

            String kind;
            if (classDecl.getKind() == J.ClassDeclaration.Kind.Type.Class || classDecl.getKind() == J.ClassDeclaration.Kind.Type.Enum || classDecl.getKind() == J.ClassDeclaration.Kind.Type.Annotation) {
                kind = "class";
            } else if (classDecl.getKind() == J.ClassDeclaration.Kind.Type.Interface) {
                kind = "interface";
            } else {
                throw new UnsupportedOperationException("Class kind is not supported: " + classDecl.getKind());
            }

            visit(classDecl.getAnnotations().getKind().getAnnotations(), p);
            visitSpace(classDecl.getAnnotations().getKind().getPrefix(), Space.Location.CLASS_KIND, p);

            KObject KObject = classDecl.getMarkers().findFirst(KObject.class).orElse(null);
            if (KObject != null) {
                p.append("object");
                if (!classDecl.getName().getMarkers().findFirst(Implicit.class).isPresent()) {
                    visit(classDecl.getName(), p);
                }
            } else {
                p.append(kind);
                visit(classDecl.getName(), p);
            }

            visitContainer("<", classDecl.getPadding().getTypeParameters(), JContainer.Location.TYPE_PARAMETERS, ",", ">", p);

            if (classDecl.getMarkers().findFirst(PrimaryConstructor.class).isPresent()) {
                for (Statement statement : classDecl.getBody().getStatements()) {
                    if (statement instanceof J.MethodDeclaration &&
                        statement.getMarkers().findFirst(PrimaryConstructor.class).isPresent() &&
                        !statement.getMarkers().findFirst(Implicit.class).isPresent()) {
                        J.MethodDeclaration method = (J.MethodDeclaration) statement;
                        beforeSyntax(method, Space.Location.METHOD_DECLARATION_PREFIX, p);
                        visit(method.getLeadingAnnotations(), p);
                        for (J.Modifier modifier : method.getModifiers()) {
                            visitModifier(modifier, p);
                        }
                        JContainer<Statement> params = method.getPadding().getParameters();
                        beforeSyntax(params.getBefore(), params.getMarkers(), JContainer.Location.METHOD_DECLARATION_PARAMETERS.getBeforeLocation(), p);
                        p.append("(");
                        List<JRightPadded<Statement>> elements = params.getPadding().getElements();
                        for (int i = 0; i < elements.size(); i++) {
                            printMethodParameters(p, i, elements);
                        }
                        afterSyntax(params.getMarkers(), p);
                        p.append(")");
                        afterSyntax(method, p);
                        break;
                    }
                }
            }

            if (classDecl.getImplements() != null) {
                JContainer<TypeTree> container = classDecl.getPadding().getImplements();
                beforeSyntax(container.getBefore(), container.getMarkers(), JContainer.Location.IMPLEMENTS.getBeforeLocation(), p);
                p.append(":");
                List<? extends JRightPadded<? extends J>> nodes = container.getPadding().getElements();
                for (int i = 0; i < nodes.size(); i++) {
                    JRightPadded<? extends J> node = nodes.get(i);
                    J element = node.getElement();
                    visit(element.getMarkers().findFirst(ConstructorDelegation.class)
                                    .flatMap(m -> getConstructorDelegationCall(classDecl)
                                            .map(c -> (J) c.withName((J.Identifier) element).withPrefix(Space.EMPTY)))
                                    .orElse(element),
                            p);
                    visitSpace(node.getAfter(), JContainer.Location.IMPLEMENTS.getElementLocation().getAfterLocation(), p);
                    visitMarkers(node.getMarkers(), p);
                    if (i < nodes.size() - 1) {
                        p.append(",");
                    }
                }
                afterSyntax(container.getMarkers(), p);
            }

            if (!classDecl.getBody().getMarkers().findFirst(OmitBraces.class).isPresent()) {
                visit(classDecl.getBody(), p);
            }
            afterSyntax(classDecl, p);
            return classDecl;
        }

        private Optional<J.MethodInvocation> getConstructorDelegationCall(J.ClassDeclaration classDecl) {
            for (Statement statement : classDecl.getBody().getStatements()) {
                if (statement instanceof J.MethodDeclaration && statement.getMarkers().findFirst(PrimaryConstructor.class).isPresent()) {
                    J.MethodDeclaration constructor = (J.MethodDeclaration) statement;
                    if (constructor.isConstructor() && constructor.getBody() != null && !constructor.getBody().getStatements().isEmpty()) {
                        Statement delegationCall = constructor.getBody().getStatements().get(0);
                        if (delegationCall instanceof J.MethodInvocation && delegationCall.getMarkers().findFirst(ConstructorDelegation.class).isPresent()) {
                            return Optional.of((J.MethodInvocation) delegationCall);
                        }
                    }
                }
            }
            return Optional.empty();
        }

        @Override
        public J visitContinue(J.Continue continueStatement, PrintOutputCapture<P> p) {
            beforeSyntax(continueStatement, Space.Location.CONTINUE_PREFIX, p);
            p.append("continue");
            if (continueStatement.getLabel() != null) {
                p.append("@");
            }
            visit(continueStatement.getLabel(), p);
            afterSyntax(continueStatement, p);
            return continueStatement;
        }

        @Override
        public J visitFieldAccess(J.FieldAccess fieldAccess, PrintOutputCapture<P> p) {
            beforeSyntax(fieldAccess, Space.Location.FIELD_ACCESS_PREFIX, p);
            visit(fieldAccess.getTarget(), p);
            String prefix = fieldAccess.getMarkers().findFirst(IsNullSafe.class).isPresent() ? "?." : ".";
            visitLeftPadded(prefix, fieldAccess.getPadding().getName(), JLeftPadded.Location.FIELD_ACCESS_NAME, p);
            afterSyntax(fieldAccess, p);
            return fieldAccess;
        }

        @Override
        public J visitForEachLoop(J.ForEachLoop forEachLoop, PrintOutputCapture<P> p) {
            beforeSyntax(forEachLoop, Space.Location.FOR_EACH_LOOP_PREFIX, p);
            p.append("for");
            J.ForEachLoop.Control ctrl = forEachLoop.getControl();
            visitSpace(ctrl.getPrefix(), Space.Location.FOR_EACH_CONTROL_PREFIX, p);
            p.append('(');
            visitRightPadded(ctrl.getPadding().getVariable(), JRightPadded.Location.FOREACH_VARIABLE, "in", p);
            visitRightPadded(ctrl.getPadding().getIterable(), JRightPadded.Location.FOREACH_ITERABLE, "", p);
            p.append(')');
            visitStatement(forEachLoop.getPadding().getBody(), JRightPadded.Location.FOR_BODY, p);
            afterSyntax(forEachLoop, p);
            return forEachLoop;
        }

        @Override
        public J visitIdentifier(J.Identifier ident, PrintOutputCapture<P> p) {
            if (ident.getMarkers().findFirst(Implicit.class).isPresent()) {
                return ident;
            }

            visit(ident.getAnnotations(), p);
            beforeSyntax(ident, Space.Location.IDENTIFIER_PREFIX, p);
            visitSpace(Space.EMPTY, Space.Location.ANNOTATIONS, p);
            p.append(ident.getSimpleName());
            afterSyntax(ident, p);
            return ident;
        }

        @Override
        public J visitImport(J.Import import_, PrintOutputCapture<P> p) {
            beforeSyntax(import_, Space.Location.IMPORT_PREFIX, p);
            p.append("import");
            if (import_.getQualid().getTarget() instanceof J.Empty) {
                visit(import_.getQualid().getName(), p);
            } else {
                visit(import_.getQualid(), p);
            }

            JLeftPadded<J.Identifier> alias = import_.getPadding().getAlias();
            if (alias != null) {
                visitSpace(alias.getBefore(), Space.Location.IMPORT_ALIAS_PREFIX, p);
                p.append("as");
                visit(alias.getElement(), p);
            }
            afterSyntax(import_, p);
            return import_;
        }

        @Override
        public J visitInstanceOf(J.InstanceOf instanceOf, PrintOutputCapture<P> p) {
            beforeSyntax(instanceOf, Space.Location.INSTANCEOF_PREFIX, p);
            String suffix = instanceOf.getMarkers().findFirst(NotIs.class).isPresent() ? "!is" : "is";
            visitRightPadded(instanceOf.getPadding().getExpr(), JRightPadded.Location.INSTANCEOF, suffix, p);
            visit(instanceOf.getClazz(), p);
            visit(instanceOf.getPattern(), p);
            afterSyntax(instanceOf, p);
            return instanceOf;
        }

        @Override
        public J visitLabel(J.Label label, PrintOutputCapture<P> p) {
            beforeSyntax(label, Space.Location.LABEL_PREFIX, p);
            visitRightPadded(label.getPadding().getLabel(), JRightPadded.Location.LABEL, "@", p);
            visit(label.getStatement(), p);
            afterSyntax(label, p);
            return label;
        }

        @Override
        public J visitLambda(J.Lambda lambda, PrintOutputCapture<P> p) {
            beforeSyntax(lambda, Space.Location.LAMBDA_PREFIX, p);

            if (lambda.getMarkers().findFirst(AnonymousFunction.class).isPresent()) {
                p.append("fun");
                visitLambdaParameters(lambda.getParameters(), p);
                visitBlock((J.Block) lambda.getBody(), p);
            } else {
                boolean omitBraces = lambda.getMarkers().findFirst(OmitBraces.class).isPresent();
                if (!omitBraces) {
                    p.append('{');
                }

                visitLambdaParameters(lambda.getParameters(), p);
                if (!lambda.getParameters().getParameters().isEmpty()) {
                    visitSpace(lambda.getArrow(), Space.Location.LAMBDA_ARROW_PREFIX, p);
                    p.append("->");
                }
                visit(lambda.getBody(), p);
                if (!omitBraces) {
                    p.append('}');
                }
            }

            afterSyntax(lambda, p);
            return lambda;
        }

        private void visitLambdaParameters(J.Lambda.Parameters parameters, PrintOutputCapture<P> p) {
            visitMarkers(parameters.getMarkers(), p);
            if (parameters.isParenthesized()) {
                visitSpace(parameters.getPrefix(), Space.Location.LAMBDA_PARAMETERS_PREFIX, p);
                p.append('(');
                visitRightPadded(parameters.getPadding().getParams(), JRightPadded.Location.LAMBDA_PARAM, ",", p);
                p.append(')');
            } else {
                List<JRightPadded<J>> params = parameters.getPadding().getParams();
                for (int i = 0; i < params.size(); i++) {
                    JRightPadded<J> param = params.get(i);
                    if (param.getElement() instanceof J.Lambda.Parameters) {
                        visitLambdaParameters((J.Lambda.Parameters) param.getElement(), p);
                        visitSpace(param.getAfter(), JRightPadded.Location.LAMBDA_PARAM.getAfterLocation(), p);
                    } else {
                        visit(param.getElement(), p);
                        visitSpace(param.getAfter(), JRightPadded.Location.LAMBDA_PARAM.getAfterLocation(), p);
                        visitMarkers(param.getMarkers(), p);
                        if (i < params.size() - 1) {
                            p.append(',');
                        }
                    }
                }
            }
        }

        @Override
        public J visitMethodDeclaration(J.MethodDeclaration method, PrintOutputCapture<P> p) {
            // Do not print generated methods.
            for (Marker marker : method.getMarkers().getMarkers()) {
                if (marker instanceof Implicit || marker instanceof PrimaryConstructor) {
                    return method;
                }
            }

            beforeSyntax(method, Space.Location.METHOD_DECLARATION_PREFIX, p);
            visit(method.getLeadingAnnotations(), p);
            for (J.Modifier m : method.getModifiers()) {
                visitModifier(m, p);
            }

            J.TypeParameters typeParameters = method.getAnnotations().getTypeParameters();
            if (typeParameters != null) {
                visit(typeParameters.getAnnotations(), p);
                visitSpace(typeParameters.getPrefix(), Space.Location.TYPE_PARAMETERS, p);
                visitMarkers(typeParameters.getMarkers(), p);
                p.append("<");
                visitRightPadded(typeParameters.getPadding().getTypeParameters(), JRightPadded.Location.TYPE_PARAMETER, ",", p);
                p.append(">");
            }

            boolean hasReceiverType = method.getMarkers().findFirst(Extension.class).isPresent();
            if (hasReceiverType) {
                J.VariableDeclarations infixReceiver = (J.VariableDeclarations) method.getParameters().get(0);
                JRightPadded<J.VariableDeclarations.NamedVariable> receiver = infixReceiver.getPadding().getVariables().get(0);
                visitRightPadded(receiver, JRightPadded.Location.NAMED_VARIABLE, ".", p);
            }

            if (!method.getName().getMarkers().findFirst(Implicit.class).isPresent()) {
                visit(method.getAnnotations().getName().getAnnotations(), p);
                visit(method.getName(), p);
            }

            JContainer<Statement> params = method.getPadding().getParameters();
            beforeSyntax(params.getBefore(), params.getMarkers(), JContainer.Location.METHOD_DECLARATION_PARAMETERS.getBeforeLocation(), p);
            p.append("(");
            int i = hasReceiverType ? 1 : 0;
            List<JRightPadded<Statement>> elements = params.getPadding().getElements();
            for (; i < elements.size(); i++) {
                printMethodParameters(p, i, elements);
            }
            afterSyntax(params.getMarkers(), p);
            p.append(")");

            if (method.getReturnTypeExpression() != null) {
                method.getMarkers().findFirst(TypeReferencePrefix.class).ifPresent(typeReferencePrefix ->
                        kotlinPrinter.visitSpace(typeReferencePrefix.getPrefix(), KSpace.Location.TYPE_REFERENCE_PREFIX, p));
                p.append(":");
                visit(method.getReturnTypeExpression(), p);
            } else if (method.getBody() != null && !method.getBody().getStatements().isEmpty()) {
                Statement firstStatement = method.getBody().getStatements().get(0);
                firstStatement.getMarkers().findFirst(ConstructorDelegation.class).ifPresent(delegation -> {
                    kotlinPrinter.visitSpace(delegation.getPrefix(), KSpace.Location.CONSTRUCTOR_DELEGATION_PREFIX, p);
                    p.append(":");
                    visit(firstStatement, p);
                });
            }

            visit(method.getBody(), p);
            afterSyntax(method, p);
            return method;
        }

        private void printMethodParameters(PrintOutputCapture<P> p, int i, List<JRightPadded<Statement>> elements) {
            JRightPadded<Statement> element = elements.get(i);
            if (element.getElement().getMarkers().findFirst(Implicit.class).isPresent()) {
                return;
            }

            // inlined modified logic `JavaPrinter#visitRightPadded(JRightPadded, JRightPadded.Location, String, PrintOutputCapture)`
            // as that method would end up printing markers before the element and there is currently no way to differentiate
            // before and after markers
            String suffix = i == elements.size() - 1 ? "" : ",";
            visit(((JRightPadded<? extends J>) element).getElement(), p);
            visitSpace(element.getAfter(), JContainer.Location.METHOD_DECLARATION_PARAMETERS.getElementLocation().getAfterLocation(), p);
            visitMarkers(element.getMarkers(), p);
            p.append(suffix);
        }

        @Override
        public J visitMethodInvocation(J.MethodInvocation method, PrintOutputCapture<P> p) {
            beforeSyntax(method, Space.Location.METHOD_INVOCATION_PREFIX, p);

            visitRightPadded(method.getPadding().getSelect(), JRightPadded.Location.METHOD_SELECT, p);
            if (method.getSelect() != null && !method.getMarkers().findFirst(Extension.class).isPresent()) {
                if (method.getMarkers().findFirst(IsNullSafe.class).isPresent()) {
                    p.append("?");
                }
                p.append(".");
            }

            visit(method.getName(), p);
            visitContainer("<", method.getPadding().getTypeParameters(), JContainer.Location.TYPE_PARAMETERS, ",", ">", p);

            visitArgumentsContainer(method.getPadding().getArguments(), Space.Location.METHOD_INVOCATION_ARGUMENTS, p);

            afterSyntax(method, p);
            return method;
        }

        private void visitArgumentsContainer(JContainer<Expression> argContainer, Space.Location argsLocation, PrintOutputCapture<P> p) {
            visitSpace(argContainer.getBefore(), argsLocation, p);
            List<JRightPadded<Expression>> args = argContainer.getPadding().getElements();
            boolean omitParensOnMethod = argContainer.getMarkers().findFirst(OmitParentheses.class).isPresent();

            int argCount = args.size();
            boolean isTrailingLambda = !args.isEmpty() && args.get(argCount - 1).getElement().getMarkers().findFirst(TrailingLambdaArgument.class).isPresent();

            if (!omitParensOnMethod) {
                p.append('(');
            }

            for (int i = 0; i < argCount; i++) {
                JRightPadded<Expression> arg = args.get(i);

                // Print trailing lambda.
                if (i == argCount - 1 && isTrailingLambda) {
                    visitSpace(arg.getAfter(), JRightPadded.Location.METHOD_INVOCATION_ARGUMENT.getAfterLocation(), p);
                    if (!omitParensOnMethod) {
                        p.append(")");
                    }
                    visit(arg.getElement(), p);
                    break;
                }

                if (i > 0 && omitParensOnMethod && (
                        !args.get(0).getElement().getMarkers().findFirst(OmitParentheses.class).isPresent() &&
                        !args.get(0).getElement().getMarkers().findFirst(OmitParentheses.class).isPresent())) {
                    p.append(')');
                } else if (i > 0) {
                    p.append(',');
                }

                SpreadArgument spread = arg.getElement().getMarkers().findFirst(SpreadArgument.class).orElse(null);
                if (spread != null) {
                    kotlinPrinter.visitSpace(spread.getPrefix(), KSpace.Location.SPREAD_ARGUMENT_PREFIX, p);
                    p.append("*");
                }
                visitRightPadded(arg, JRightPadded.Location.METHOD_INVOCATION_ARGUMENT, p);
            }

            if (!omitParensOnMethod && !isTrailingLambda) {
                p.append(')');
            }
        }

        @Override
        public J visitNewClass(J.NewClass newClass, PrintOutputCapture<P> p) {
            KObject kObject = newClass.getMarkers().findFirst(KObject.class).orElse(null);
            if (kObject != null) {
                kotlinPrinter.visitSpace(kObject.getPrefix(), KSpace.Location.OBJECT_PREFIX, p);
                p.append("object");
            }

            beforeSyntax(newClass, Space.Location.NEW_CLASS_PREFIX, p);

            if (kObject != null && newClass.getClazz() != null) {
                p.append(":");
            }

            visitRightPadded(newClass.getPadding().getEnclosing(), JRightPadded.Location.NEW_CLASS_ENCLOSING, ".", p);
            visitSpace(newClass.getNew(), Space.Location.NEW_PREFIX, p);
            visit(newClass.getClazz(), p);

            visitArgumentsContainer(newClass.getPadding().getArguments(), Space.Location.NEW_CLASS_ARGUMENTS, p);

            visit(newClass.getBody(), p);
            afterSyntax(newClass, p);
            return newClass;
        }

        @Override
        public J visitReturn(J.Return return_, PrintOutputCapture<P> p) {
            if (return_.getMarkers().findFirst(ImplicitReturn.class).isPresent()) {
                visitSpace(return_.getPrefix(), Space.Location.RETURN_PREFIX, p);
                visitMarkers(return_.getMarkers(), p);
                visit(return_.getExpression(), p);
                afterSyntax(return_, p);
                return return_;
            }
            return super.visitReturn(return_, p);
        }

        @Override
        public J visitTernary(J.Ternary ternary, PrintOutputCapture<P> p) {
            beforeSyntax(ternary, Space.Location.TERNARY_PREFIX, p);
            visitLeftPadded("", ternary.getPadding().getTruePart(), JLeftPadded.Location.TERNARY_TRUE, p);
            visitLeftPadded("?:", ternary.getPadding().getFalsePart(), JLeftPadded.Location.TERNARY_FALSE, p);
            afterSyntax(ternary, p);
            return ternary;
        }

        @Override
        public J visitTypeCast(J.TypeCast typeCast, PrintOutputCapture<P> p) {
            beforeSyntax(typeCast, Space.Location.TYPE_CAST_PREFIX, p);
            visit(typeCast.getExpression(), p);

            J.ControlParentheses<TypeTree> controlParens = typeCast.getClazz();
            beforeSyntax(controlParens, Space.Location.CONTROL_PARENTHESES_PREFIX, p);

            String as = typeCast.getMarkers().findFirst(IsNullSafe.class).isPresent() ? "as?" : "as";
            p.append(as);

            visit(controlParens.getTree(), p);
            afterSyntax(typeCast, p);
            return typeCast;
        }

        @Override
        public J visitTypeParameter(J.TypeParameter typeParam, PrintOutputCapture<P> p) {
            beforeSyntax(typeParam, Space.Location.TYPE_PARAMETERS_PREFIX, p);
            visit(typeParam.getAnnotations(), p);
            visit(typeParam.getName(), p);
            Optional<GenericType> bounds = typeParam.getMarkers().findFirst(GenericType.class);
            String delimiter = "";
            if (bounds.isPresent()) {
                if (GenericType.Variance.COVARIANT == bounds.get().getVariance()) {
                    delimiter = "out";
                } else if (GenericType.Variance.CONTRAVARIANT == bounds.get().getVariance()) {
                    delimiter = "in";
                }
            } else if (typeParam.getBounds() != null && !typeParam.getBounds().isEmpty()) {
                delimiter = ":";
            }
            visitContainer(delimiter, typeParam.getPadding().getBounds(), JContainer.Location.TYPE_BOUNDS, "&", "", p);
            afterSyntax(typeParam, p);
            return typeParam;
        }

        @Override
        public J visitUnary(J.Unary unary, PrintOutputCapture<P> p) {
            if (unary.getOperator() == J.Unary.Type.Not && unary.getExpression() instanceof K.Binary && ((K.Binary) unary.getExpression()).getOperator() == K.Binary.Type.NotContains) {
                // This is a special case for the `!in` operator.
                // The `!` is a unary operator, but the `in` is a binary operator.
                // The `!` is printed as part of the binary operator.
                beforeSyntax(unary, Space.Location.UNARY_PREFIX, p);
                visit(unary.getExpression(), p);
                afterSyntax(unary, p);
                return unary;
            }
            return super.visitUnary(unary, p);
        }

        @Override
        public J visitWildcard(J.Wildcard wildcard, PrintOutputCapture<P> p) {
            beforeSyntax(wildcard, Space.Location.WILDCARD_PREFIX, p);
            if (wildcard.getPadding().getBound() != null) {
                p.append(wildcard.getPadding().getBound().getElement() == J.Wildcard.Bound.Super ? "in" : "out");
            }
            if (wildcard.getBoundedType() == null) {
                p.append('*');
            } else {
                visit(wildcard.getBoundedType(), p);
            }
            afterSyntax(wildcard, p);
            return wildcard;
        }

        @Override
        public J visitVariableDeclarations(J.VariableDeclarations multiVariable, PrintOutputCapture<P> p) {
            // TypeAliases are converted into a J.VariableDeclaration to re-use complex recipes like RenameVariable and ChangeType.
            // However, a type alias has different syntax and is printed separately to reduce code complexity in visitVariableDeclarations.
            // This is a temporary solution until K.TypeAlias is added to the model, and RenameVariable is revised to operator from a J.Identifier.
            if (multiVariable.getLeadingAnnotations().stream().anyMatch(it -> "typealias".equals(it.getSimpleName()))) {
                visitTypeAlias(multiVariable, p);
                return multiVariable;
            }

            beforeSyntax(multiVariable, Space.Location.VARIABLE_DECLARATIONS_PREFIX, p);

            visit(multiVariable.getLeadingAnnotations(), p);
            for (J.Modifier m : multiVariable.getModifiers()) {
                visitModifier(m, p);
                if (m.getType() == J.Modifier.Type.Final) {
                    p.append("val");
                }
            }

            boolean containsTypeReceiver = multiVariable.getMarkers().findFirst(Extension.class).isPresent();
            List<JRightPadded<J.VariableDeclarations.NamedVariable>> variables = multiVariable.getPadding().getVariables();
            // V1: Covers and unique case in `mapForLoop` of the KotlinParserVisitor caused by how the FirElement represents for loops.
            for (int i = 0; i < variables.size(); i++) {
                JRightPadded<J.VariableDeclarations.NamedVariable> variable = variables.get(i);
                beforeSyntax(variable.getElement(), Space.Location.VARIABLE_PREFIX, p);
                if (variables.size() > 1 && !containsTypeReceiver && i == 0) {
                    p.append("(");
                }

                visit(variable.getElement().getName(), p);

                if (multiVariable.getTypeExpression() != null) {
                    TypeReferencePrefix typeReferencePrefix = multiVariable.getMarkers().findFirst(TypeReferencePrefix.class).orElse(null);
                    if (typeReferencePrefix != null) {
                        kotlinPrinter.visitSpace(typeReferencePrefix.getPrefix(), KSpace.Location.TYPE_REFERENCE_PREFIX, p);
                        p.append(":");
                    }
                    visit(multiVariable.getTypeExpression(), p);
                }

                if (variable.getElement().getPadding().getInitializer() != null) {
                    visitSpace(variable.getElement().getPadding().getInitializer().getBefore(), Space.Location.VARIABLE_INITIALIZER, p);
                }

                if (variable.getElement().getInitializer() != null) {
                    String equals = getEqualsText(multiVariable);
                    p.append(equals);
                }

                visit(variable.getElement().getInitializer(), p);
                visitSpace(variable.getAfter(), Space.Location.VARIABLE_INITIALIZER, p);

                if (i < variables.size() - 1) {
                    p.append(",");
                } else if (variables.size() > 1 && !containsTypeReceiver) {
                    p.append(")");
                }

                variable.getMarkers().findFirst(Semicolon.class).ifPresent(m -> visitMarker(m, p));
            }

            afterSyntax(multiVariable, p);
            return multiVariable;
        }

        @Override
        public J visitVariable(J.VariableDeclarations.NamedVariable variable, PrintOutputCapture<P> p) {
            beforeSyntax(variable, Space.Location.VARIABLE_PREFIX, p);
            boolean isTypeReceiver = variable.getMarkers().findFirst(Extension.class).isPresent();
            if (!isTypeReceiver) {
                visit(variable.getName(), p);
            }
            visitLeftPadded(isTypeReceiver ? "" : "=", variable.getPadding().getInitializer(), JLeftPadded.Location.VARIABLE_INITIALIZER, p);
            afterSyntax(variable, p);
            return variable;
        }

        private void visitTypeAlias(J.VariableDeclarations typeAlias, PrintOutputCapture<P> p) {
            beforeSyntax(typeAlias, Space.Location.VARIABLE_DECLARATIONS_PREFIX, p);
            visitSpace(Space.EMPTY, Space.Location.ANNOTATIONS, p);
            visit(typeAlias.getLeadingAnnotations(), p);
            for (J.Modifier m : typeAlias.getModifiers()) {
                visitModifier(m, p);
            }

            visit(typeAlias.getTypeExpression(), p);
            visitVariable(typeAlias.getPadding().getVariables().get(0).getElement(), p);
            visitMarkers(typeAlias.getPadding().getVariables().get(0).getMarkers(), p);
            afterSyntax(typeAlias, p);
        }

        protected void visitStatement(@Nullable JRightPadded<Statement> paddedStat, JRightPadded.Location location, PrintOutputCapture<P> p) {
            if (paddedStat != null) {
                Statement element = paddedStat.getElement();
                if (element.getMarkers().findFirst(Implicit.class).isPresent()) {
                    return;
                }
                visit(element, p);
                visitSpace(paddedStat.getAfter(), location.getAfterLocation(), p);
                visitMarkers(paddedStat.getMarkers(), p);
            }
        }

        @Override
        public <M extends Marker> M visitMarker(Marker marker, PrintOutputCapture<P> p) {
            if (marker instanceof Semicolon) {
                p.append(';');
            } else if (marker instanceof Reified) {
                p.append("reified");
            } else if (marker instanceof TrailingComma) {
                // TODO consider adding cursor message to only print for last element in list
                // TODO the space should then probably be printed anyway (could contain a comment)
                p.append(',');
                visitSpace(((TrailingComma) marker).getSuffix(), Space.Location.LANGUAGE_EXTENSION, p);
            }

            return super.visitMarker(marker, p);
        }


        /**
         * Does not print the final modifier, as it is not supported in Kotlin.
         */
        @Override
        protected void visitModifier(J.Modifier mod, PrintOutputCapture<P> p) {
            visit(mod.getAnnotations(), p);
            String keyword = "";
            switch (mod.getType()) {
                case Default:
                    keyword = "default";
                    break;
                case Public:
                    keyword = "public";
                    break;
                case Protected:
                    keyword = "protected";
                    break;
                case Private:
                    keyword = "private";
                    break;
                case Abstract:
                    keyword = "abstract";
                    break;
                case Static:
                    keyword = "static";
                    break;
                case Native:
                    keyword = "native";
                    break;
                case NonSealed:
                    keyword = "non-sealed";
                    break;
                case Sealed:
                    keyword = "sealed";
                    break;
                case Strictfp:
                    keyword = "strictfp";
                    break;
                case Synchronized:
                    keyword = "synchronized";
                    break;
                case Transient:
                    keyword = "transient";
                    break;
                case Volatile:
                    keyword = "volatile";
                    break;
                case LanguageExtension:
                    keyword = mod.getKeyword();
                    break;
            }
            beforeSyntax(mod, Space.Location.MODIFIER_PREFIX, p);
            p.append(keyword);
            afterSyntax(mod, p);
        }

        @Override
        protected void afterSyntax(J j, PrintOutputCapture<P> p) {
            kotlinPrinter.trailingMarkers(j.getMarkers(), p);
            super.afterSyntax(j, p);
        }
    }

    private void trailingMarkers(Markers markers, PrintOutputCapture<P> p) {
        for (Marker marker : markers.getMarkers()) {
            if (marker instanceof CheckNotNull) {
                KotlinPrinter.this.visitSpace(((CheckNotNull) marker).getPrefix(), KSpace.Location.CHECK_NOT_NULL_PREFIX, p);
                p.append("!!");
            } else if (marker instanceof IsNullable) {
                KotlinPrinter.this.visitSpace(((IsNullable) marker).getPrefix(), KSpace.Location.TYPE_REFERENCE_PREFIX, p);
                p.append("?");
            }
        }
    }

    @Override
    public Space visitSpace(Space space, KSpace.Location loc, PrintOutputCapture<P> p) {
        return delegate.visitSpace(space, Space.Location.LANGUAGE_EXTENSION, p);
    }

    @Override
    public Space visitSpace(Space space, Space.Location loc, PrintOutputCapture<P> p) {
        return delegate.visitSpace(space, loc, p);
    }

    protected void visitContainer(String before, @Nullable JContainer<? extends J> container, KContainer.Location location,
                                  @Nullable String after, PrintOutputCapture<P> p) {
        if (container == null) {
            return;
        }
        visitSpace(container.getBefore(), location.getBeforeLocation(), p);
        p.append(before);
        visitRightPadded(container.getPadding().getElements(), location.getElementLocation(), p);
        p.append(after == null ? "" : after);
    }

    protected void visitRightPadded(List<? extends JRightPadded<? extends J>> nodes, KRightPadded.Location location, PrintOutputCapture<P> p) {
        for (int i = 0; i < nodes.size(); i++) {
            JRightPadded<? extends J> node = nodes.get(i);
            visit(node.getElement(), p);
            visitSpace(node.getAfter(), location.getAfterLocation(), p);
            visitMarkers(node.getMarkers(), p);
            if (i < nodes.size() - 1) {
                p.append(",");
            }
        }
    }

    @Override
    public <M extends Marker> M visitMarker(Marker marker, PrintOutputCapture<P> p) {
        return delegate.visitMarker(marker, p);
    }

    private static final UnaryOperator<String> JAVA_MARKER_WRAPPER =
            out -> "/*~~" + out + (out.isEmpty() ? "" : "~~") + ">*/";

    private void beforeSyntax(K k, KSpace.Location loc, PrintOutputCapture<P> p) {
        beforeSyntax(k.getPrefix(), k.getMarkers(), loc, p);
    }

    @SuppressWarnings("SameParameterValue")
    private void beforeSyntax(J j, Space.Location loc, PrintOutputCapture<P> p) {
        beforeSyntax(j.getPrefix(), j.getMarkers(), loc, p);
    }

    private void beforeSyntax(Space prefix, Markers markers, @Nullable KSpace.Location loc, PrintOutputCapture<P> p) {
        for (Marker marker : markers.getMarkers()) {
            p.append(p.getMarkerPrinter().beforePrefix(marker, new Cursor(getCursor(), marker), JAVA_MARKER_WRAPPER));
        }
        if (loc != null) {
            visitSpace(prefix, loc, p);
        }
        KotlinPrinter.this.visitMarkers(markers, p);
        for (Marker marker : markers.getMarkers()) {
            p.append(p.getMarkerPrinter().beforeSyntax(marker, new Cursor(getCursor(), marker), JAVA_MARKER_WRAPPER));
        }
    }

    private void beforeSyntax(K k, Space.Location loc, PrintOutputCapture<P> p) {
        beforeSyntax(k.getPrefix(), k.getMarkers(), loc, p);
    }

    private void beforeSyntax(Space prefix, Markers markers, @Nullable Space.Location loc, PrintOutputCapture<P> p) {
        for (Marker marker : markers.getMarkers()) {
            p.append(p.getMarkerPrinter().beforePrefix(marker, new Cursor(getCursor(), marker), JAVA_MARKER_WRAPPER));
        }
        if (loc != null) {
            delegate.visitSpace(prefix, loc, p);
        }
        KotlinPrinter.this.visitMarkers(markers, p);
        for (Marker marker : markers.getMarkers()) {
            p.append(p.getMarkerPrinter().beforeSyntax(marker, new Cursor(getCursor(), marker), JAVA_MARKER_WRAPPER));
        }
    }

    private void afterSyntax(J j, PrintOutputCapture<P> p) {
        trailingMarkers(j.getMarkers(), p);
        afterSyntax(j.getMarkers(), p);
    }

    private void afterSyntax(Markers markers, PrintOutputCapture<P> p) {
        for (Marker marker : markers.getMarkers()) {
            p.append(p.getMarkerPrinter().afterSyntax(marker, new Cursor(getCursor(), marker), JAVA_MARKER_WRAPPER));
        }
    }

    @NotNull
    private static String getEqualsText(J.VariableDeclarations vd) {
        String equals = "=";
        for (Marker marker : vd.getMarkers().getMarkers()) {
            if (marker instanceof By) {
                equals = "by";
                break;
            } else if (marker instanceof OmitEquals) {
                equals = "";
                break;
            }
        }
        return equals;
    }
}
