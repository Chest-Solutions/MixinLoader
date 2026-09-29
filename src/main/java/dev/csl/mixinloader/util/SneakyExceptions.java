package dev.csl.mixinloader.util;

public class SneakyExceptions {
    @FunctionalInterface
    public interface ThrowingSupplier<T> {
        T get() throws Throwable;
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Throwable;
    }

    @SuppressWarnings("unchecked")
    public static <E extends Throwable> void sneakyThrow(final Throwable e) throws E {
        throw (E) e;
    }

    public static <T> T get(final ThrowingSupplier<T> supplier) {
        try {
            return supplier.get();
        } catch (final Throwable t) {
            sneakyThrow(t);
            return null;
        }
    }

    public static void run(final ThrowingRunnable runnable) {
        try {
            runnable.run();
        } catch (final Throwable t) {
            sneakyThrow(t);
        }
    }
}
