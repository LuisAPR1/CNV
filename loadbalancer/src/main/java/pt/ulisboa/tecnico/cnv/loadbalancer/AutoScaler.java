package pt.ulisboa.tecnico.cnv.loadbalancer;

import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.IamInstanceProfileSpecification;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.Reservation;
import com.amazonaws.services.ec2.model.RunInstancesRequest;
import com.amazonaws.services.ec2.model.RunInstancesResult;
import com.amazonaws.services.ec2.model.Tag;
import com.amazonaws.services.ec2.model.TagSpecification;
import com.amazonaws.services.ec2.model.TerminateInstancesRequest;

import java.util.Base64;
import java.util.Collections;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Auto Scaler — monitoriza a carga dos workers e ajusta o número de
 * instâncias EC2 activas.
 *
 * Dois modos de operação:
 *   1. <b>Local mode</b>: sem credenciais AWS ou {@link AwsConfig#isAwsScalingEnabled()}
 *      = false. Apenas regista decisões nos logs (útil para desenvolvimento local).
 *   2. <b>AWS mode</b>: lança/termina instâncias EC2 via SDK.
 *
 * Estratégia simples (calibrável):
 *   - Se avgLoad &gt; SCALE_UP_THRESHOLD durante 1 verificação consecutiva → +1 worker
 *   - Se avgLoad &lt; SCALE_DOWN_THRESHOLD e numWorkers &gt; MIN_WORKERS → -1 worker
 *   - Cooldown de 60s entre acções para evitar oscilação.
 *   - Cap de 5 workers para não explodir custos durante testes.
 */
public class AutoScaler {

    private static final int CHECK_INTERVAL_SECONDS = 5;
    private static final double SCALE_UP_THRESHOLD = 1.0;
    private static final double SCALE_DOWN_THRESHOLD = 0.25;
    private static final int MIN_WORKERS = 1;
    private static final int MAX_WORKERS = 5;
    private static final long COOLDOWN_MS = 60_000;

    /**
     * User-data executado pela EC2 worker no primeiro boot.
     * A AMI worker (criada por 03-create-ami.sh) já contém Java, os JARs
     * em /opt/cnv e o systemd unit cnv-worker.service enabled. Este
     * user-data serve apenas como marcador para depuração no cloud-init.
     */
    private static final String WORKER_USER_DATA =
            "#!/bin/bash\n" +
            "exec > /var/log/cnv-bootstrap.log 2>&1\n" +
            "echo \"[$(date -Is)] AutoScaler-managed worker boot — JARs já na AMI.\"\n";

    private final WorkerPool workerPool;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "AutoScaler");
        t.setDaemon(true);
        return t;
    });

    private AmazonEC2 ec2;
    private final boolean awsMode;
    private volatile long lastScalingAction = 0;

    public AutoScaler(WorkerPool workerPool) {
        this.workerPool = workerPool;
        this.awsMode = initAwsClient();
        // Registar callback: quando um worker falhar health checks e for evicto pelo
        // WorkerPool, terminar a EC2 (se for gerida) para evitar instâncias órfãs.
        workerPool.setOnUnhealthyEviction(this::handleUnhealthyEviction);
    }

    /**
     * Chamado pelo {@link WorkerPool} quando um worker é evicto por health-check
     * (3 falhas consecutivas). Se a instância foi lançada pelo AutoScaler
     * (tem {@code instanceId}), tenta terminá-la na AWS para libertar recursos.
     */
    private void handleUnhealthyEviction(WorkerPool.Worker w) {
        if (!awsMode || w.getInstanceId() == null) {
            System.out.println("[AutoScaler] Eviction de worker manual/local — não há EC2 a terminar: " + w);
            return;
        }
        try {
            ec2.terminateInstances(new TerminateInstancesRequest()
                    .withInstanceIds(w.getInstanceId()));
            System.out.println("[AutoScaler] EC2 " + w.getInstanceId()
                    + " terminada por health-check eviction.");
        } catch (Exception e) {
            System.err.println("[AutoScaler] Falha a terminar EC2 " + w.getInstanceId()
                    + " após eviction: " + e.getMessage());
        }
    }

    private boolean initAwsClient() {
        if (!AwsConfig.isAwsScalingEnabled()) {
            System.out.println("[AutoScaler] AWS scaling DESACTIVADO (AMI ou SG não configurados). "
                    + "Modo local — apenas logs.");
            System.out.println("[AutoScaler] " + AwsConfig.summary());
            return false;
        }
        try {
            this.ec2 = AmazonEC2ClientBuilder.standard()
                    .withRegion(AwsConfig.REGION)
                    .build();
            // Sanity check: chamar uma API leve.
            ec2.describeRegions();
            System.out.println("[AutoScaler] AWS scaling ACTIVO. " + AwsConfig.summary());
            return true;
        } catch (Exception e) {
            System.err.println("[AutoScaler] Falha a inicializar cliente EC2: " + e.getMessage()
                    + ". A correr em modo local.");
            return false;
        }
    }

    public void start() {
        // Em modo AWS, descobrir EC2s já a correr com tag Role=worker e adoptá-las
        // no pool — evita criar workers órfãos quando o LB arranca a meio do dia,
        // ou quando o operador lançou workers manualmente antes do LB.
        if (awsMode) {
            try {
                discoverExistingWorkers();
            } catch (Exception e) {
                System.err.println("[AutoScaler] discoverExistingWorkers falhou: " + e.getMessage());
            }
        }
        scheduler.scheduleAtFixedRate(this::checkAndScale,
                CHECK_INTERVAL_SECONDS, CHECK_INTERVAL_SECONDS, TimeUnit.SECONDS);
        System.out.println("[AutoScaler] Iniciado. Verificação a cada " + CHECK_INTERVAL_SECONDS + "s");
    }

    public void stop() {
        scheduler.shutdown();
    }

    private void checkAndScale() {
        try {
            int numWorkers = workerPool.size();
            int totalActive = 0;
            for (WorkerPool.Worker w : workerPool.getWorkers()) {
                totalActive += w.getActiveRequests();
            }
            double avgLoad = numWorkers == 0 ? Double.POSITIVE_INFINITY
                    : (double) totalActive / numWorkers;

            System.out.printf("[AutoScaler] Workers=%d, TotalActive=%d, AvgLoad=%.2f%n",
                    numWorkers, totalActive, avgLoad);

            // Cooldown — não fazer scaling demasiado depressa.
            if (System.currentTimeMillis() - lastScalingAction < COOLDOWN_MS) {
                return;
            }

            if (numWorkers < MIN_WORKERS) {
                System.out.println("[AutoScaler] Abaixo do mínimo (" + MIN_WORKERS + ") — SCALE UP forçado.");
                scaleUp();
            } else if (avgLoad > SCALE_UP_THRESHOLD && numWorkers < MAX_WORKERS) {
                System.out.println("[AutoScaler] SCALE UP (avgLoad=" + avgLoad + " > " + SCALE_UP_THRESHOLD + ")");
                scaleUp();
            } else if (avgLoad < SCALE_DOWN_THRESHOLD && numWorkers > MIN_WORKERS) {
                System.out.println("[AutoScaler] SCALE DOWN (avgLoad=" + avgLoad + " < " + SCALE_DOWN_THRESHOLD + ")");
                scaleDown();
            }
        } catch (Exception e) {
            System.err.println("[AutoScaler] Erro na verificação: " + e.getMessage());
        }
    }

    // ───────────────────────────────────────────────────────────────
    //  SCALE UP
    // ───────────────────────────────────────────────────────────────

    private void scaleUp() {
        if (!awsMode) {
            System.out.println("[AutoScaler] (local mode) SCALE UP simulado — sem chamada EC2.");
            return;
        }
        try {
            String userData64 = Base64.getEncoder()
                    .encodeToString(WORKER_USER_DATA.getBytes());

            RunInstancesRequest req = new RunInstancesRequest()
                    .withImageId(AwsConfig.WORKER_AMI_ID)
                    .withInstanceType(AwsConfig.INSTANCE_TYPE)
                    .withMinCount(1).withMaxCount(1)
                    .withKeyName(AwsConfig.KEYPAIR_NAME)
                    .withSecurityGroupIds(AwsConfig.WORKER_SG_ID)
                    .withIamInstanceProfile(new IamInstanceProfileSpecification()
                            .withName(AwsConfig.WORKER_INSTANCE_PROFILE))
                    .withUserData(userData64)
                    .withTagSpecifications(new TagSpecification()
                            .withResourceType("instance")
                            .withTags(
                                    new Tag("Project", "NatureAtCloud"),
                                    new Tag("Role", "worker"),
                                    new Tag("ManagedBy", "AutoScaler")));

            RunInstancesResult res = ec2.runInstances(req);
            Instance inst = res.getReservation().getInstances().get(0);
            String instanceId = inst.getInstanceId();
            System.out.println("[AutoScaler] Instância lançada: " + instanceId + " — a aguardar IP...");

            String privateIp = waitForPrivateIp(instanceId);
            if (privateIp == null) {
                System.err.println("[AutoScaler] Timeout à espera do IP de " + instanceId
                        + ". A terminar a instância.");
                ec2.terminateInstances(new TerminateInstancesRequest()
                        .withInstanceIds(instanceId));
                return;
            }

            workerPool.addWorker(privateIp, AwsConfig.WORKER_PORT, instanceId);
            lastScalingAction = System.currentTimeMillis();
            System.out.println("[AutoScaler] Worker " + instanceId + " @ " + privateIp + " adicionado ao pool.");
            System.out.println("[AutoScaler] systemd cnv-worker.service arrancará automaticamente (~30-60s para servir pedidos).");
        } catch (Exception e) {
            System.err.println("[AutoScaler] Falha no SCALE UP: " + e.getMessage());
        }
    }

    private String waitForPrivateIp(String instanceId) {
        for (int i = 0; i < 60; i++) { // até ~2 minutos
            try {
                DescribeInstancesResult dr = ec2.describeInstances(
                        new DescribeInstancesRequest().withInstanceIds(instanceId));
                for (Reservation r : dr.getReservations()) {
                    for (Instance inst : r.getInstances()) {
                        String ip = inst.getPrivateIpAddress();
                        String state = inst.getState().getName();
                        if ("running".equals(state) && ip != null && !ip.isEmpty()) {
                            return ip;
                        }
                    }
                }
                Thread.sleep(2000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            } catch (Exception e) {
                System.err.println("[AutoScaler] Polling describeInstances falhou: " + e.getMessage());
            }
        }
        return null;
    }

    // ───────────────────────────────────────────────────────────────
    //  SCALE DOWN
    // ───────────────────────────────────────────────────────────────

    private void scaleDown() {
        // Escolher worker menos carregado E gerido pelo AutoScaler (com instanceId).
        WorkerPool.Worker target = null;
        for (WorkerPool.Worker w : workerPool.getWorkers()) {
            if (w.getInstanceId() == null) continue; // não terminar workers manuais
            if (target == null || w.getActiveRequests() < target.getActiveRequests()) {
                target = w;
            }
        }
        if (target == null) {
            System.out.println("[AutoScaler] Não há workers EC2 geridos para terminar.");
            return;
        }

        System.out.println("[AutoScaler] A drenar e terminar worker: " + target);
        workerPool.removeWorker(target); // pára de receber pedidos novos

        // Drenagem: esperar até 30s para pedidos em curso terminarem.
        for (int i = 0; i < 15 && target.getActiveRequests() > 0; i++) {
            try { Thread.sleep(2000); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        if (awsMode) {
            try {
                ec2.terminateInstances(new TerminateInstancesRequest()
                        .withInstanceIds(target.getInstanceId()));
                System.out.println("[AutoScaler] Instância " + target.getInstanceId() + " terminada.");
            } catch (Exception e) {
                System.err.println("[AutoScaler] Falha a terminar " + target.getInstanceId()
                        + ": " + e.getMessage());
            }
        }
        lastScalingAction = System.currentTimeMillis();
    }

    /**
     * Inventário das EC2s já existentes na conta com tag Project=NatureAtCloud,
     * Role=worker e estado running — adopta-as no {@link WorkerPool} para que
     * passem a estar sob gestão do AutoScaler (incluindo scale-down).
     *
     * <p>O método é seguro contra duplicados graças à idempotência do
     * {@link WorkerPool#addWorker(String, int, String)}.
     */
    public void discoverExistingWorkers() {
        if (!awsMode) return;
        DescribeInstancesResult res = ec2.describeInstances(new DescribeInstancesRequest()
                .withFilters(
                        new com.amazonaws.services.ec2.model.Filter("tag:Project", Collections.singletonList("NatureAtCloud")),
                        new com.amazonaws.services.ec2.model.Filter("tag:Role", Collections.singletonList("worker")),
                        new com.amazonaws.services.ec2.model.Filter("instance-state-name", Collections.singletonList("running"))));
        int discovered = 0;
        for (Reservation r : res.getReservations()) {
            for (Instance inst : r.getInstances()) {
                String ip = inst.getPrivateIpAddress();
                if (ip != null) {
                    workerPool.addWorker(ip, AwsConfig.WORKER_PORT, inst.getInstanceId());
                    discovered++;
                }
            }
        }
        System.out.println("[AutoScaler] discoverExistingWorkers: " + discovered
                + " EC2(s) Role=worker running encontradas e adoptadas.");
    }
}
