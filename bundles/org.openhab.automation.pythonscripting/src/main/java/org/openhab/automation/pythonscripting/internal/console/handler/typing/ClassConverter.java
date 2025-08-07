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

@NonNullByDefault
public class ClassConverter {
    private final Logger logger = LoggerFactory.getLogger(ClassConverter.class);

    private static Pattern EXTENDS_MATCHER = Pattern.compile("\\? extends ([a-z0-9\\.]+)", Pattern.CASE_INSENSITIVE);
    private static Pattern CLASS_MATCHER = Pattern.compile("([a-z0-9\\.]+)", Pattern.CASE_INSENSITIVE);

    public void buildClass(ClassContainer container) throws IOException, ClassNotFoundException {
        StringBuilder classBody = new StringBuilder();

        Map<String, String> generics = new HashMap<String, String>();
        Map<String, String> imports = new HashMap<String, String>();

        classBody.append(buildClassHead(container, imports, generics));

        List<MethodContainer> methods = container.getRelatedMethods();
        for (MethodContainer method : methods) {

            classBody.append(buildClassMethod(container, method, imports, generics));
        }
        if (imports.size() > 0) {
            classBody.insert(0, "\n\n");
            classBody.insert(0, buildClassImports(imports));
        }

        container.setClassStub(classBody.toString(), imports.keySet());
    }

    private Object buildClassImports(Map<String, String> imports) {
        StringBuilder builder = new StringBuilder();
        HashSet<String> hashSet = new HashSet<>(imports.values());
        ArrayList<String> sortedImports = new ArrayList<>(hashSet);
        sortedImports.sort(Comparator.reverseOrder());

        for (String importLine : sortedImports) {
            builder.append(importLine + "\n");
        }
        return builder.toString();
    }

    private String buildClassMethod(ClassContainer container, MethodContainer method, Map<String, String> imports,
            Map<String, String> generics) {

        // Collect generics
        for (int i = 0; i < method.getReturnTypeCount(); i++) {
            collectGenerics(method.getGenericReturnType(i), method.getReturnType(i), generics);
        }
        for (ParameterContainer p : method.getParameters()) {
            for (int i = 0; i < p.getTypeCount(); i++) {
                collectGenerics(p.getGenericType(i), p.getType(i), generics);
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
                String t = convertToPythonType(container, p.getGenericType(i), p.getType(i), imports, generics, true);
                parameterTypes.add(t);
            }
            List<String> sortedParameterTypes = parameterTypes.stream().sorted().collect(Collectors.toList());
            arguments.add(
                    p.getName() + ": " + String.join(" | ", sortedParameterTypes) + (p.isOptional ? " = None" : ""));
        }

        // Collect Return types
        Set<String> returnTypes = new HashSet<String>();
        for (int i = 0; i < method.getReturnTypeCount(); i++) {
            String t = convertToPythonType(container, method.getGenericReturnType(i), method.getReturnType(i), imports,
                    generics, false);
            returnTypes.add(t);
        }
        List<String> sortedReturnTypes = returnTypes.stream().sorted().collect(Collectors.toList());

        StringBuilder builder = new StringBuilder();
        if (Modifier.isStatic(method.getModifiers())) {
            builder.append("    @staticmethod\n");
        }
        builder.append("    def " + method.getName() + "(" + String.join(", ", arguments) + ") -> "
                + String.join(" | ", sortedReturnTypes) + ":\n");
        String doc = buildMethodDocumentationBlock(container, method);
        if (doc != null) {
            builder.append(doc);
        } else {
            builder.append("        pass\n");
        }
        builder.append("\n");

        return builder.toString();
    }

