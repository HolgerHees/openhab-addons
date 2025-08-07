package org.openhab.automation.pythonscripting.internal.console.handler.typing;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.automation.pythonscripting.internal.console.handler.Typing.Logger;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

@NonNullByDefault
public class ClassCollector {
    public Map<String, ClassContainer> collectBundleClasses(String packageName, Logger logger) throws Exception {
        List<Class<?>> clsList = new ArrayList<Class<?>>();
        Bundle bundle = FrameworkUtil.getBundle(ClassCollector.class);
        Bundle[] bundles = bundle.getBundleContext().getBundles();
        for (Bundle b : bundles) {
            List<Class<?>> bundleClsList = new ArrayList<Class<?>>();
            Enumeration<URL> entries = b.findEntries(packageName.replace(".", "/"), "*.class", true);
            if (entries != null) {
                while (entries.hasMoreElements()) {
                    String entry = entries.nextElement().toString();
                    String clsName = entry.substring(entry.indexOf(packageName.replace(".", "/")));
                    if (clsName.indexOf("/internal") != -1) {
                        continue;
                    }
                    clsName = clsName.replace(".class", "").replace("/", ".");
                    try {
                        Class cls = Class.forName(clsName);
                        if (!Modifier.isPublic(cls.getModifiers())) {
                            continue;
                        }
                        bundleClsList.add(cls);
                    } catch (ClassNotFoundException e) {
                        logger.warn("BUNDLE: " + b + " class " + clsName + " not found");
                    }
                }
            }
            if (bundleClsList.size() > 0) {
                logger.info("BUNDLE: " + b + " with " + bundleClsList.size() + " classes processed");
                clsList.addAll(bundleClsList);
            }
        }
        return processClasses(clsList);
    }

    public Map<String, ClassContainer> collectReflectionClasses(Set<String> imports, Logger logger) throws Exception {
        List<Class<?>> clsList = new ArrayList<Class<?>>();
        for (String _import : imports) {
            try {
                clsList.add(Class.forName(_import));
            } catch (ClassNotFoundException e) {
                logger.warn("Class " + _import + " not found");
            }
        }

        /*
         * Reflections reflections = new Reflections(imports, new SubTypesScanner(false));
         * for (String c : reflections.getAllTypes()) {
         * try {
         * clsList.add(Class.forName(c));
         * } catch (ClassNotFoundException e) {
         * }
         * }
         */
        return processClasses(clsList);
    }

    private Map<String, ClassContainer> processClasses(List<Class<?>> clsList) {
        Map<String, ClassContainer> result = new HashMap<String, ClassContainer>();
        for (Class<?> cls : clsList) {
            // if (!cls.getSimpleName().equals("BusEvent")) {
            // continue;
            // }
            result.put(cls.getName(), new ClassContainer(cls));
        }
        return result;
    }

    public static class ClassContainer {
        private Class<?> cls;
        private List<Field> fields;
        private Map<String, MethodContainer> methods = new HashMap<String, ClassCollector.MethodContainer>();

        private String className = "";
        private String moduleName = "";
        private String body = "";
        private Set<String> imports = new HashSet<String>();

        public ClassContainer(Class<?> cls) {
            this.cls = cls;

            fields = Arrays.stream(cls.getDeclaredFields()).filter(
                    method -> Modifier.isPublic(method.getModifiers()) && Modifier.isStatic(method.getModifiers()))
                    .collect(Collectors.toList());

            List<Method> methods = Arrays.stream(cls.getDeclaredMethods()).filter(
                    method -> Modifier.isPublic(method.getModifiers()) && !Modifier.isVolatile(method.getModifiers()))
                    .collect(Collectors.toList());

            // for (Method method : methods) {
            // System.out.println(method.getName() + " " + Modifier.toString(method.getModifiers()));
            // }

            Collections.sort(methods, new Comparator<Method>() {
                @Override
                public int compare(Method o1, Method o2) {
                    return o1.getName().compareTo(o2.getName());
                }
            });

            for (Method method : methods) {
                String uid = method.getName();
                // + "|" + method.getParameterCount();
                MethodContainer methodContainer;
                if (!this.methods.containsKey(uid)) {
                    methodContainer = new MethodContainer(method);
                    this.methods.put(uid, methodContainer);
                }
                this.methods.get(uid).addParametersFrom(method);
            }
        }

