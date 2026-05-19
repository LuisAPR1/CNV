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
        private final long graceUntilMs; // 0 = sem grace period

        public Worker(String host, int port) {
            this(host, port, null, 0L);
        }

        public Worker(String host, int port, String instanceId) {
            this(host, port, instanceId, 0L);
        }

        public Worker(String host, int port, String instanceId, long graceMs) {
            this.host = host;
            this.port = port;
            this.instanceId = instanceId;
            this.graceUntilMs = graceMs > 0L ? System.currentTimeMillis() + graceMs : 0L;
        }

        public String getHost() { return host; }
        public int getPort() { return port; }
        public String getInstanceId() { return instanceId; }

        /**
         * Indica se o worker ainda está no período de grace inicial (a aguardar
         * bootstrap do systemd cnv-worker.service). O health checker deve
         * ignorar workers em grace para evitar removê-los antes de estarem
         * prontos a servir pedidos.
         */
        public boolean isInGracePeriod() {
            return graceUntilMs > 0L && System.currentTimeMillis() < graceUntilMs;
        }

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

    public void addWorker(String host, int port) {
        addWorker(host, port, null, 0L);
    }

    public Worker addWorker(String host, int port, String instanceId) {
        return addWorker(host, port, instanceId, 0L);
    }

    /**
     * Adiciona um worker com um período de grace inicial (em ms). Durante
     * esse período, o health checker não conta falhas — útil para evitar
     * que um worker EC2 recém-lançado seja removido enquanto o systemd
     * ainda está a arrancar o serviço HTTP.
     */
    public Worker addWorker(String host, int port, String instanceId, long graceMs) {
        Worker w = new Worker(host, port, instanceId, graceMs);
        workers.add(w);
        String graceInfo = graceMs > 0L ? " (grace=" + (graceMs / 1000) + "s)" : "";
        System.out.println("[WorkerPool] Added worker: " + w + graceInfo);
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
                if (w.isInGracePeriod()) {
                    // Worker ainda a arrancar (systemd cnv-worker). Não contar falhas.
                    continue;
                }
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
