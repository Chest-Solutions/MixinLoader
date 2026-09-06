package dev.csl.mixinloader.util;

public class SneakyExceptions {
    @SuppressWarnings("unchecked")
    public static <E extends Throwable> void sneakyThrow(Throwable e) throws E {
        throw (E) e;
    }

    public static <T> T get(ThrowingSupplier<T> supplier) {
        try {
            return supplier.get();
        } catch (Throwable t) {
            sneakyThrow(t);
            return null;
        }
    }

    public static void run(ThrowingRunnable runnable) {
        try {
            runnable.run();
        } catch (Throwable t) {
            sneakyThrow(t);
        }
    }

    @FunctionalInterface
    public interface ThrowingSupplier<T> {
        T get() throws Throwable;
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Throwable;
    }
}
