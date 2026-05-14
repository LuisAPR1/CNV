package pt.ulisboa.tecnico.cnv.loadbalancer;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Auto Scaler — monitors worker load and adjusts the number of active workers.
 *
 * Current simplified strategy (local testing):
 * - Periodically logs worker load status.
 * - Flags when workers are overloaded (avg active requests > threshold).
 *
 * For AWS deployment, this will be extended to:
 * - Launch new EC2 instances when overloaded.
 * - Terminate idle EC2 instances to save costs.
 */
public class AutoScaler {

    private static final int CHECK_INTERVAL_SECONDS = 10;
    private static final double SCALE_UP_THRESHOLD = 3.0;
    private static final double SCALE_DOWN_THRESHOLD = 0.5;

    private final WorkerPool workerPool;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "AutoScaler");
        t.setDaemon(true);
        return t;
    });

    public AutoScaler(WorkerPool workerPool) {
        this.workerPool = workerPool;
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::checkAndScale, CHECK_INTERVAL_SECONDS, CHECK_INTERVAL_SECONDS, TimeUnit.SECONDS);
        System.out.println("[AutoScaler] Started. Checking every " + CHECK_INTERVAL_SECONDS + "s");
    }

    public void stop() {
        scheduler.shutdown();
    }

    private void checkAndScale() {
        try {
            int numWorkers = workerPool.size();
            if (numWorkers == 0) {
                System.out.println("[AutoScaler] WARNING: No workers available!");
                // TODO: In AWS mode, launch a new EC2 instance here.
                return;
            }

            int totalActive = 0;
            for (WorkerPool.Worker w : workerPool.getWorkers()) {
                totalActive += w.getActiveRequests();
            }
            double avgLoad = (double) totalActive / numWorkers;

            System.out.printf("[AutoScaler] Workers=%d, TotalActive=%d, AvgLoad=%.1f%n",
                    numWorkers, totalActive, avgLoad);

            if (avgLoad > SCALE_UP_THRESHOLD) {
                System.out.println("[AutoScaler] SCALE UP needed (avgLoad=" + avgLoad + " > " + SCALE_UP_THRESHOLD + ")");
                // TODO: In AWS mode, launch a new EC2 instance.
                // For now, just log the decision.
            } else if (avgLoad < SCALE_DOWN_THRESHOLD && numWorkers > 1) {
                System.out.println("[AutoScaler] SCALE DOWN possible (avgLoad=" + avgLoad + " < " + SCALE_DOWN_THRESHOLD + ")");
                // TODO: In AWS mode, terminate an idle EC2 instance.
                // For now, just log the decision.
            }
        } catch (Exception e) {
            System.err.println("[AutoScaler] Error during check: " + e.getMessage());
        }
    }
}
