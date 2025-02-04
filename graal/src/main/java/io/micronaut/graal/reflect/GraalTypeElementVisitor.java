/*
 * Copyright 2017-2019 original authors
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

package io.micronaut.graal.reflect;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import io.micronaut.core.annotation.*;
import io.micronaut.core.util.CollectionUtils;
import io.micronaut.core.util.StringUtils;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.ConstructorElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.ParameterElement;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.inject.writer.GeneratedFile;
import org.reactivestreams.Publisher;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.util.*;
import java.util.concurrent.Future;

/**
 * Generates the GraalVM reflect.json file at compilation time.
 *
 * @author graemerocher
 * @since 1.1
 */
@Experimental
public class GraalTypeElementVisitor implements TypeElementVisitor<Object, Object> {

    /**
     * Beans are those requiring full reflective access to all public members.
     */
    protected static Set<String> packages = new HashSet<>();
    /**
     * Beans are those requiring full reflective access to all public members.
     */
    protected static Set<String> beans = new HashSet<>();

    /**
     * Classes only require classloading access.
     */
    protected static Set<String> classes = new HashSet<>();

    /**
     * Arrays requiring reflective instantiation.
     */
    protected static Set<String> arrays = new HashSet<>();

    private static final String REFLECTION_CONFIG_JSON = "reflection-config.json";
    private static final String NATIVE_IMAGE_PROPERTIES = "native-image.properties";

    private static final String BASE_REFLECT_JSON = "src/main/graal/reflect.json";

    private static final String ALL_PUBLIC_METHODS = "allPublicMethods";

    private static final String NAME = "name";

    private static final String ALL_DECLARED_CONSTRUCTORS = "allDeclaredConstructors";

