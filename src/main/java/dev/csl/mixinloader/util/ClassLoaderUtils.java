package dev.csl.mixinloader.util;

import jdk.internal.loader.URLClassPath;

import java.lang.reflect.Field;
import java.net.URL;

public class ClassLoaderUtils {
    public static URLClassPath getURLClassPath(ClassLoader classLoader) {
        Class<?> clazz = classLoader.getClass();
        while (clazz != null && clazz != Object.class) {
            try {
                Field f = clazz.getDeclaredField("ucp");
                f.setAccessible(true);
                return (URLClassPath) f.get(classLoader);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            } catch (Exception e) {
                throw new RuntimeException("Failed to expose ucp from " + classLoader.getClass().getName(), e);
            }
        }
        throw new RuntimeException("No 'ucp' field found in classloader hierarchy: " + classLoader.getClass().getName());
    }

    public static void addURL(ClassLoader classLoader, URL url) {
        if (classLoader == null || url == null) return;
        getURLClassPath(classLoader).addURL(url);
    }
}