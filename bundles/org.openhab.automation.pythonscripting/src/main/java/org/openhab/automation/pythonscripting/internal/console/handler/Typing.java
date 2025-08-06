package org.openhab.automation.pythonscripting.internal.console.handler;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.automation.pythonscripting.internal.console.handler.typing.ClassCollector;
import org.openhab.automation.pythonscripting.internal.console.handler.typing.ClassCollector.ClassContainer;
import org.openhab.automation.pythonscripting.internal.console.handler.typing.ClassConverter;
import org.openhab.core.io.console.Console;

@NonNullByDefault
public class Typing {
    /*
     * public static void main(String[] args) throws Exception {
     * Builder builder = new Builder();
     * builder.build("org.openhab", Paths.get("/openhab/conf/automation/python/typings/"));
     *
     * // https://gitlab.cern.ch/scripting-tools/stubgenj
     * // https://github.com/paul-hammant/paranamer
     * // https://mypy.readthedocs.io/en/stable/stubs.html
     * }
     */

    public static class Logger {
        private Object logger;

        public Logger(Console console) {
            this.logger = console;
        }

        public Logger(org.slf4j.Logger logger) {
            this.logger = logger;
        }

        public void info(String s) {
            if (logger instanceof Console console) {
                console.println("INFO: " + s);
            } else {
                ((org.slf4j.Logger) logger).info(s);
            }
        }

        public void warn(String s) {
            if (logger instanceof Console console) {
                console.println("WARN: " + s);
            } else {
                ((org.slf4j.Logger) logger).warn(s);
            }
        }
    }

    public static void build(String packageName, Path outputPath, Logger logger) throws Exception {
        // Cleanup Directory
        if (Files.isDirectory(outputPath)) {
            try (Stream<Path> paths = Files.walk(outputPath)) {
                paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
        }
        // Collect Classes
        Map<String, ClassContainer> classMap = ClassCollector.collectContainer(packageName, logger);

        // Generate Class Files
        Map<String, String> fileModuleMap = new HashMap<String, String>();
        for (ClassContainer container : classMap.values()) {
            String classBody = ClassConverter.buildClass(container, classMap);
            if (classBody == null) {
                continue;
            }

            String fullClassName = container.getRelatedClass().getName();
            String fullModulePath = fullClassName.replace(".", "/");
            String baseModulePath = fullModulePath.substring(0, fullModulePath.lastIndexOf("/"));
            String moduleName = fullModulePath.substring(fullModulePath.lastIndexOf("/") + 1);

            Path path = outputPath.resolve(baseModulePath).resolve("__" + moduleName.toLowerCase() + "__.py");
            fileModuleMap.put(path.toString(), moduleName);

            dumpContentToFile(classBody.toString(), path);
        }

        // Generate __init__.py Files
        Path path = outputPath.resolve(packageName.replace(".", "/"));
        dumpInit(path.toString(), fileModuleMap);

        logger.info("Total of " + classMap.size() + " type hint files create in '" + outputPath + "'");
    }

    public static void dumpInit(String path, Map<String, String> fileModuleMap) throws IOException {

        File root = new File(path);
        File[] list = root.listFiles();
        if (list == null) {
            return;
        }

        ArrayList<File> files = new ArrayList<File>();
        for (File f : list) {
            if (f.isDirectory()) {
                dumpInit(f.getAbsolutePath(), fileModuleMap);
            } else {
                files.add(f);
            }
        }

        if (files.size() > 0) {
            StringBuilder initBody = new StringBuilder();
            // List<String> modules = new ArrayList<String>();
            for (File file : files) {
                if (file.toString().endsWith("__init__.py")) {
                    continue;
                }
                initBody.append("from ." + file.getName().replace(".py", "") + " import "
                        + fileModuleMap.get(file.toString()) + "\n");
            }

            String packageUrl = path.replace(".", "/") + "/__init__.py";
            Path initPath = Paths.get(packageUrl);
            dumpContentToFile(initBody.toString(), initPath);
        }
    }

    private static void dumpContentToFile(String content, Path path) throws IOException {
        Path parent = path.getParent();
        File directory = parent.toFile();
        if (!directory.exists()) {
            directory.mkdirs();
        }
        Files.write(path, content.getBytes());
    }
}
