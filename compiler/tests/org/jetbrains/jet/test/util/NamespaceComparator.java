/*
 * Copyright 2010-2012 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.jet.test.util;

import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jet.jvm.compiler.ExpectedLoadErrorsUtil;
import org.jetbrains.jet.lang.descriptors.*;
import org.jetbrains.jet.lang.resolve.DescriptorUtils;
import org.jetbrains.jet.lang.resolve.MemberComparator;
import org.jetbrains.jet.lang.resolve.name.FqName;
import org.jetbrains.jet.lang.resolve.name.FqNameUnsafe;
import org.jetbrains.jet.lang.resolve.scopes.JetScope;
import org.jetbrains.jet.renderer.DescriptorRenderer;
import org.jetbrains.jet.renderer.DescriptorRendererBuilder;
import org.jetbrains.jet.utils.Printer;
import org.junit.Assert;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * @author Stepan Koltsov
 */
public class NamespaceComparator {
    public static final Configuration DONT_INCLUDE_METHODS_OF_OBJECT = new Configuration(false, false, Predicates.<FqNameUnsafe>alwaysTrue());
    public static final Configuration RECURSIVE = new Configuration(false, true, Predicates.<FqNameUnsafe>alwaysTrue());

    private static final DescriptorRenderer RENDERER = new DescriptorRendererBuilder()
            .setWithDefinedIn(false)
            .setExcludedAnnotationClasses(Arrays.asList(new FqName(ExpectedLoadErrorsUtil.ANNOTATION_CLASS_NAME)))
            .setVerbose(true).build();

    private static final ImmutableSet<String> JAVA_OBJECT_METHOD_NAMES = ImmutableSet.of(
            "equals", "hashCode", "finalize", "wait", "notify", "notifyAll", "toString", "clone", "getClass");

    private final Configuration conf;

    private NamespaceComparator(@NotNull Configuration conf) {
        this.conf = conf;
    }

    private String serializeRecursively(@NotNull DeclarationDescriptor declarationDescriptor) {
        StringBuilder result = new StringBuilder();
        appendDeclarationRecursively(declarationDescriptor, new Printer(result, 1), true);
        return result.toString();
    }

    private void appendDeclarationRecursively(@NotNull DeclarationDescriptor descriptor, @NotNull Printer printer, boolean topLevel) {
        if (descriptor instanceof ClassOrNamespaceDescriptor && !topLevel) {
            printer.println();
        }

        boolean isPrimaryConstructor = descriptor instanceof ConstructorDescriptor && ((ConstructorDescriptor) descriptor).isPrimary();
        printer.print(isPrimaryConstructor && conf.checkPrimaryConstructors ? "/*primary*/ " : "", RENDERER.render(descriptor));

        if (descriptor instanceof ClassOrNamespaceDescriptor) {
            if (topLevel) {
                printer.println();
                printer.println();
            }
            else {
                printer.printlnWithNoIndent(" {").pushIndent();
            }

            List<DeclarationDescriptor> subDescriptors = Lists.newArrayList();

            if (descriptor instanceof ClassDescriptor) {
                ClassDescriptor klass = (ClassDescriptor) descriptor;
                JetScope memberScope = klass.getDefaultType().getMemberScope();

                subDescriptors.addAll(klass.getConstructors());
                subDescriptors.addAll(memberScope.getAllDescriptors());
                subDescriptors.addAll(memberScope.getObjectDescriptors());
                ContainerUtil.addIfNotNull(subDescriptors, klass.getClassObjectDescriptor());
            }
            else if (descriptor instanceof NamespaceDescriptor) {
                JetScope memberScope = ((NamespaceDescriptor) descriptor).getMemberScope();
                subDescriptors.addAll(memberScope.getAllDescriptors());
                subDescriptors.addAll(memberScope.getObjectDescriptors());
            }
            else {
                throw new IllegalStateException("Should be class or namespace: " + descriptor.getClass());
            }

            Collections.sort(subDescriptors, MemberComparator.INSTANCE);

            for (DeclarationDescriptor subDescriptor : subDescriptors) {
                if (!conf.includeMethodsOfJavaObject && subDescriptor instanceof FunctionDescriptor
                    && JAVA_OBJECT_METHOD_NAMES.contains(subDescriptor.getName().getName())) {
                    continue;
                }

                if (subDescriptor instanceof NamespaceDescriptor && !conf.recurseIntoPackage.apply(DescriptorUtils.getFQName(subDescriptor))) {
                    continue;
                }

                appendDeclarationRecursively(subDescriptor, printer, false);
            }

            if (!topLevel) {
                printer.popIndent().println("}");
            }
        }
        else {
            printer.printlnWithNoIndent();
        }
    }

    public static void compareNamespaces(
            @NotNull NamespaceDescriptor expectedNamespace,
            @NotNull NamespaceDescriptor actualNamespace,
            @NotNull Configuration configuration,
            @Nullable File txtFile
    ) {
        NamespaceComparator comparator = new NamespaceComparator(configuration);

        String expectedSerialized = comparator.serializeRecursively(expectedNamespace);
        String actualSerialized = comparator.serializeRecursively(actualNamespace);

        Assert.assertEquals("Expected and actual namespaces differ", expectedSerialized, actualSerialized);

        if (txtFile != null) {
            try {
                if (!txtFile.exists()) {
                    FileUtil.writeToFile(txtFile, actualSerialized);
                    Assert.fail("Expected data file did not exist. Generating: " + txtFile);
                }
                String expected = FileUtil.loadFile(txtFile, true);

                // compare with hardcopy: make sure nothing is lost in output
                Assert.assertEquals("Expected and actual namespaces differ from " + txtFile.getName(), expected, actualSerialized);
            }
            catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public static class Configuration {
        private final boolean checkPrimaryConstructors;
        private final boolean includeMethodsOfJavaObject;
        private final Predicate<FqNameUnsafe> recurseIntoPackage;

        public Configuration(
                boolean checkPrimaryConstructors,
                boolean includeMethodsOfJavaObject,
                Predicate<FqNameUnsafe> recurseIntoPackage
        ) {
            this.checkPrimaryConstructors = checkPrimaryConstructors;
            this.includeMethodsOfJavaObject = includeMethodsOfJavaObject;
            this.recurseIntoPackage = recurseIntoPackage;
        }

        public Configuration filterRecursion(@NotNull Predicate<FqNameUnsafe> recurseIntoPackage) {
            return new Configuration(checkPrimaryConstructors, includeMethodsOfJavaObject, recurseIntoPackage);
        }

        public Configuration checkPrimaryConstructors(boolean checkPrimaryConstructors) {
            return new Configuration(checkPrimaryConstructors, includeMethodsOfJavaObject, recurseIntoPackage);
        }
    }
}
