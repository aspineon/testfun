package org.testfun.jee.runner.inject;

import junit.framework.AssertionFailedError;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.testfun.jee.runner.EjbWithMockitoRunnerException;

import java.io.File;
import java.io.IOException;
import java.util.Enumeration;
import java.util.LinkedList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.Assert.fail;

public class ClassPathScanner {

    private static final Logger LOGGER = LogManager.getLogger(ClassPathScanner.class);

    public List<String> getResourcesInClassPath() {
        boolean jarScanningEnabled = Boolean.getBoolean("org.testfun.jee.enable_jar_scanning");

        List<String> resourceNames = new LinkedList<>();

        for (String root : getClasPathRoots()) {
            File rootFile = new File(root);
            if (rootFile.isDirectory())
            if (rootFile.exists()) {
                if (rootFile.isDirectory()) {
                    findResourcesFromDirectory(resourceNames, rootFile.getAbsolutePath().length() + 1, rootFile);
                } else if (jarScanningEnabled) {
                    findResourcesFromJarFile(resourceNames, rootFile);
                }
            }
        }

        return resourceNames;
    }

    String[] getClasPathRoots() {
        String pathSeparator = System.getProperty("path.separator");
        return System.getProperty("java.class.path", ".").split(pathSeparator);
    }

    public void scan(Handler handler) {
        ClassLoader classLoader = getClass().getClassLoader();

        for (String resource : getResourcesInClassPath()) {

            try {
                String className = resource.replace('/', '.');
                className = className.substring(0, className.length() - 6); // Remove the ".class" suffix (6 characters) from the resource name

                Class<?> aClass = Class.forName(className, false, classLoader);
                handler.classFound(aClass);

            } catch (Throwable e) {
                if (e instanceof AssertionFailedError) {
                    fail(e.getMessage());//someone wanted this to fail...
                }
                LOGGER.trace("Failed determining class details for resource: " + resource, e);
            }
        }
    }

    public static interface Handler {
        public void classFound(Class<?> aClass);
    }

    private void findResourcesFromJarFile(List<String> resourceNames, File jarFile) {
        ZipFile zipFile = null;
        try {
            zipFile = new ZipFile(jarFile);

            Enumeration<? extends ZipEntry> zipFileEntries = zipFile.entries();
            while (zipFileEntries.hasMoreElements()) {

                String fileName = zipFileEntries.nextElement().getName();
                resourceNames.add(fileName);
            }

        } catch (Exception e) {
            throw new EjbWithMockitoRunnerException("Failed finding resources in JAR: " + jarFile, e);

        } finally {
            if (zipFile != null) {
                try {
                    zipFile.close();
                } catch (IOException e) {
                    LOGGER.warn("Failed closing a resource", e);
                }
            }
        }
    }

    private void findResourcesFromDirectory(List<String> resourceNames, int rootLength, File directory) {
        File[] fileList = directory.listFiles();
        if (fileList != null) {
            for (File file : fileList) {

                if (file.isDirectory()) {
                    findResourcesFromDirectory(resourceNames, rootLength, file);

                } else {
                    String fileName = file.getAbsolutePath().substring(rootLength).replace('\\', '/');
                    resourceNames.add(fileName);
                }
            }
        }
    }

}
