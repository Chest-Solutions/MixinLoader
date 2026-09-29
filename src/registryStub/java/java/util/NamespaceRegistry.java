package java.util;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

public final class NamespaceRegistry {
    public static final Map<String, ClassLoader> NAMESPACE_REGISTRY = new ConcurrentHashMap<>();
    private static final MethodHandle FIND_CLASS, FIND_LOADED_CLASS;

    static { try {
        final Method mFindClass = ClassLoader.class.getDeclaredMethod("findClass", String.class);
        final Method mFindLoadedClass = ClassLoader.class.getDeclaredMethod("findLoadedClass", String.class);
        mFindClass.setAccessible(true); mFindLoadedClass.setAccessible(true);
        FIND_CLASS = MethodHandles.lookup().unreflect(mFindClass);
        FIND_LOADED_CLASS = MethodHandles.lookup().unreflect(mFindLoadedClass);
    } catch (final Throwable t) { throw new ExceptionInInitializerError(t); } }

    public static Class<?> findClassFromNamespaceRegistry(final ClassLoader requester, final String name) throws ClassNotFoundException {
        final int lastDot = name.lastIndexOf('.');
        if (lastDot > 0) {
            final String pkg = name.substring(0, lastDot);
            final ClassLoader owner = NAMESPACE_REGISTRY.get(pkg);

            if (owner != null && owner != requester) {
                try {
                    if (FIND_LOADED_CLASS != null) {
                        final Class<?> loaded = (Class<?>) FIND_LOADED_CLASS.invoke(owner, name);
                        if (loaded != null) return loaded;
                    }
                    if (FIND_CLASS != null) return (Class<?>) FIND_CLASS.invoke(owner, name);
                }
                catch (final ClassNotFoundException e) { throw e; }
                catch (final Throwable ignored) {}
            }
        }
        throw new ClassNotFoundException(name);
    }
}
