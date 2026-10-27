// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package probe;

public final class NativeTransportProbe {
    public static void main(String[] args) throws Exception {
        if (!System.getProperty("os.name").equals("Linux")) throw new IllegalStateException("Linux epoll test only");
        run("com.multiclouddb.provider.cosmos.shaded.");
        if (args.length > 0 && args[0].equals("collisions")) run("");
    }

    private static void run(String prefix) throws Exception {
        Class<?> epoll = Class.forName(prefix + "io.netty.channel.epoll.Epoll");
        if (!(boolean) epoll.getMethod("isAvailable").invoke(null)) {
            throw new AssertionError(epoll.getMethod("unavailabilityCause").invoke(null));
        }
        Class<?> groupType = Class.forName(prefix + "io.netty.channel.epoll.EpollEventLoopGroup");
        Object group = groupType.getConstructor(int.class).newInstance(1);
        Class<?> eventExecutor = Class.forName(prefix + "io.netty.util.concurrent.EventExecutorGroup");
        Object executor = eventExecutor.getMethod("next").invoke(group);
        Class<?> singleExecutor = Class.forName(prefix + "io.netty.util.concurrent.EventExecutor");
        java.util.concurrent.CountDownLatch ran = new java.util.concurrent.CountDownLatch(1);
        singleExecutor.getMethod("execute", Runnable.class).invoke(executor, (Runnable) ran::countDown);
        try {
            if (!ran.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Epoll task did not run");
        } finally {
            Object completion = eventExecutor.getMethod("shutdownGracefully", long.class, long.class,
                    java.util.concurrent.TimeUnit.class).invoke(group, 0L, 2L, java.util.concurrent.TimeUnit.SECONDS);
            Class.forName(prefix + "io.netty.util.concurrent.Future").getMethod("sync").invoke(completion);
        }
        System.out.println("PASS real epoll event-loop execution: " + (prefix.isEmpty() ? "application" : "private"));
    }
}