    private String buildClassHead(ClassContainer container, Map<String, String> imports, Map<String, String> generics) {
        StringBuilder builder = new StringBuilder();
        Class<?> cls = container.getRelatedClass();

        String packageName = cls.getName();
        String moduleName = ClassContainer.parseModuleName(packageName);
        container.setModuleName(moduleName);
        String className = ClassContainer.parseClassName(packageName);
        container.setClassName(className);

        builder.append("class " + container.getClassName());
        Class<?> parentCls = cls.getSuperclass();
        if (parentCls != null) {
            String pythonType = convertBaseJavaToPythonType(parentCls.getName(), container, imports, generics);
            builder.append("(" + pythonType + ")");
        }
        builder.append(":\n");
        String doc = buildClassDocumentationBlock(container);
        if (doc != null) {
            builder.append(doc);
            builder.append("\n");
        }

        boolean hasFields = false;
        for (Field field : container.getRelatedFields()) {
            try {
                String type = convertToPythonType(container, field.getGenericType(), field.getType(), imports, generics,
                        true);
                String value = "";
                Class<?> t = field.getType();

                if (Collection.class.isAssignableFrom(field.getType())) {
                    List<String> values = new ArrayList<String>();
                    Collection<Object> _values = (Collection<Object>) field.get(null);
                    for (Object _value : _values) {
                        values.add(buildValue(_value));
                    }
                    value = "[" + String.join(",", values) + "]";
                } else if (t == short.class) {
                    value = buildValue(field.getShort(null));
                } else if (t == int.class) {
                    value = buildValue(field.getInt(null));
                } else if (t == long.class) {
                    value = buildValue(field.getLong(null));
                } else if (t == float.class) {
                    value = buildValue(field.getFloat(null));
                } else if (t == double.class) {
                    value = buildValue(field.getDouble(null));
                } else if (t == boolean.class) {
                    value = buildValue(field.getBoolean(null));
                } else if (t == char.class) {
                    value = buildValue(field.getChar(null));
                } else {
                    value = buildValue(field.get(null).toString());
                }

                builder.append("    " + field.getName() + ": " + type + " = " + value + "\n");
                hasFields = true;
            } catch (IllegalArgumentException | IllegalAccessException e) {
                logger.warn("Cant convert static field {} of class {}", container.getRelatedClass().getName(),
                        field.getName(), e);
            }
        }
        if (hasFields) {
            builder.append("\n");
        }

        return builder.toString();
    }

    private String buildValue(Object value) {
        if (value instanceof Number) {
            return value.toString();
        }
        if (value instanceof Boolean) {
            return ((Boolean) value) ? "True" : "False";
        }
        return "\"" + value.toString() + "\"";
    }
    /*
     * if (value.class.isNestmateOf(short.class)) {
     * return String.valueOf(field.getShort(null));
     * } else if (t == int.class) {
     * value = String.valueOf(field.getInt(null));
     * } else if (t == long.class) {
     * value = String.valueOf(field.getLong(null));
     * } else if (t == float.class) {
     * value = String.valueOf(field.getFloat(null));
     * } else if (t == double.class) {
     * value = String.valueOf(field.getDouble(null));
     * } else if (t == boolean.class) {
     * value = String.valueOf(field.getBoolean(null) ? "True" : "False");
     * } else if (t == char.class) {
     * value = "\"" + String.valueOf(field.getChar(null)) + "\"";
     * } else {
     * value = "\"" + field.get(null).toString() + "\"";
     * }
     * }
     */

    private void collectGenerics(Type genericType, Type type, Map<String, String> generics) {
        if (genericType instanceof TypeVariable) {
            TypeVariable<?> _type = (TypeVariable<?>) genericType;
            if (!generics.containsKey(_type.getTypeName())) {
                String javaSubType = parseSubType(_type.getBounds()[0]);
                generics.put(_type.getTypeName(), javaSubType);
            }
        }
    }

    private String convertToPythonType(ClassContainer container, Type genericType, Type type,
            Map<String, String> imports, Map<String, String> generics, boolean isArgument) {
        boolean isList = false;
        String javaType = null;
        String subJavaType = null;
        if (genericType instanceof TypeVariable) {
            TypeVariable<?> _type = (TypeVariable<?>) genericType;
            // System.out.println("TypeVariable " + _type);
            javaType = _type.getTypeName();
            javaType = generics.get(javaType);
        } else if (genericType instanceof ParameterizedType) {
            ParameterizedType _type = (ParameterizedType) genericType;
            javaType = _type.getRawType().getTypeName();
            // System.out.println("ParameterizedType " + _type);
            subJavaType = convertSubType(_type.getActualTypeArguments()[0], generics);
        } else if (genericType instanceof WildcardType) {
            // TODO Not tested
            WildcardType _type = (WildcardType) genericType;
            // System.out.println("WildcardType " + _type);
            javaType = "java.lang.Class";
            if (_type.getUpperBounds().length > 0) {
                subJavaType = convertSubType(_type.getUpperBounds()[0], generics);
            } else if (_type.getLowerBounds().length > 0) {
                subJavaType = convertSubType(_type.getUpperBounds()[0], generics);
            }
        } else if (genericType instanceof GenericArrayType) {
            // TODO Not tested
            GenericArrayType _type = (GenericArrayType) genericType;
            // System.out.println("GenericArrayType " + _type);
            javaType = "java.util.List";
            subJavaType = convertSubType(_type.getGenericComponentType(), generics);
        } else {
            javaType = type.getTypeName();
            // System.out.println("Other " + javaType);
            isList = javaType.endsWith("[]");
            if (isList) {
                subJavaType = javaType.substring(0, javaType.length() - 2);
                javaType = "java.util.List";
            }
        }

        if (javaType != null) {
            if (isArgument) {
                return convertJavaArgumentToPythonType(javaType, subJavaType, container, imports, generics);
            }
            return convertJavaReturnToPythonType(javaType, subJavaType, container, imports, generics);
        }
        return "None";
    }

