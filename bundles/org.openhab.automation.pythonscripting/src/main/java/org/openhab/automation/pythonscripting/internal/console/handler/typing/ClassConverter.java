package org.openhab.automation.pythonscripting.internal.console.handler.typing;

import java.io.IOException;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.automation.pythonscripting.internal.console.handler.typing.ClassCollector.ClassContainer;

@NonNullByDefault
public class ClassConverter {
    public static @Nullable String buildClass(ClassContainer container, Map<String, ClassContainer> containers)
            throws IOException, ClassNotFoundException {
        Class<?> cls = container.getRelatedClass();

        String simpleClassName = cls.getSimpleName();
        String fullClassName = cls.getName();

        if (fullClassName.endsWith("$" + simpleClassName)) {
            return null;
        }

        StringBuilder classBody = new StringBuilder();

        Map<String, String> generics = new HashMap<String, String>();
        Map<String, String> imports = new HashMap<String, String>();

        classBody.append(buildClassHead(cls, containers.keySet(), imports));

        List<Method> methods = container.getRelatedMethods();
        for (Method method : methods) {

            classBody.append(buildClassMethod(method, containers.keySet(), imports, generics));
        }
        classBody.insert(0, "\n");
        classBody.insert(0, "\n");

        classBody.insert(0, buildClassImports(imports));

        return classBody.toString();
    }

    private static Object buildClassImports(Map<String, String> imports) {
        StringBuilder builder = new StringBuilder();
        HashSet<String> hashSet = new HashSet<>(imports.values());
        ArrayList<String> sortedImports = new ArrayList<>(hashSet);
        sortedImports.sort(Comparator.reverseOrder());

        for (String importLine : sortedImports) {
            builder.append(importLine + "\n");
        }
        return builder.toString();
    }

    private static String buildClassMethod(Method method, Set<String> classes, Map<String, String> imports,
            Map<String, String> generics) {
        Class<?>[] pType = method.getParameterTypes();
        Type[] gpType = method.getGenericParameterTypes();

        // Collect generics
        collectGenerics(method.getGenericReturnType(), method.getReturnType(), generics);
        int i = 0;
        for (Parameter parameter : method.getParameters()) {
            collectGenerics(gpType[i], pType[i], generics);
            i++;
        }

        // Collect arguments
        List<String> arguments = new ArrayList<String>();
        if (!Modifier.isStatic(method.getModifiers())) {
            arguments.add("self");
        }
        i = 0;
        for (Parameter parameter : method.getParameters()) {
            String type = convertToPythonType(gpType[i], pType[i], classes, imports, generics);
            arguments.add(parameter.getName() + ": " + type);
            i++;
        }

        String type = convertToPythonType(method.getGenericReturnType(), method.getReturnType(), classes, imports,
                generics);

        StringBuilder builder = new StringBuilder();
        if (Modifier.isStatic(method.getModifiers())) {
            builder.append("    @staticmethod\n");
            builder.append("    def " + method.getName() + "() -> " + type + ":\n        pass\n");
        } else {
            builder.append("    def " + method.getName() + "(" + String.join(", ", arguments) + ") -> " + type
                    + ":\n        pass\n");
        }
        builder.append("\n");

        return builder.toString();
    }

    private static String buildClassHead(Class<?> cls, Set<String> classes, Map<String, String> imports) {
        StringBuilder line = new StringBuilder();
        line.append("class " + cls.getSimpleName());
        Class<?> parentCls = cls.getSuperclass();
        if (parentCls != null) {
            String pythonType = convertJavaToPythonType(parentCls.getName(), imports);
            pythonType = importPythonType(pythonType, classes, imports);
            line.append("(" + pythonType + ")");
        }
        line.append(":\n");
        return line.toString();
    }

    private static void collectGenerics(Type genericType, Type type, Map<String, String> generics) {
        if (genericType instanceof TypeVariable) {
            TypeVariable _type = (TypeVariable) genericType;
            if (!generics.containsKey(_type.getTypeName())) {
                generics.put(_type.getTypeName(), _type.getBounds()[0].getTypeName());
            }
        }
    }

