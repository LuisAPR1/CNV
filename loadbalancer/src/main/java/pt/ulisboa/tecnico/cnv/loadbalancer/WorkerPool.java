package pt.ulisboa.tecnico.cnv.loadbalancer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages a pool of worker instances and selects which worker handles each request.
 * Currently uses round-robin selection. Will be extended with complexity-based routing.
 */
public class WorkerPool {

    public static class Worker {
        private final String host;
        private final int port;
        private final AtomicInteger activeRequests = new AtomicInteger(0);

        public Worker(String host, int port) {
            this.host = host;
            this.port = port;
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
            return host + ":" + port + " (active=" + activeRequests.get() + ")";
        }
    }

    private final CopyOnWriteArrayList<Worker> workers = new CopyOnWriteArrayList<>();
    private final AtomicInteger roundRobinIndex = new AtomicInteger(0);

    public void addWorker(String host, int port) {
        workers.add(new Worker(host, port));
        System.out.println("[WorkerPool] Added worker: " + host + ":" + port);
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
