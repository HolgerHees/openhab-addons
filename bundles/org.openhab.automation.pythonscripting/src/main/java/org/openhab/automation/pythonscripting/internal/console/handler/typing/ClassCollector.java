package org.openhab.automation.pythonscripting.internal.console.handler.typing;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.automation.pythonscripting.internal.console.handler.Typing.Logger;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

@NonNullByDefault
public class ClassCollector {
    public static Map<String, ClassContainer> collectContainer(String packageName, Logger logger) throws Exception {
        Map<String, ClassContainer> result = new HashMap<String, ClassContainer>();

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
                        bundleClsList.add(Class.forName(clsName));
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
        /*
         * Reflections reflections = new Reflections(packageName, new SubTypesScanner(false));
         * List<Class<?>> clsList = new ArrayList<Class<?>>();
         * for (String c : reflections.getAllTypes()) {
         * try {
         * clsList.add(Class.forName(c));
         * } catch (ClassNotFoundException e) {
         * }
         * }
         */
        for (Class<?> cls : clsList) {
            // if (!cls.getSimpleName().equals("RootUIComponent")) {
            // continue;
            // }

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

            result.put(cls.getName(), new ClassContainer(cls, methods));
        }
        return result;
    }

    public static class ClassContainer {
        private Class<?> cls;
        private List<Method> methods;

        public ClassContainer(Class<?> cls, List<Method> methods) {
            this.cls = cls;
            this.methods = methods;
        }

        public Class<?> getRelatedClass() {
            return cls;
        }

        public List<Method> getRelatedMethods() {
            return methods;
        }
    }
}
