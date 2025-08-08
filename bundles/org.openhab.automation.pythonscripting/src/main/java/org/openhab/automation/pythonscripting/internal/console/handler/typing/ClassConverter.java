/*
 * Copyright (c) 2010-2025 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.automation.pythonscripting.internal.console.handler.typing;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.automation.pythonscripting.internal.console.handler.typing.ClassCollector.ClassContainer;
import org.openhab.automation.pythonscripting.internal.console.handler.typing.ClassCollector.MethodContainer;
import org.openhab.automation.pythonscripting.internal.console.handler.typing.ClassCollector.ParameterContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Converts a Java class to a Python class stub
 *
 * @author Holger Hees - Initial contribution
 */
@NonNullByDefault
public class ClassConverter {
    private final Logger logger = LoggerFactory.getLogger(ClassConverter.class);

    private static Pattern CLASS_MATCHER = Pattern
            .compile("(?:(?:super|extends) )?([a-z0-9\\.\\$_]+|\\?)(?:<.*?>|\\[\\])?$", Pattern.CASE_INSENSITIVE);

    private static String BASE_URL;
    static {
        // Version version = FrameworkUtil.getBundle(OpenHAB.class).getVersion();
        String v = "latest"; // version.getQualifier() == null || version.getQualifier().isEmpty() ? version.toString()
                             // : "latest";
        BASE_URL = "https://www.openhab.org/javadoc/" + v + "/";
    }

    private final Map<String, String> generics;
    private final Map<String, String> imports;
    private final ClassContainer container;

    public ClassConverter(ClassContainer container) {
        this.container = container;
        this.generics = new HashMap<String, String>();
        this.imports = new HashMap<String, String>();
    }

    public List<String> getImports() {
        return new ArrayList<String>(this.imports.keySet());
    }

    public String build() throws IOException, ClassNotFoundException {
        // Class head
        StringBuilder classBody = new StringBuilder();
        classBody.append(buildClassHead());

        // Class documentation
        String doc = buildClassDocumentationBlock();
        if (doc != null) {
            classBody.append(doc);
            classBody.append("\n");
        }

        // Class fields
        classBody.append(buildClassFields());

        // Class methods
        List<MethodContainer> methods = container.getMethods();
        Collections.sort(methods, new Comparator<MethodContainer>() {
            @Override
            public int compare(MethodContainer o1, MethodContainer o2) {
                return o1.getPythonMethodName().compareTo(o2.getPythonMethodName());
            }
        });
        for (MethodContainer method : methods) {

            classBody.append(buildClassMethod(method));
        }

        // Class imports
        if (!imports.isEmpty()) {
            classBody.insert(0, "\n\n");
            classBody.insert(0, buildClassImports());
        }

        return classBody.toString();
    }

    private String buildClassHead() {
        StringBuilder builder = new StringBuilder();
        builder.append("class " + container.getPythonClassName());
        List<String> parentTypes = new ArrayList<String>();
        Class<?> parentClass = container.getRelatedClass().getSuperclass();
        if (parentClass != null) {
            String pythonType = convertBaseJavaToPythonType(parentClass.getName());
            if (!"object".equals(pythonType)) {
                parentTypes.add(pythonType);
            }
        }
        Class<?>[] parentInterfaces = container.getRelatedClass().getInterfaces();
        for (Class<?> parentInterface : parentInterfaces) {
            String pythonType = convertBaseJavaToPythonType(parentInterface.getName());
            parentTypes.add(pythonType);
        }
        if (parentTypes.isEmpty()) {
            if (container.getRelatedClass().isInterface()) {
                parentTypes.add("Protocol");
                imports.put("__typing.Protocol", "from typing import Protocol");
            }
        }
        if (!parentTypes.isEmpty()) {
            builder.append("(" + String.join(", ", parentTypes) + ")");
        }
        builder.append(":\n");
        return builder.toString();
    }

