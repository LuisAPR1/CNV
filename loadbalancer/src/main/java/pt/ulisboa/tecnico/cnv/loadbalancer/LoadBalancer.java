package pt.ulisboa.tecnico.cnv.loadbalancer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;

/**
 * Load Balancer — entry point of the Nature@Cloud system.
 * Receives all HTTP requests and forwards them to available workers.
 * Also runs the Auto Scaler in a background thread.
 *
 * Usage: java LoadBalancer [lb_port] [worker_host:port ...]
 * Example: java LoadBalancer 8080 localhost:8001 localhost:8002
 */
public class LoadBalancer {

    private static final int DEFAULT_PORT = 8080;
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final WorkerPool workerPool;
    private final AutoScaler autoScaler;

    public LoadBalancer(WorkerPool workerPool) {
        this.workerPool = workerPool;
        this.autoScaler = new AutoScaler(workerPool);
    }

    public void start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", new ForwardHandler());
        server.start();

        System.out.println("[LoadBalancer] Listening on port " + port);
        System.out.println("[LoadBalancer] Workers: " + workerPool.size());

        // Start auto scaler in background.
        autoScaler.start();
    }

    /**
     * Handler that forwards requests to workers.
     */
    private class ForwardHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // Handling CORS.
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, OPTIONS");
                exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type,Authorization");
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            String path = exchange.getRequestURI().getRawPath();
            String query = exchange.getRequestURI().getRawQuery();

            // Only forward workload paths.
            if (!path.equals("/fractals") && !path.equals("/dna") && !path.equals("/grayscott")) {
                String msg = "Load Balancer - Nature@Cloud\nWorkers: " + workerPool.size() + "\n";
                for (WorkerPool.Worker w : workerPool.getWorkers()) {
                    msg += "  " + w.toString() + "\n";
                }
                byte[] bytes = msg.getBytes();
                exchange.sendResponseHeaders(200, bytes.length);
                OutputStream os = exchange.getResponseBody();
                os.write(bytes);
                os.close();
                return;
            }

            // Try forwarding with retry on failure.
            int maxRetries = Math.min(3, workerPool.size());
            java.util.Set<WorkerPool.Worker> triedWorkers = new java.util.HashSet<>();
            boolean success = false;

            for (int attempt = 0; attempt < maxRetries && !success; attempt++) {
                // Select least-loaded worker not yet tried.
                WorkerPool.Worker worker = workerPool.selectLeastLoadedExcluding(triedWorkers);
                if (worker == null) {
                    break;
                }
                triedWorkers.add(worker);

                // Build target URL.
                String targetUrl = worker.getBaseUrl() + path;
                if (query != null && !query.isEmpty()) {
                    targetUrl += "?" + query;
                }

                System.out.println("[LoadBalancer] Forwarding " + path + " -> " + worker + " (attempt " + (attempt + 1) + ")");

                worker.incrementActive();
                try {
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(targetUrl))
                            .timeout(Duration.ofSeconds(120))
                            .GET()
                            .build();

                    HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

                    // Return worker's response to client.
                    byte[] body = response.body();
                    exchange.sendResponseHeaders(response.statusCode(), body.length);
                    OutputStream os = exchange.getResponseBody();
                    os.write(body);
                    os.close();
                    success = true;

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    sendError(exchange, 500, "Request interrupted");
                    return;
                } catch (Exception e) {
                    System.err.println("[LoadBalancer] Worker " + worker + " failed: " + e.getMessage());
                } finally {
                    worker.decrementActive();
                }
            }

            if (!success) {
                sendError(exchange, 502, "All workers unreachable after " + maxRetries + " attempts");
            }
        }

        private void sendError(HttpExchange exchange, int code, String message) throws IOException {
            String error = "{ \"error\": \"" + message + "\" }";
            byte[] bytes = error.getBytes();
            exchange.sendResponseHeaders(code, bytes.length);
            OutputStream os = exchange.getResponseBody();
            os.write(bytes);
            os.close();
        }
    }

    public static void main(String[] args) throws Exception {
        int port = DEFAULT_PORT;
        WorkerPool pool = new WorkerPool();

        if (args.length >= 1) {
            port = Integer.parseInt(args[0]);
        }

        // Remaining args are worker addresses (host:port).
        if (args.length >= 2) {
            for (int i = 1; i < args.length; i++) {
                String[] parts = args[i].split(":");
                String host = parts[0];
                int workerPort = Integer.parseInt(parts[1]);
                pool.addWorker(host, workerPort);
            }
        } else {
            // Default: single local worker on port 8000.
            pool.addWorker("localhost", 8000);
        }

        LoadBalancer lb = new LoadBalancer(pool);
        lb.start(port);
    }
}
