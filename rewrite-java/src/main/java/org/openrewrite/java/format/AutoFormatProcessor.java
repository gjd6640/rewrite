/*
 * Copyright 2020 the original author or authors.
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
package org.openrewrite.java.format;

import org.openrewrite.Cursor;
import org.openrewrite.Tree;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.java.JavaIsoProcessor;
import org.openrewrite.java.JavaProcessor;
import org.openrewrite.java.style.BlankLinesStyle;
import org.openrewrite.java.style.IntelliJ;
import org.openrewrite.java.style.SpacesStyle;
import org.openrewrite.java.style.TabsAndIndentsStyle;
import org.openrewrite.java.tree.J;

import java.util.Optional;

public class AutoFormatProcessor<P> extends JavaProcessor<P> {
    @Override
    public J visit(@Nullable Tree tree, P p, Cursor cursor) {
        J.CompilationUnit cu = cursor.firstEnclosing(J.CompilationUnit.class);
        assert cu != null;

        J t = new BlankLinesProcessor<>(Optional.ofNullable(cu.getStyle(BlankLinesStyle.class))
                .orElse(IntelliJ.blankLines()))
                .visit(tree, p, cursor);

        t = new SpacesProcessor<>(Optional.ofNullable(cu.getStyle(SpacesStyle.class))
                .orElse(IntelliJ.spaces()))
                .visit(t, p, cursor);

        t = new TabsAndIndentsProcessor<>(Optional.ofNullable(cu.getStyle(TabsAndIndentsStyle.class))
                .orElse(IntelliJ.tabsAndIndents()))
                .visit(t, p, cursor);

        return t;
    }

    @Override
    public J visitCompilationUnit(J.CompilationUnit cu, P p) {
        J t = new BlankLinesProcessor<>(Optional.ofNullable(cu.getStyle(BlankLinesStyle.class))
                .orElse(IntelliJ.blankLines()))
                .visit(cu, p);

        t = new SpacesProcessor<>(Optional.ofNullable(cu.getStyle(SpacesStyle.class))
                .orElse(IntelliJ.spaces()))
                .visit(t, p);

        t = new TabsAndIndentsProcessor<>(Optional.ofNullable(cu.getStyle(TabsAndIndentsStyle.class))
                .orElse(IntelliJ.tabsAndIndents()))
                .visit(t, p);

        return t;
    }
}