    private String buildClassFields() {
        StringBuilder builder = new StringBuilder();
        for (Field field : container.getFields()) {
            try {
                String type = convertJavatToPythonType(collectJavaTypes(field.getGenericType()));
                String value = "";
                Class<?> t = field.getType();

                if (Collection.class.isAssignableFrom(field.getType())) {
                    List<String> values = new ArrayList<String>();
                    if (field.get(null) instanceof Collection _values) {
                        for (Object _value : _values) {
                            values.add(convertFieldValue(_value));
                        }
                    }
                    value = "[" + String.join(",", values) + "]";
                } else if (t == short.class) {
                    value = convertFieldValue(field.getShort(null));
                } else if (t == int.class) {
                    value = convertFieldValue(field.getInt(null));
                } else if (t == long.class) {
                    value = convertFieldValue(field.getLong(null));
                } else if (t == float.class) {
                    value = convertFieldValue(field.getFloat(null));
                } else if (t == double.class) {
                    value = convertFieldValue(field.getDouble(null));
                } else if (t == boolean.class) {
                    value = convertFieldValue(field.getBoolean(null));
                } else if (t == char.class) {
                    value = convertFieldValue(field.getChar(null));
                } else {
                    value = convertFieldValue(field.get(null));
                }

                builder.append("    " + field.getName() + ": " + type + " = " + value + "\n");
            } catch (IllegalArgumentException | IllegalAccessException e) {
                logger.warn("Cant convert static field {} of class {}", container.getRelatedClass().getName(),
                        field.getName(), e);
            }
        }
        if (!builder.isEmpty()) {
            builder.append("\n");
        }

        return builder.toString();
    }

    private String buildClassMethod(MethodContainer method) {

        // Collect generics
        for (int i = 0; i < method.getReturnTypeCount(); i++) {
            collectGenerics(method.getGenericReturnType(i));
        }
        for (ParameterContainer p : method.getParameters()) {
            for (int i = 0; i < p.getTypeCount(); i++) {
                collectGenerics(p.getGenericType(i));
            }
        }

        // Collect arguments
        List<String> arguments = new ArrayList<String>();
        if (!Modifier.isStatic(method.getModifiers())) {
            arguments.add("self");
        }
        for (ParameterContainer p : method.getParameters()) {
            Set<String> parameterTypes = new HashSet<String>();
            for (int i = 0; i < p.getTypeCount(); i++) {
                String t = convertJavatToPythonType(collectJavaTypes(p.getGenericType(i)));
                parameterTypes.add(t);
            }
            List<String> sorted = parameterTypes.stream().sorted().collect(Collectors.toList());
            arguments.add(p.getName() + ": " + String.join(" | ", sorted) + (p.isOptional ? " = None" : ""));
        }

        // Build method
        StringBuilder builder = new StringBuilder();
        if (Modifier.isStatic(method.getModifiers())) {
            builder.append("    @staticmethod\n");
        }
        String methodName = method.isConstructor() ? "__init__" : method.getPythonMethodName();
        builder.append("    def " + methodName + "(" + String.join(", ", arguments) + ")");

        // Build return value
        if (method.getReturnTypeCount() > 0) {
            // Collect Return types
            Set<String> returnTypes = new HashSet<String>();
            for (int i = 0; i < method.getReturnTypeCount(); i++) {
                String t = convertJavatToPythonType(collectJavaTypes(method.getGenericReturnType(i)));
                returnTypes.add(t);
            }
            List<String> sortedReturnTypes = returnTypes.stream().sorted().collect(Collectors.toList());

            builder.append(" -> " + String.join(" | ", sortedReturnTypes));
        }

        // Finalize method
        builder.append(":\n");
        String doc = buildMethodDocumentationBlock(method);
        if (doc != null) {
            builder.append(doc);
        } else {
            builder.append("        pass\n");
        }
        builder.append("\n");

        return builder.toString();
    }

    private Object buildClassImports() {
        StringBuilder builder = new StringBuilder();
        HashSet<String> hashSet = new HashSet<>(imports.values());
        ArrayList<String> sortedImports = new ArrayList<>(hashSet);
        Collections.sort(sortedImports, new Comparator<String>() {
            @Override
            public int compare(String o1, String o2) {
                if (o1.length() > o2.length()) {
                    return 1;
                }
                if (o1.length() < o2.length()) {
                    return -1;
                }
                return o1.compareTo(o2);
            }
        });
        for (String importLine : sortedImports) {
            builder.append(importLine + "\n");
        }
        return builder.toString();
    }