    private static String convertToPythonType(Type genericType, Type type, Set<String> classes,
            Map<String, String> imports, Map<String, String> generics) {
        boolean isList = false;
        String javaType = null;
        String subJavaType = null;
        if (genericType instanceof GenericArrayType) {
            GenericArrayType _type = (GenericArrayType) genericType;
            // System.out.println("GenericArrayType");

        } else if (genericType instanceof ParameterizedType) {
            ParameterizedType _type = (ParameterizedType) genericType;
            javaType = _type.getRawType().getTypeName();
            // System.out.println("ParameterizedType " + _type);
            if ((_type.getActualTypeArguments()[0] instanceof TypeVariable)) {
                subJavaType = generics.get(_type.getActualTypeArguments()[0].getTypeName());
            } else {
                subJavaType = _type.getActualTypeArguments()[0].getTypeName();
            }
        } else if (genericType instanceof TypeVariable) {
            TypeVariable _type = (TypeVariable) genericType;
            // System.out.println("TypeVariable " + _type);
            javaType = _type.getTypeName();
            javaType = generics.get(javaType);
        } else if (genericType instanceof WildcardType) {
            WildcardType _type = (WildcardType) genericType;
            // System.out.println("WildcardType " + _type);
        } else {
            javaType = type.getTypeName();
            isList = javaType.endsWith("[]");
            if (isList) {
                subJavaType = javaType.substring(0, javaType.length() - 2);
                javaType = "java.util.List";
            }
        }

        if (javaType != null) {
            String pythonType = convertJavaToPythonType(javaType, imports);
            pythonType = importPythonType(pythonType, classes, imports);
            if ("type".equals(pythonType)) {
                if (subJavaType != null) {
                    String subPythonType = convertJavaToPythonType(subJavaType, imports);
                    subPythonType = importPythonType(subPythonType, classes, imports);
                    return "type[" + subPythonType + "]";
                }
                return "type";
            } else if ("list".equals(pythonType)) {
                if (subJavaType != null) {
                    Pattern pattern = Pattern.compile("java\\.lang\\.Class<\\? extends ([^><]+)",
                            Pattern.CASE_INSENSITIVE);
                    Matcher matcher = pattern.matcher(subJavaType);
                    if (matcher.find()) {
                        subJavaType = matcher.group(1);
                    }
                    String subPythonType = convertJavaToPythonType(subJavaType, imports);
                    subPythonType = importPythonType(subPythonType, classes, imports);
                    return "list[" + subPythonType + "]";
                }
                return "list[]";
            }
            return pythonType;
        }
        return "None";
    }

    private static String convertJavaToPythonType(String type, Map<String, String> imports) {
        switch (type) {
            case "int":
                return "int";
            case "byte":
                return "bytes";
            case "java.lang.String":
                return "str";
            case "java.lang.Object":
                return "object";
            case "java.lang.Class":
                return "type";
            case "java.util.Map":
                return "dict";
            case "java.util.List":
            case "java.util.Set":
                return "list";
            case "boolean":
                return "bool";
            case "java.time.ZonedDateTime":
                imports.put("java.time.ZonedDateTime", "from datetime import datetime");
                return "datetime";
            case "java.time.Instant":
                imports.put("java.time.Instant", "from datetime import datetime");
                return "datetime";
            case "void":
                return "None";
            default:
                return type;
        }
    }

    private static String importPythonType(String pythonType, Set<String> classes, Map<String, String> imports) {
        if (pythonType != null) {
            if (classes.contains(pythonType) || (pythonType.startsWith("java.") && !pythonType.startsWith("java.lang"))
                    || pythonType.startsWith("javax.")) {
                String moduleName = pythonType.substring(0, pythonType.lastIndexOf("."));
                String typeName = pythonType.substring(pythonType.lastIndexOf(".") + 1);
                imports.put(pythonType, "from " + moduleName + " import " + typeName);

                return typeName;
            }
        }
        return pythonType;
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