    private boolean isSubclass = getClass() != GraalTypeElementVisitor.class;

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        if (!isSubclass && !element.hasStereotype(Deprecated.class)) {
            if (element.hasAnnotation(Introspected.class)) {
                packages.add(element.getPackageName());
                beans.add(element.getName());
                final String[] introspectedClasses = element.getValue(Introspected.class, "classes", String[].class).orElse(StringUtils.EMPTY_STRING_ARRAY);
                Collections.addAll(beans, introspectedClasses);
            } else if (element.hasAnnotation(TypeHint.class)) {
                packages.add(element.getPackageName());
                final String[] introspectedClasses = element.getValue(TypeHint.class, String[].class).orElse(StringUtils.EMPTY_STRING_ARRAY);
                final TypeHint.AccessType accessType = element.getValue(TypeHint.class, "accessType", TypeHint.AccessType.class)
                        .orElse(TypeHint.AccessType.REFLECTION_PUBLIC);

                processClasses(accessType, introspectedClasses);
                processClasses(accessType, element.getValue(
                        TypeHint.class,
                        "typeNames",
                        String[].class).orElse(StringUtils.EMPTY_STRING_ARRAY
                    )
                );
            }
        }
    }

    @Override
    public void visitMethod(MethodElement element, VisitorContext context) {
        if (!isSubclass) {
            if (element.hasDeclaredStereotype(EntryPoint.class)) {
                final ClassElement returnType = element.getReturnType();
                possiblyReflectOnType(returnType);
                final ParameterElement[] parameters = element.getParameters();
                for (ParameterElement parameter : parameters) {
                    possiblyReflectOnType(parameter.getType());
                }
            }
        }
    }

    @Override
    public void visitConstructor(ConstructorElement element, VisitorContext context) {
        if (!isSubclass) {
            if (element.hasAnnotation(Creator.class)) {
                final ClassElement declaringType = element.getDeclaringType();
                packages.add(declaringType.getPackageName());
                beans.add(declaringType.getName());
            }
        }
    }

    private void possiblyReflectOnType(ClassElement type) {
        if (type == null || type.isPrimitive() || type.isAbstract() || type.isEnum() || type.getName().startsWith("java.lang")) {
            return;
        }

        boolean isWrapperType = type.isAssignable(Iterable.class) ||
                                type.isAssignable(Publisher.class) ||
                                type.isAssignable(Map.class) ||
                                type.isAssignable(Optional.class) ||
                                type.isAssignable(Future.class);
        if (!isWrapperType) {
            beans.add(type.getName());
        }
        
        final Map<String, ClassElement> typeArguments = type.getTypeArguments();
        for (ClassElement value : typeArguments.values()) {
            possiblyReflectOnType(value);
        }
    }

    private void processClasses(TypeHint.AccessType accessType, String... introspectedClasses) {
        if (accessType == TypeHint.AccessType.CLASS_LOADING) {
            Collections.addAll(classes, introspectedClasses);
        } else {
            Collections.addAll(beans, introspectedClasses);
        }
    }

    @Override
    public final void finish(VisitorContext visitorContext) {
        // don't do this for subclasses
        if (!isSubclass) {
            List<Map> json;
            ObjectMapper mapper = new ObjectMapper();
            ObjectWriter writer = mapper.writer(new DefaultPrettyPrinter());

            File f = new File(BASE_REFLECT_JSON);
            if (f.exists()) {
                try {
                    json = mapper.readValue(f, new TypeReference<List<Map>>() {
                    });
                } catch (Throwable e) {
                    visitorContext.fail("Error parsing base reflect.json: " + BASE_REFLECT_JSON, null);
                    return;
                }
            } else {
                json = new ArrayList<>();
            }

            if (CollectionUtils.isEmpty(beans) && CollectionUtils.isEmpty(classes) && CollectionUtils.isEmpty(arrays)) {
                return;
            }

            try {

                String basePackage = packages.stream()
                        .distinct()
                        .min(Comparator.comparingInt(String::length)).orElse("io.micronaut");

                String module;
                if (basePackage.startsWith("io.micronaut.")) {
                    module = basePackage.substring("io.micronaut.".length()).replace('.', '-');
                    basePackage = "io.micronaut";
                } else {
                    if (basePackage.contains(".")) {
                        final int i = basePackage.lastIndexOf('.');
                        module = basePackage.substring(i + 1);
                        basePackage = basePackage.substring(0, i);
                    } else {
                        module = basePackage;
                    }
                }

                String path = "native-image/" + basePackage + "/" + module + "/";
                String reflectFile = path + REFLECTION_CONFIG_JSON;
                String propsFile = path + NATIVE_IMAGE_PROPERTIES;

                visitorContext.visitMetaInfFile(propsFile).ifPresent(gf -> {
                    visitorContext.info("Writing " + NATIVE_IMAGE_PROPERTIES + " file to destination: " + gf.getName());
                    try (PrintWriter w = new PrintWriter (gf.openWriter())) {
                        w.println("Args = -H:ReflectionConfigurationResources=${.}/reflection-config.json");
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                });
                final Optional<GeneratedFile> generatedFile = visitorContext.visitMetaInfFile(reflectFile);
                generatedFile.ifPresent(gf -> {

                    for (String aClass : classes) {
                        json.add(CollectionUtils.mapOf(
                                NAME, aClass,
                                ALL_DECLARED_CONSTRUCTORS, true
                        ));
                    }

                    for (String array : arrays) {
                        json.add(CollectionUtils.mapOf(
                                NAME, "[L" + array.substring(0, array.length() - 2) + ";",
                                ALL_DECLARED_CONSTRUCTORS, true
                        ));
                    }

                    for (String bean : beans) {
                        json.add(CollectionUtils.mapOf(
                                NAME, bean,
                                ALL_PUBLIC_METHODS, true,
                                ALL_DECLARED_CONSTRUCTORS, true
                        ));
                    }

                    try (Writer w = gf.openWriter()) {
                        visitorContext.info("Writing " + REFLECTION_CONFIG_JSON + " file to destination: " + gf.getName());

                        writer.writeValue(w, json);
                    } catch (IOException e) {
                        visitorContext.fail("Error writing " + REFLECTION_CONFIG_JSON + ": " + e.getMessage(), null);
                    }
                });
            } finally {
                beans.clear();
                classes.clear();
                arrays.clear();
            }
        }
    }
}