    private List<String> collectJavaTypes(Type genericType) {
        List<String> types = new ArrayList<String>();
        if (genericType instanceof TypeVariable) {
            TypeVariable<?> _type = (TypeVariable<?>) genericType;
            String type = generics.get(_type.getTypeName());
            if (type != null) {
                types.add(type);
            }
        } else if (genericType instanceof ParameterizedType) {
            ParameterizedType _type = (ParameterizedType) genericType;
            // System.out.println("ParameterizedType | " + _type + " | " + _type.getRawType().getTypeName() + " | "
            // + _type.getActualTypeArguments()[0]);
            types.addAll(collectJavaTypes(_type.getRawType()));
            if (_type.getActualTypeArguments().length > 0) {
                types.addAll(collectJavaTypes(_type.getActualTypeArguments()[0]));
            }
        } else if (genericType instanceof WildcardType) {
            // TODO Not tested
            WildcardType _type = (WildcardType) genericType;
            // System.out.println("WildcardType | " + _type);
            if (_type.getUpperBounds().length > 0) {
                types.addAll(collectJavaTypes(_type.getUpperBounds()[0]));
            } else if (_type.getLowerBounds().length > 0) {
                types.addAll(collectJavaTypes(_type.getLowerBounds()[0]));
            } else {
                types.add("java.lang.Class");
            }
        } else if (genericType instanceof GenericArrayType) {
            // TODO Not tested
            GenericArrayType _type = (GenericArrayType) genericType;
            // System.out.println("GenericArrayType | " + _type);
            types.add("java.util.List");
            types.addAll(collectJavaTypes(_type.getGenericComponentType()));
        } else {
            // System.out.println("OtherType | " + genericType.getTypeName());
            String type = genericType.getTypeName();
            while (type.endsWith("[]")) {
                types.add("java.util.List");
                type = type.substring(0, type.length() - 2);
            }
            types.add(type);
        }

        /*
         * if (container.getRelatedClass().getSimpleName().equals("DateTimeItem")) {
         * System.out.println("---- " + genericType.getTypeName());
         * for (String _type : types) {
         * System.out.println(_type);
         * }
         * }
         */

        return types;
    }

    private String convertJavatToPythonType(List<String> types) {
        if (types.isEmpty()) {
            logger.warn("ConvertToPythonType for type '{}' is empty for class {}", types,
                    container.getRelatedClass().getName());
            return "any";
        }

        String javaType = types.remove(0);
        switch (javaType) {
            case "char":
                return "str";
            case "long":
            case "int":
            case "short":
                return "int";
            case "double":
            case "float":
                return "float";
            case "byte":
                return "bytes";
            case "boolean":
                return "bool";
            case "java.lang.Byte":
                return "bytes";
            case "java.lang.Double":
            case "java.lang.Float":
                return "float";
            case "java.lang.BigDecimal":
            case "java.lang.BigInteger":
            case "java.lang.Long":
            case "java.lang.Integer":
            case "java.lang.Short":
                return "int";
            case "java.lang.Number":
                return "float | int";
            case "java.util.List":
            case "java.util.Set":
                if (!types.isEmpty()) {
                    return "list[" + convertJavatToPythonType(types) + "]";
                }
                return "list";
            case "java.util.Map":
            case "java.util.HashMap":
            case "java.util.Hashtable":
                if (!types.isEmpty()) {
                    return "dict[" + convertJavatToPythonType(types) + "]";
                }
                return "dict";
            case "?":
            case "java.lang.Class":
                if (!types.isEmpty()) {
                    return convertJavatToPythonType(types);
                }
                return "any";
            case "void":
                return "None";
        }

        return convertBaseJavaToPythonType(javaType);
    }

