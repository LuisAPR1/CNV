package pt.ulisboa.tecnico.cnv.loadbalancer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Manages a pool of worker instances and selects which worker handles each request.
 * Currently uses round-robin selection. Will be extended with complexity-based routing.
 */
public class WorkerPool {

    public static class Worker {
        private final String host;
        private final int port;
        private final String instanceId; // null quando o worker é local/manual
        private final AtomicInteger activeRequests = new AtomicInteger(0);

        public Worker(String host, int port) {
            this(host, port, null);
        }

        public Worker(String host, int port, String instanceId) {
            this.host = host;
            this.port = port;
            this.instanceId = instanceId;
        }

        public String getHost() { return host; }
        public int getPort() { return port; }
        public String getInstanceId() { return instanceId; }

        public String getBaseUrl() {
            return "http://" + host + ":" + port;
        }

        public int getActiveRequests() {
            return activeRequests.get();
        }

        public void incrementActive() {
            activeRequests.incrementAndGet();
        }

        public void decrementActive() {
            activeRequests.decrementAndGet();
        }

        /**
         * Health check: try to reach the worker's root endpoint.
         */
        public boolean isHealthy() {
            try {
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(2))
                        .build();
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(getBaseUrl() + "/"))
                        .timeout(Duration.ofSeconds(2))
                        .GET()
                        .build();
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                return resp.statusCode() == 200;
            } catch (Exception e) {
                return false;
            }
        }

        @Override
        public String toString() {
            String id = instanceId != null ? " [" + instanceId + "]" : "";
            return host + ":" + port + id + " (active=" + activeRequests.get() + ")";
        }
    }

    private final CopyOnWriteArrayList<Worker> workers = new CopyOnWriteArrayList<>();
    private final AtomicInteger roundRobinIndex = new AtomicInteger(0);

    // Health checking state.
    private static final int HEALTH_CHECK_INTERVAL_SECONDS = 15;
    private static final int FAILURES_BEFORE_REMOVAL = 3;
    private final ConcurrentHashMap<Worker, Integer> consecutiveFailures = new ConcurrentHashMap<>();
    private ScheduledExecutorService healthChecker;

    /**
     * Callback invoked when a worker is evicted by the health-checker (i.e. failed
     * {@value #FAILURES_BEFORE_REMOVAL} consecutive checks). The AutoScaler registers
     * here to also terminate the corresponding EC2 instance, avoiding orphan
     * instances when an app crashes but the VM is still running.
     *
     * <p>NOT invoked by {@link #removeWorker(Worker)} (which is the manual / scale-down
     * path; the caller is responsible for any cleanup).
     */
    private volatile Consumer<Worker> onUnhealthyEviction;

    public void setOnUnhealthyEviction(Consumer<Worker> callback) {
        this.onUnhealthyEviction = callback;
    }

    public void addWorker(String host, int port) {
        addWorker(host, port, null);
    }

    /**
     * Idempotent by (host, port): if a worker with the same endpoint is already in the
     * pool, returns the existing entry instead of adding a duplicate. This is essential
     * when {@code discoverExistingWorkers()} runs alongside explicit args on the LB
     * command line.
     */
    public Worker addWorker(String host, int port, String instanceId) {
        for (Worker existing : workers) {
            if (existing.getHost().equals(host) && existing.getPort() == port) {
                System.out.println("[WorkerPool] Skip duplicate (already present): " + existing);
                return existing;
            }
        }
        Worker w = new Worker(host, port, instanceId);
        workers.add(w);
        System.out.println("[WorkerPool] Added worker: " + w);
        return w;
    }

    public void removeWorker(Worker worker) {
        workers.remove(worker);
        System.out.println("[WorkerPool] Removed worker: " + worker);
    }

    public List<Worker> getWorkers() {
        return workers;
    }

    public int size() {
        return workers.size();
    }

    /**
     * Select the next worker using round-robin.
     * Returns null if no workers are available.
     */
    public Worker selectWorker() {
        if (workers.isEmpty()) {
            return null;
        }
        int idx = roundRobinIndex.getAndIncrement() % workers.size();
        return workers.get(Math.abs(idx));
    }

    /**
     * Select the worker with the fewest active requests (least-loaded).
     * Returns null if no workers are available.
     */
    public Worker selectLeastLoaded() {
        if (workers.isEmpty()) {
            return null;
        }
        Worker best = workers.get(0);
        for (Worker w : workers) {
            if (w.getActiveRequests() < best.getActiveRequests()) {
                best = w;
            }
        }
        return best;
    }

    /**
     * Start a periodic health-checker that pings each worker every
     * {@value #HEALTH_CHECK_INTERVAL_SECONDS} seconds and removes any worker
     * that fails {@value #FAILURES_BEFORE_REMOVAL} consecutive checks.
     *
     * <p>Idempotent: subsequent calls are no-ops.
     */
    public synchronized void startHealthChecks() {
        if (healthChecker != null) return;
        healthChecker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "WorkerPool-HealthCheck");
            t.setDaemon(true);
            return t;
        });
        healthChecker.scheduleAtFixedRate(this::runHealthChecks,
                HEALTH_CHECK_INTERVAL_SECONDS, HEALTH_CHECK_INTERVAL_SECONDS, TimeUnit.SECONDS);
        System.out.println("[WorkerPool] Health checks started (interval="
                + HEALTH_CHECK_INTERVAL_SECONDS + "s, failuresBeforeRemoval="
                + FAILURES_BEFORE_REMOVAL + ")");
    }

    public synchronized void stopHealthChecks() {
        if (healthChecker != null) {
            healthChecker.shutdownNow();
            healthChecker = null;
        }
    }

    private void runHealthChecks() {
        // Snapshot to avoid surprises if workers are mutated mid-iteration.
        for (Worker w : workers) {
            try {
                if (w.isHealthy()) {
                    if (consecutiveFailures.remove(w) != null) {
                        System.out.println("[WorkerPool] Health recovered: " + w);
                    }
                } else {
                    int fails = consecutiveFailures.merge(w, 1, Integer::sum);
                    System.out.println("[WorkerPool] Health check FAILED (" + fails + "/"
                            + FAILURES_BEFORE_REMOVAL + "): " + w);
                    if (fails >= FAILURES_BEFORE_REMOVAL) {
                        System.out.println("[WorkerPool] Removing unhealthy worker: " + w);
                        removeWorker(w);
                        consecutiveFailures.remove(w);
                        Consumer<Worker> cb = onUnhealthyEviction;
                        if (cb != null) {
                            try {
                                cb.accept(w);
                            } catch (Exception cbErr) {
                                System.err.println("[WorkerPool] Eviction callback failed for "
                                        + w + ": " + cbErr.getMessage());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("[WorkerPool] Health-check error for " + w + ": " + e.getMessage());
            }
        }
    }

    /**
     * Select the least-loaded worker excluding a set of already-tried workers.
     * Used for retry logic — avoids sending to a worker that already failed.
     * Returns null if no eligible workers are available.
     */
    public Worker selectLeastLoadedExcluding(Set<Worker> excluded) {
        Worker best = null;
        for (Worker w : workers) {
            if (excluded.contains(w)) {
                continue;
            }
            if (best == null || w.getActiveRequests() < best.getActiveRequests()) {
                best = w;
            }
        }
        return best;
    }
}