        public Class<?> getRelatedClass() {
            return cls;
        }

        public List<Field> getRelatedFields() {
            return fields;
        }

        public List<MethodContainer> getRelatedMethods() {
            return new ArrayList<MethodContainer>(methods.values());
        }

        public void setClassStub(String body, Set<String> set) {
            this.body = body;
            this.imports = set;
        }

        public @Nullable String getBody() {
            return body;
        }

        public List<String> getImports() {
            return new ArrayList<String>(this.imports);
        }

        public void setClassName(String className) {
            this.className = className;
        }

        public String getClassName() {
            return className;
        }

        public void setModuleName(String moduleName) {
            this.moduleName = moduleName;
        }

        public String getModuleName() {
            return moduleName;
        }

        public static String parseModuleName(String name) {
            return name.substring(0, name.lastIndexOf("."));
        }

        public static String parseClassName(String name) {
            String className = name.substring(name.lastIndexOf(".") + 1);
            if (className.contains("$")) {
                className = className.replace("$", "_");
            }
            return className;
        }
    }

    public static class MethodContainer {
        Method method;
        List<Type> returnTypes = new ArrayList<Type>();
        List<Class<?>> returnClasses = new ArrayList<Class<?>>();
        List<ParameterContainer> args = new ArrayList<ParameterContainer>();

        public MethodContainer(Method method) {
            this.method = method;

            this.returnTypes.add(method.getGenericReturnType());
            this.returnClasses.add(method.getReturnType());
        }

        public Method getRelatedMethod() {
            return method;
        }

        public void addParametersFrom(Method method) {
            returnTypes.add(method.getGenericReturnType());
            returnClasses.add(method.getReturnType());

            Type[] gpType = method.getGenericParameterTypes();
            Class<?>[] pType = method.getParameterTypes();
            for (int i = 0; i < method.getParameterCount(); i++) {
                if (args.size() <= i) {
                    args.add(new ParameterContainer(method.getParameters()[i], gpType[i], pType[i]));
                } else {
                    args.get(i).addParameter(method.getParameters()[i], gpType[i], pType[i]);
                }
            }

            for (int i = method.getParameterCount(); i < args.size(); i++) {
                args.get(i).markAsOptional();
            }
        }

        public String getName() {
            return method.getName();
        }

        public int getModifiers() {
            return method.getModifiers();
        }

        public int getReturnTypeCount() {
            return returnTypes.size();
        }

        public Type getGenericReturnType(int index) {
            return returnTypes.get(index);
        }

        public Class<?> getReturnType(int index) {
            return returnClasses.get(index);
        }

        public List<ParameterContainer> getParameters() {
            return args;
        }

        public int getParameterCount() {
            return args.size();
        }

        public ParameterContainer getParameter(int index) {
            return args.get(index);
        }
    }

    public static class ParameterContainer {
        Parameter parameter;
        boolean isOptional = false;
        List<Type> types = new ArrayList<Type>();
        List<Class<?>> clss = new ArrayList<Class<?>>();

        public ParameterContainer(Parameter arg, Type type, Class cls) {
            this.parameter = arg;
            this.types.add(type);
            this.clss.add(cls);
        }

        public void addParameter(Parameter arg, Type type, Class cls) {
            types.add(type);
            clss.add(cls);
        }

        public String getName() {
            return parameter.getName();
        }

        public int getTypeCount() {
            return types.size();
        }

        public Type getGenericType(int index) {
            return types.get(index);
        }

        public Class<?> getType(int index) {
            return clss.get(index);
        }

        public void markAsOptional() {
            isOptional = true;
        }

        public boolean isOptional() {
            return isOptional;
        }
    }
}