    private String convertBaseJavaToPythonType(String javaType) {
        switch (javaType) {
            case "java.lang.String":
                return "str";
            case "java.lang.Object":
                return "object";
            case "java.time.ZonedDateTime":
                imports.put("__java.time.ZonedDateTime", "from datetime import datetime");
                return "datetime";
            case "java.time.Instant":
                imports.put("__java.time.Instant", "from datetime import datetime");
                return "datetime";
            default:
                javaType = cleanClassName(javaType);
                if (javaType.contains(".")) {
                    String className = ClassContainer.parsePythonClassName(javaType);
                    // Handle import
                    if (!className.equals(container.getPythonClassName())) {
                        String moduleName = ClassContainer.parsePythonModuleName(javaType);
                        imports.put(javaType, "from " + moduleName + " import " + className);
                        return className;
                    }
                    return "\"" + className + "\"";
                } else if (javaType.length() == 1) {
                    String _javaType = generics.get(javaType);
                    if (_javaType != null) {
                        return _javaType;
                    }
                }
                return javaType;
        }
    }

    private String convertFieldValue(@Nullable Object value) {
        if (value == null) {
            return "None";
        }
        if (value instanceof Number) {
            return value.toString();
        }
        if (value instanceof Boolean) {
            return ((Boolean) value) ? "True" : "False";
        }
        return "\"" + value.toString() + "\"";
    }

    private void collectGenerics(Type genericType) {
        if (genericType instanceof TypeVariable) {
            TypeVariable<?> _type = (TypeVariable<?>) genericType;
            if (!generics.containsKey(_type.getTypeName())) {
                generics.put(_type.getTypeName(), cleanClassName(_type.getBounds()[0].getTypeName()));
            }
        } else if (genericType instanceof ParameterizedType) {
            ParameterizedType _type = (ParameterizedType) genericType;
            collectGenerics(_type.getRawType());
            for (Type _genericType : _type.getActualTypeArguments()) {
                collectGenerics(_genericType);
            }
        } else if (genericType instanceof WildcardType) {
            WildcardType _type = (WildcardType) genericType;
            if (_type.getUpperBounds().length > 0) {
                for (Type _genericType : _type.getUpperBounds()) {
                    collectGenerics(_genericType);
                }
            } else if (_type.getLowerBounds().length > 0) {
                for (Type _genericType : _type.getLowerBounds()) {
                    collectGenerics(_genericType);
                }
            }
        }
    }

    private String cleanClassName(String className) {
        Matcher matcher = CLASS_MATCHER.matcher(className);
        if (matcher.find() && !className.equals(matcher.group(1))) {
            // System.out.println("parseSubType: " + container.getRelatedClass().getName() + " | "
            // + _type.getTypeName() + " | " + javaSubType + " | " + matcher.group(1));
            return matcher.group(1);
        }
        return className;
    }

    private @Nullable String buildClassDocumentationBlock() {
        if (!container.getPythonModuleName().startsWith("org.openhab.core")) {
            return null;
        }

        String classUrl = BASE_URL
                + container.getRelatedClass().getName().toLowerCase().replace(".", "/").replace("$", ".");

        StringBuilder builder = new StringBuilder();
        builder.append("    \"\"\"\n");
        builder.append("    Java class: ").append(container.getRelatedClass().getName()).append("\n\n");
        builder.append("    Java doc: ").append(classUrl);
        builder.append("\n");
        builder.append("    \"\"\"\n");
        return builder.toString();
    }

    private @Nullable String buildMethodDocumentationBlock(MethodContainer method) {
        if (!container.getPythonModuleName().startsWith("org.openhab.core")) {
            return null;
        }

        String classUrl = BASE_URL
                + container.getRelatedClass().getName().toLowerCase().replace(".", "/").replace("$", ".");

        StringBuilder builder = new StringBuilder();
        builder.append("        \"\"\"\n");
        builder.append("        Java doc url:\n");

        String functionRepresentation = method.getRawStringRepresentation();
        Pattern pattern = Pattern.compile("([^\\.]+\\([^\\)]*\\))", Pattern.CASE_INSENSITIVE);
        Matcher matcher1 = pattern.matcher(functionRepresentation);
        if (matcher1.find()) {
            functionRepresentation = matcher1.group(1);
        }
        functionRepresentation = functionRepresentation.replaceAll("<>\\?", "").replace("$", ".");
        // System.out.println(classUrl + "#" + functionRepresentation);
        builder.append("        ").append(classUrl).append("#").append(functionRepresentation);

        builder.append("\n");
        builder.append("        \"\"\"\n");
        return builder.toString();
    }
}
