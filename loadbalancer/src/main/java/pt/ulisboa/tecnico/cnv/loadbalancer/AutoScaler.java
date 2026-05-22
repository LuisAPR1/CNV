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
 * Estratégia (calibrável):
 *   - Usa estimatedWork (soma dos custos estimados pelo ComplexityEstimator)
 *     em vez de contagem bruta de pedidos ativos.
 *   - Se avgEstimatedWork &gt; ESTIMATED_WORK_THRESHOLD → +1 worker
 *   - Se avgEstimatedWork &lt; ESTIMATED_WORK_THRESHOLD / 4 e numWorkers &gt; MIN_WORKERS → -1 worker
 *   - Cooldown de 60s entre acções para evitar oscilação.
 *   - Cap de 5 workers para não explodir custos durante testes.
 */
public class AutoScaler {

    private static final int CHECK_INTERVAL_SECONDS = 5;
    private static final int MIN_WORKERS = 1;
    private static final int MAX_WORKERS = 5;
    private static final long COOLDOWN_MS = 60_000;

    /**
     * Limiar de trabalho estimado por worker (em <b>instruções bytecode</b>, ICount).
     *
     * <p>Calibração 2026-05-21 baseada em medições empíricas locais (ver
     * {@code docs/01.6_calibration_evidence.md}):
     * <ul>
     *   <li>Fractals 1000×1000×500 (heavy): ~880M instr.</li>
     *   <li>GrayScott 256×1000 (heavy): ~10.8B instr.</li>
     *   <li>GrayScott 128×2000 (medium): ~5.4B instr.</li>
     * </ul>
     *
     * <p>Limiar fixado em <b>5×10⁹</b> → scale-up quando avg ≈ 1 pedido medium
     * por worker. Scale-down em {@code THRESHOLD/4} = 1.25×10⁹ (worker
     * essencialmente idle, com no máximo 1 pedido leve).
     */
    private static final long ESTIMATED_WORK_THRESHOLD = 5_000_000_000L;

    /**
     * Tempo máximo (em iterações de 2s) que o scale-down espera por drenagem
     * antes de adiar a terminação. 15 × 2s = 30s de drenagem total.
     */
    private static final int DRAIN_POLL_ITERATIONS = 15;
    private static final long DRAIN_POLL_INTERVAL_MS = 2000;

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
            long totalEstimatedWork = 0;
            int totalActive = 0;
            for (WorkerPool.Worker w : workerPool.getWorkers()) {
                totalEstimatedWork += w.getEstimatedWork();
                totalActive += w.getActiveRequests();
            }
            long avgEstimatedWork = numWorkers == 0 ? Long.MAX_VALUE
                    : totalEstimatedWork / numWorkers;

            System.out.printf("[AutoScaler] Workers=%d, TotalActive=%d, TotalEstWork=%d, AvgEstWork=%d%n",
                    numWorkers, totalActive, totalEstimatedWork, avgEstimatedWork);

            // Cooldown — não fazer scaling demasiado depressa.
            if (System.currentTimeMillis() - lastScalingAction < COOLDOWN_MS) {
                return;
            }

            if (numWorkers < MIN_WORKERS) {
                System.out.println("[AutoScaler] Abaixo do mínimo (" + MIN_WORKERS + ") — SCALE UP forçado.");
                scaleUp();
            } else if (avgEstimatedWork > ESTIMATED_WORK_THRESHOLD && numWorkers < MAX_WORKERS) {
                System.out.println("[AutoScaler] SCALE UP (avgEstWork=" + avgEstimatedWork
                        + " > " + ESTIMATED_WORK_THRESHOLD + ")");
                scaleUp();
            } else if (avgEstimatedWork < ESTIMATED_WORK_THRESHOLD / 4 && numWorkers > MIN_WORKERS) {
                System.out.println("[AutoScaler] SCALE DOWN (avgEstWork=" + avgEstimatedWork
                        + " < " + (ESTIMATED_WORK_THRESHOLD / 4) + ")");
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

    /**
     * Termina seguramente um worker EC2: drena pedidos em curso ANTES de chamar
     * {@code terminateInstances}. Se a drenagem não terminar dentro do tempo
     * máximo, o scale-down é <b>adiado</b> (não há terminação à força) — o
     * worker é re-adicionado ao pool e o AutoScaler tentará novamente no
     * próximo ciclo, quando porventura já estiver vazio.
     *
     * <p>Esta política substitui a versão anterior (que matava a EC2 mesmo
     * com {@code activeRequests > 0}, perdendo pedidos a meio). Ver
     * {@code docs/02.2_safe_scale_down.md}.
     */
    private void scaleDown() {
        // Escolher worker menos carregado E gerido pelo AutoScaler (com instanceId).
        WorkerPool.Worker target = null;
        for (WorkerPool.Worker w : workerPool.getWorkers()) {
            if (w.getInstanceId() == null) continue; // não terminar workers manuais
            if (target == null || w.getEstimatedWork() < target.getEstimatedWork()) {
                target = w;
            }
        }
        if (target == null) {
            System.out.println("[AutoScaler] Não há workers EC2 geridos para terminar.");
            return;
        }

        System.out.println("[AutoScaler] Candidato a scale-down: " + target
                + " — a iniciar drenagem.");
        workerPool.removeWorker(target); // pára de receber pedidos novos

        // Drenagem com polling: aguarda até DRAIN_POLL_ITERATIONS × DRAIN_POLL_INTERVAL_MS.
        for (int i = 0; i < DRAIN_POLL_ITERATIONS && target.getActiveRequests() > 0; i++) {
            try {
                Thread.sleep(DRAIN_POLL_INTERVAL_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        // Se ainda há pedidos activos: ADIAR (não terminar à força).
        if (target.getActiveRequests() > 0) {
            System.out.println("[AutoScaler] Scale-down ADIADO: " + target
                    + " ainda tem " + target.getActiveRequests()
                    + " pedidos ativos após " + (DRAIN_POLL_ITERATIONS * DRAIN_POLL_INTERVAL_MS / 1000)
                    + "s de drenagem. A re-adicionar ao pool — retry no próximo ciclo.");
            // Re-adicionar para o LB poder voltar a usar o worker entretanto.
            workerPool.addWorker(target.getHost(), target.getPort(), target.getInstanceId());
            // NÃO actualizar lastScalingAction: queremos o cooldown a contar do
            // último scale REAL, não desta tentativa adiada.
            return;
        }

        // Drenagem completa — seguro terminar.
        if (awsMode) {
            try {
                ec2.terminateInstances(new TerminateInstancesRequest()
                        .withInstanceIds(target.getInstanceId()));
                System.out.println("[AutoScaler] Instância " + target.getInstanceId()
                        + " terminada (drenagem completa).");
            } catch (Exception e) {
                System.err.println("[AutoScaler] Falha a terminar " + target.getInstanceId()
                        + ": " + e.getMessage());
            }
        } else {
            System.out.println("[AutoScaler] (local mode) SCALE DOWN simulado para " + target);
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