    private @Nullable String convertSubType(Type type, Map<String, String> generics) {
        if (type instanceof TypeVariable) {
            return generics.get(type.getTypeName());
        }
        return parseSubType(type);
    }

    private String parseSubType(Type type) {
        String typeName = type.getTypeName();
        Matcher matcher = EXTENDS_MATCHER.matcher(typeName);
        if (matcher.find()) {
            typeName = matcher.group(1);
        }
        matcher = CLASS_MATCHER.matcher(typeName);
        if (matcher.find()) {
            typeName = matcher.group(1);
        }
        return typeName;
    }

    private String convertJavaArgumentToPythonType(String mainJavaType, @Nullable String subJavaType,
            ClassContainer container, Map<String, String> imports, Map<String, String> generics) {
        switch (mainJavaType) {
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
            case "java.util.Map":
                return "dict";
            case "java.util.List":
            case "java.util.Set":
                if (subJavaType != null) {
                    String subPythonType = convertJavaArgumentToPythonType(subJavaType, null, container, imports,
                            generics);
                    return "list[" + subPythonType + "]";
                }
                return "list[]";
            case "java.lang.Class":
                if (subJavaType != null) {
                    String subPythonType = convertJavaArgumentToPythonType(subJavaType, null, container, imports,
                            generics);
                    return subPythonType;
                }
                return "any";
        }

        return convertJavaReturnToPythonType(mainJavaType, subJavaType, container, imports, generics);
    }

    private String convertJavaReturnToPythonType(String mainJavaType, @Nullable String subJavaType,
            ClassContainer container, Map<String, String> imports, Map<String, String> generics) {
        switch (mainJavaType) {
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
            case "void":
                return "None";
            case "?":
                return "any";
        }

        return convertBaseJavaToPythonType(mainJavaType, container, imports, generics);
    }

    private String convertBaseJavaToPythonType(String javaType, ClassContainer container, Map<String, String> imports,
            Map<String, String> generics) {
        switch (javaType) {
            case "java.lang.String":
                return "str";
            case "java.lang.Object":
                return "object";
            case "java.time.ZonedDateTime":
                imports.put("java.time.ZonedDateTime", "from datetime import datetime");
                return "datetime";
            case "java.time.Instant":
                imports.put("java.time.Instant", "from datetime import datetime");
                return "datetime";
            default:
                if (javaType.contains(".")) {
                    String className = ClassContainer.parseClassName(javaType);
                    // Handle import
                    if (!className.equals(container.getClassName())) {
                        String moduleName = ClassContainer.parseModuleName(javaType);
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

    private @Nullable String buildClassDocumentationBlock(ClassContainer container) {
        StringBuilder builder = new StringBuilder();
        builder.append("    \"\"\"\n");
        builder.append("    Java class: ").append(container.getRelatedClass().getName()).append("\n\n");
        builder.append("    Java doc: https://www.openhab.org/javadoc/latest/");
        builder.append(container.getRelatedClass().getName().toLowerCase().replace(".", "/").replace("$", "."));
        builder.append("\n");
        builder.append("    \"\"\"\n");
        return builder.toString();
    }

    private @Nullable String buildMethodDocumentationBlock(ClassContainer container, MethodContainer method) {
        if (!container.getModuleName().startsWith("org.openhab.core")) {
            return null;
        }

        StringBuilder builder = new StringBuilder();
        builder.append("        \"\"\"\n");
        builder.append("        Java doc url:\n");
        builder.append("        https://www.openhab.org/javadoc/latest/");
        builder.append(container.getRelatedClass().getName().toLowerCase().replace(".", "/").replace("$", "."));
        if (method.getParameterCount() == 0) {
            builder.append("#").append(method.getName()).append("()");
        }
        builder.append("\n");
        builder.append("        \"\"\"\n");
        return builder.toString();
    }

    /*
     * Constructor<?>[] constructors = cls.getClass().getConstructors();
     * for (Constructor<?> constructor : constructors) {
     * String methodName = "__init__";
     * List<String> arguments = new ArrayList<String>();
     * arguments.add("self");
     *
     * Class<?>[] pType = constructor.getParameterTypes();
     * Type[] gpType = constructor.getGenericParameterTypes();
     *
     * int i = 0;
     * for (Parameter parameter : constructor.getParameters()) {
     * String type = mapType(importedClasses, classMap, typeClasses, gpType[i], pType[i]);
     * arguments.add(parameter.getName() + ": " + type);
     * i++;
     * }
     *
     * classBody.append("    def " + methodName + "(" + String.join(", ", arguments) + "): ...\n");
     * classBody.append("\n");
     * }
     */
}
