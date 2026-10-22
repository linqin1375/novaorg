package io.github.tangbailin.novaorg.audit;

import java.util.concurrent.Callable;

/**
 * Minimal trace context.
 *
 * <p>A full OpenTelemetry setup is out of scope, but the property that actually matters during an
 * incident is cheap to get right: <b>every audit line produced while handling one request carries
 * the same trace id</b>. That turns a flat log into something you can group by.
 *
 * <p>Set once per inbound request (see {@code TraceFilter}) and read by {@link AuditEvent} without
 * threading a parameter through the whole call chain.
 */
public final class TraceContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private TraceContext() {
    }

    public static String currentTraceId() {
        String id = CURRENT.get();
        return id == null ? "trace-none" : id;
    }

    public static String start(String traceId) {
        String previous = CURRENT.get();
        CURRENT.set(traceId);
        return previous;
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** Runs a block with a trace id bound, restoring the previous value afterwards. */
    public static <T> T withTrace(String traceId, Callable<T> body) {
        String previous = start(traceId);
        try {
            return body.call();
        } catch (Exception e) {
            throw e instanceof RuntimeException re ? re : new IllegalStateException(e);
        } finally {
            if (previous == null) {
                clear();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
