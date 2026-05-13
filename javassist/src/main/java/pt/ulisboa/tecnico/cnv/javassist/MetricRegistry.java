package pt.ulisboa.tecnico.cnv.javassist;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Thread-local metric collection for instrumented request handling.
 * Each worker thread accumulates metrics during request processing.
 */
public class MetricRegistry {

    /**
     * Holds per-request metrics for the current thread.
     */
    public static class RequestMetrics {
        private long methodCallCount = 0;
        private long basicBlockCount = 0;
        private long startTime = 0;
        private String requestId = "";

        public void reset(String requestId) {
            this.requestId = requestId;
            this.methodCallCount = 0;
            this.basicBlockCount = 0;
            this.startTime = System.nanoTime();
        }

        public long getMethodCallCount() { return methodCallCount; }
        public long getBasicBlockCount() { return basicBlockCount; }
        public String getRequestId() { return requestId; }
        public long getElapsedTimeMs() { return (System.nanoTime() - startTime) / 1_000_000; }

        @Override
        public String toString() {
            return String.format("[%s] methods=%d, basicblocks=%d, time=%dms",
                    requestId, methodCallCount, basicBlockCount, getElapsedTimeMs());
        }
    }

    private static final ThreadLocal<RequestMetrics> threadMetrics =
            ThreadLocal.withInitial(RequestMetrics::new);

    /**
     * Storage for completed request metrics (requestId -> metrics snapshot).
     */
    private static final ConcurrentHashMap<String, String> completedMetrics = new ConcurrentHashMap<>();

    /**
     * Called at the start of request handling (e.g., handle() method entry).
     */
    public static void startRequest(String requestId) {
        threadMetrics.get().reset(requestId);
    }

    /**
     * Called by instrumented code at each method entry.
     */
    public static void incrementMethodCalls() {
        threadMetrics.get().methodCallCount++;
    }

    /**
     * Called by instrumented code at each basic block entry.
     */
    public static void incrementBasicBlocks(long count) {
        threadMetrics.get().basicBlockCount += count;
    }

    /**
     * Called at the end of request handling. Logs and stores the metrics.
     */
    public static void stopRequest() {
        RequestMetrics m = threadMetrics.get();
        String summary = m.toString();
        System.out.println("[Metrics] " + summary);
        completedMetrics.put(m.getRequestId(), summary);
    }

    /**
     * Returns the current thread's metrics (for inspection/debugging).
     */
    public static RequestMetrics getCurrentMetrics() {
        return threadMetrics.get();
    }

    /**
     * Returns all completed metrics.
     */
    public static Map<String, String> getCompletedMetrics() {
        return completedMetrics;
    }
}
