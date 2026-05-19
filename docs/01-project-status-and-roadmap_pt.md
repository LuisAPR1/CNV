# Nature@Cloud — Estado do Projeto & Roteiro

> **Última atualização:** 2025-05-19 (pós-validação Fase 2 + auditoria + hardening Alta prioridade)
> **Autores:** Luis Alexandre + a81430 + laura
> **Curso:** Computação e Virtualização na Nuvem (CNV) — IST 2025-26
> **Mestre:** [`Project.txt`](../Project.txt) é a referência canónica do enunciado

---

## TL;DR

- **Fase 1** (instrumentação Javassist + MSS DynamoDB): implementada e validada em AWS.
- **Fase 2** (deploy EC2 + LB + AS reais): validada end-to-end (`docs/04-testing-checklist.md`, Níveis 0–11). Scale-up real **1→3 workers** comprovado.
- **Próximo bloqueio:** deliverables do **checkpoint a 22 Maio** — relatório de 1 página + vídeo. **Código está pronto.**
- **Após o checkpoint:** Fase 3 (Lambda) + Fase 4 (routing por complexidade + AS cost-aware).
- Auditoria de 19 Mai (resumo em §6): **0 problemas críticos**, ~10 pontos de atenção identificados. **Alta prioridade (3 itens): todos corrigidos no mesmo dia.** Restantes (Média / Baixa / Doc) ficam para tratar antes da entrega final (5 Jun).

---

## Índice

1. [Visão Geral](#1-visão-geral)
2. [Arquitectura Actual](#2-arquitectura-actual)
3. [Estado Actual — Código](#3-estado-actual--código)
4. [Estrutura do Projecto](#4-estrutura-do-projecto)
5. [Checklist do Checkpoint (22 Mai)](#5-checklist-do-checkpoint-22-mai)
6. [Pontos de Atenção Pendentes](#6-pontos-de-atenção-pendentes-da-auditoria-19-mai)
7. [Roadmap Fase 3 — Lambda](#7-roadmap-fase-3--lambda)
8. [Roadmap Fase 4 — Refinamentos Finais](#8-roadmap-fase-4--refinamentos-finais)
9. [Deliverables](#9-deliverables)
10. [Apêndice A — Setup AWS Rápido](#10-apêndice-a--setup-aws-rápido)
11. [Apêndice B — Comandos Úteis](#11-apêndice-b--comandos-úteis)
12. [Apêndice C — Histórico de Decisões e Fixes](#12-apêndice-c--histórico-de-decisões-e-fixes)

---

## 1. Visão Geral

**Nature@Cloud** = serviço elástico na AWS que executa 3 workloads CPU-bound:

| Endpoint | Workload | Parâmetros |
|---|---|---|
| `/fractals` | Julia-set | `w`, `h`, `iterations` |
| `/grayscott` | Reação-difusão | `size`, `maxIterations`, `f`, `k`, `stopOnExtinction`, `seedMode` |
| `/dna` | Matching FASTA | `seq1`, `seq2`, `minLength`, `stopOnFirst` |

Componentes (do enunciado §2):

- **Workers VM (EC2)** — Javassist obrigatório
- **Workers FaaS (Lambda)** — Javassist opcional
- **Load Balancer (LB)** — ponto de entrada único, em VM, decide EC2 vs Lambda baseado em complexidade
- **Auto Scaler (AS)** — co-localizado com LB, ajusta nº de workers EC2
- **MSS** — DynamoDB com métricas históricas para o LB consultar

**Datas:**

- **22 Maio 23:59** — submissão checkpoint (código + vídeo)
- **23 Maio 23:59** — relatório intermédio (1 página, 2 colunas)
- **5 Junho 23:59** — entrega final (código + vídeo)
- **6 Junho 23:59** — relatório final (até 6 páginas, 2 colunas)

---

## 2. Arquitectura Actual

> Validada em AWS (conta `577267183760`, região `eu-west-1`).

```
                       Internet
                          |
                          v :8080
            +--------------------------+
            |   LB EC2 (t3.micro)      |
            |  - ForwardHandler        |
            |  - ComplexityEstimator   | <-- Query DynamoDB (cache 30s)
            |  - AutoScaler (5s tick)  | --> EC2 RunInstances/Terminate
            |  - WorkerPool + HC 15s   |
            +----------+---------------+
                       | HTTP :8000
            +----------+----------+
            v          v          v
       +--------+ +--------+ +--------+
       |Worker 1| |Worker 2| |Worker N|   t3.micro, AMI cnv-worker
       |+jagent | |+jagent | |+jagent |   systemd cnv-worker.service
       +----+---+ +----+---+ +----+---+
            | async   |           |
            +---------+-----------+
                      v
              +----------------+
              |   DynamoDB     |
              |  cnv-metrics   |  PK=requestType, SK=requestId
              +----------------+
```

**Workers Lambda + invocação Lambda no LB** = ainda não existem (Fase 3).

---

## 3. Estado Actual — Código

### 3.1 Implementado e validado em AWS

| Componente | Estado | Evidência |
|---|---|---|
| `WebServer` multi-thread (`CachedThreadPool`) | OK | systemd `cnv-worker.service` na AMI |
| `JavassistAgent` (instrumenta `fractals/grayscott/dna`) | OK | `[Metrics] [fractals] params=..., methods=N, basicblocks=M` |
| `MetricRegistry` estruturado (`CompletedRequest` imutável) | OK | `MetricRegistry.java:22-70` (FIX 01) |
| `MetricsStorageService` async DynamoDB | OK | 45+ records gravados, race fix em `:144-149` (FIX 02) |
| `LoadBalancer.ForwardHandler` (retry + CORS) | OK | `selectLeastLoadedExcluding`, max 3 tentativas |
| `WorkerPool` + health checks (15s, 3 falhas) | OK | `WorkerPool.java:90-91` |
| `AutoScaler` real (5s / 1.0 / 0.25 / cooldown 60s / cap 5) | OK | scale-up duplo 1→3 com 20× `2000³` |
| `ComplexityEstimator` ratio-based + heuristic | OK | cache 30s (FIX 03) |
| `AwsConfig` centralizado (system properties) | OK | LB recebe via `-D` em `05-launch-lb.sh` |
| Provisioning end-to-end (6 scripts bash idempotentes) | OK | IAM + SG + AMI + worker + LB + cleanup |
| `iam:PassRole` inline para `LB-Role`→`Worker-Role` | OK | `01-setup-iam.sh:71-92` |

### 3.2 Implementado mas não em uso (preparação ou dead code)

- **`WorkerPool.selectWorker()`** (round-robin) — implementado, mas o LB usa `selectLeastLoaded` em todo o lado.
- **`AutoScaler.discoverExistingWorkers()`** — `@SuppressWarnings("unused")`. Útil para o caso "LB reinicia e EC2s já correm".
- **`RootHandler.HELLO_MSG`** — só logado, não devolvido no body (correcto para health check `200, -1`).

### 3.3 Não implementado (Fase 3 e 4)

| # | Componente | Fase | Notas |
|---|---|---|---|
| 1 | Workers Lambda (3 funções) | 3 | Handlers já implementam `RequestHandler<...>`; falta packaging + deploy + script |
| 2 | `LambdaInvoker` no LB | 3 | Dep nova: `aws-java-sdk-lambda` (mesma versão do SDK v1 já em uso) |
| 3 | Routing por complexidade no LB | 4 | `Estimate` é calculado mas só **logado** — não influencia escolha |
| 4 | Routing EC2 vs Lambda (custo / latência) | 4 | Modelo: pequenos→Lambda, grandes→EC2 |
| 5 | AS cost-aware (não só load) | 4 | Modelo: `$/h EC2` vs `$/req Lambda` |
| 6 | Instrumentação real de basic blocks | 4 | Heurística actual `bytecodeLength/15` (limita correlação para loops intra-método) |

---

## 4. Estrutura do Projecto

```
CNV/
+- pom.xml                  POM raiz, 6 modulos
+- Project.txt              Enunciado oficial - MESTRE
+- README.md                Build + execucao local
+- docs/
|  +- 01-project-status-and-roadmap_pt.md   <- este ficheiro
|  +- 02-fase2-aws-deployment.md            (detalhe Fase 2)
|  +- 03-aws-onboarding-guide.md            (per-membro AWS)
|  +- 04-testing-checklist.md               (Niveis 0-11)
|  +- 05-handoff-fase2.md                   (Fase 2->3 handoff)
|  +- FIX_01_refatorar_metric_registry.md
|  +- FIX_02_integracao_dynamodb.md
|  +- FIX_03_estimador_complexidade.md
|  +- _archive/                             (snapshots historicos)
+- scripts/                 Bash idempotente (Linux/Mac/Git Bash)
|  +- aws-config.sh         Vars + helpers (sanitize, ensure_my_ip, ensure_base_ami)
|  +- 01-setup-iam.sh       3 Roles + 2 Instance Profiles + iam:PassRole inline
|  +- 02-setup-network.sh   2 SGs + key pair (cnv-keypair.pem)
|  +- 03-create-ami.sh      AMI worker pre-cozida (Java 11 + JARs + systemd)
|  +- 04-launch-worker.sh   Lanca 1 worker da AMI
|  +- 05-launch-lb.sh       Lanca LB + scp JAR + nohup com -D properties
|  +- 99-cleanup.sh         Termina EC2s; --deep apaga IAM/SG/AMI/DDB
|  +- README.md             Quickstart + troubleshooting
|  +- .state/               IDs + cache (gitignored)
+- javassist/               Agent + MetricRegistry + MetricsStorageService
+- webserver/               WebServer + RootHandler (health 200,-1)
+- fractals/ grayscott/ dna/   Workloads (fornecidas pelo professor)
+- loadbalancer/
   +- LoadBalancer.java         Entry + ForwardHandler
   +- WorkerPool.java           Pool + health checks periodicos
   +- AutoScaler.java           EC2 SDK real
   +- ComplexityEstimator.java  Ratio-based + heuristic + cache
   +- AwsConfig.java            Config via -Dxxx
```

---

## 5. Checklist do Checkpoint (22 Mai)

| # | Requisito (Project.txt §4) | Estado |
|---|---|---|
| 1 | VMs multi-thread | OK — `CachedThreadPool` |
| 2 | Javassist a recolher métricas | OK — method calls + BBs estimados + elapsed |
| 3 | LB + AS configurados em AWS | OK — EC2 t3.micro |
| 4 | Lógica inicial LB/AS (ou pseudocódigo) | OK — ratio-based + thresholds + cooldown |
| 5 | Métricas no DynamoDB | OK — `cnv-metrics`, async, IMDSv2 |
| 6 | Scripts de deploy automatizado | OK — 6 bash idempotentes |
| 7 | **Relatório intermédio (1 pág, 2 col)** | **A FAZER (até 23 Mai)** |
| 8 | **Vídeo demo curto** | **A FAZER** |

**Veredicto:** código pronto para checkpoint. Falta apenas relatório + vídeo.

---

## 6. Pontos de Atenção Pendentes (da auditoria 19 Mai)

> Análise feita ao commit `98b58d7`. Prioridades para abordar **antes da entrega final** (5 Jun).
>
> **Status (19 Mai noite):** Alta prioridade ✅ TODOS corrigidos. Restantes (Média + Baixa + Doc) ainda pendentes.

### Alta prioridade — TODOS CORRIGIDOS (19 Mai)

1. ✅ **Worker novo pode ser removido pelo health check antes de estar pronto** — **FIXED**
   - Sintoma: janela de ~45s entre EC2 `running` e systemd a servir HTTP. Risco: loop SCALE UP → health-removal → SCALE UP.
   - Ficheiros: `loadbalancer/.../WorkerPool.java` + `loadbalancer/.../AutoScaler.java`
   - Fix aplicado:
     - Novo campo `Worker.graceUntilMs` + método `isInGracePeriod()`
     - Novo overload `WorkerPool.addWorker(host, port, instanceId, graceMs)`
     - `runHealthChecks()` salta workers em grace (não conta falhas)
     - `AutoScaler.scaleUp()` passa `WORKER_GRACE_MS = 90_000` ao adicionar worker
     - `AutoScaler.discoverExistingWorkers()` usa grace curto (30s)

2. ✅ **`02-setup-network.sh` deixava SSH aberto para `0.0.0.0/0`** se a detecção de IP falhasse — **FIXED**
   - Ficheiro: `scripts/02-setup-network.sh`
   - Fix aplicado: `exit 1` explícito em vez de fallback inseguro. Permite override controlado via env vars `MY_IP=1.2.3.4` ou `MY_CIDR=1.2.3.0/24` para redes corporativas/CI. Para forçar `0.0.0.0/0` é preciso `MY_CIDR=0.0.0.0/0` explícito.

3. ✅ **`LoadBalancer` criava worker `localhost:8000` por defeito em modo AWS** — **FIXED**
   - Sintoma: se `05-launch-lb.sh` fosse invocado sem instance IDs, LB arrancava com worker fantasma → 502s + health-removal + SCALE UP forçado nos primeiros ~45s.
   - Ficheiro: `loadbalancer/.../LoadBalancer.java:192-200`
   - Fix aplicado: criação do worker `localhost:8000` condicionada a `!AwsConfig.isAwsScalingEnabled()`. Em modo AWS, pool inicial fica vazio e o `AutoScaler` provisiona até `MIN_WORKERS`.

### Média prioridade (custo / robustez)

4. **`99-cleanup.sh --deep` pode deixar AMI/snapshots órfãos** se `.state/worker-ami-id.txt` for perdido. Fix: query por tag `Project=NatureAtCloud`.
5. **IAM policies usam `*FullAccess`** — para entrega final, justificar no relatório OU restringir a actions específicas.
6. **Race em `MetricsStorageService.ensureTableExists` para estado `CREATING`** — `describeTable` retorna OK e o worker B salta o `waitForTableActive`. Probabilidade muito baixa.
7. **Race em `scaleDown` drain**: pedido entre `selectLeastLoaded` e `incrementActive` pode ser interrompido. Mitigação: retry do LB compensa.

### Baixa prioridade (cosmético / dívida técnica)

8. **AutoScaler thresholds hardcoded** — para tuning sem rebuild, expor via `System.getProperty`.
9. **`WORKER_PORT` baked no systemd unit** dentro da AMI — mudar implica rebuild AMI. Documentar.
10. **Acumulação de regras SG** ao longo do tempo (cada IP novo é adicionado, antigos nunca removidos).

### Doc cleanup

11. **`docs/05-handoff-fase2.md:103`** descreve fix do `04-launch-worker.sh` errado (doc diz `head -1 + wc -l`, código usa `sort -u`).
12. **`docs/05-handoff-fase2.md:150`** menciona AMI ID placeholder fictício — actualizar.

---

## 7. Roadmap Fase 3 — Lambda

> **Quando começar:** depois do vídeo do checkpoint estar gravado (>= 22 Mai).
> **Esforço estimado:** 1-2 sessões (4-8 h).

### 7.1 Deploy das 3 funções Lambda

Os handlers já implementam `RequestHandler` (do professor), portanto a parte de código está praticamente pronta — falta packaging + deploy.

**Decisão recomendada:** 3 Lambdas separadas (uma por workload). Vantagens: IAM por função, custo per-function, troubleshooting mais fácil.

#### 7.1.1 Packaging

Adicionar `maven-shade-plugin` em cada workload (`fractals`, `grayscott`, `dna`) para gerar JARs Lambda-ready (uber-JAR sem dependências do `httpserver`):

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-shade-plugin</artifactId>
    <configuration>
        <finalName>${project.artifactId}-lambda</finalName>
    </configuration>
    <executions>
        <execution>
            <phase>package</phase>
            <goals><goal>shade</goal></goals>
        </execution>
    </executions>
</plugin>
```

#### 7.1.2 Script `06-deploy-lambdas.sh`

Novo script em `scripts/`:

```bash
LAMBDA_ROLE_ARN=$(aws iam get-role --role-name CNV-Lambda-ExecutionRole \
    --query 'Role.Arn' --output text)

for WL in fractals grayscott dna; do
    aws lambda create-function \
        --function-name cnv-$WL \
        --runtime java11 \
        --memory-size 512 \
        --timeout 30 \
        --role "$LAMBDA_ROLE_ARN" \
        --handler "pt.ulisboa.tecnico.cnv.${WL}.${WL^}Handler::handleRequest" \
        --zip-file "fileb://$WL/target/$WL-lambda.jar" \
        --region "$AWS_REGION" || \
    aws lambda update-function-code \
        --function-name cnv-$WL \
        --zip-file "fileb://$WL/target/$WL-lambda.jar" \
        --region "$AWS_REGION"
done
```

#### 7.1.3 Cleanup

Adicionar a `99-cleanup.sh --deep`:

```bash
for fn in cnv-fractals cnv-grayscott cnv-dna; do
    aws lambda delete-function --function-name "$fn" --region "$AWS_REGION" || true
done
```

### 7.2 `LambdaInvoker` no LB

- Nova dependência em `loadbalancer/pom.xml`: `aws-java-sdk-lambda` v1.12.528.
- Nova classe `loadbalancer/.../LambdaInvoker.java`:

```java
public class LambdaInvoker {
    private final AWSLambda lambda = AWSLambdaClientBuilder.standard()
        .withRegion(AwsConfig.REGION).build();

    public byte[] invoke(String functionName, Map<String,String> params) {
        InvokeRequest req = new InvokeRequest()
            .withFunctionName(functionName)
            .withPayload(toJson(params));
        InvokeResult res = lambda.invoke(req);
        if (res.getStatusCode() != 200) {
            throw new RuntimeException("Lambda failed: " + res.getFunctionError());
        }
        return res.getPayload().array();
    }
}
```

- Mapeamento `requestType -> functionName` em `AwsConfig`:

```java
public static final String LAMBDA_FRACTALS  = System.getProperty("cnv.lambda.fractals", "cnv-fractals");
public static final String LAMBDA_GRAYSCOTT = System.getProperty("cnv.lambda.grayscott", "cnv-grayscott");
public static final String LAMBDA_DNA       = System.getProperty("cnv.lambda.dna", "cnv-dna");
```

### 7.3 Routing inicial EC2 vs Lambda

Lógica inicial (refinar na Fase 4):

```
SE numWorkers == MIN_WORKERS E avgRemainingWork > THRESHOLD E estimatedCost < LAMBDA_CAP
   -> invocar Lambda (overflow path)
SENAO
   -> workers EC2 (path normal)
```

Justificação para o relatório: Lambda absorve picos curtos sem esperar 30-60 s pelo scale-up EC2; EC2 é mais barato em sustained load.

---

## 8. Roadmap Fase 4 — Refinamentos Finais

> **Esforço estimado:** 3-5 sessões.

### 8.1 Routing inteligente no LB (algoritmo central)

**Modelo proposto:** "trabalho restante estimado" por worker.

```java
// No WorkerPool.Worker:
private final AtomicLong remainingWork = new AtomicLong(0);

// Na chegada do pedido (LB):
long cost = estimator.estimate(type, params).getEstimatedBasicBlocks();
worker.remainingWork.addAndGet(cost);
worker.incrementActive();
try {
    forward(worker, request);
} finally {
    worker.remainingWork.addAndGet(-cost);
    worker.decrementActive();
}

// Selection:
selectMinRemainingWork()  // nao selectLeastLoaded()
```

**Decisão EC2 vs Lambda:**

```
SE min(worker.remainingWork) > LAMBDA_BREAKEVEN  E  cost < LAMBDA_TIMEOUT_BUDGET
   -> Lambda
SENAO
   -> EC2
```

### 8.2 Auto-scaling cost-aware

Estender a decisão actual (que é só `avgLoad`):

```
custo_horario = ec2_count * $0.0104     # t3.micro on-demand eu-west-1
custo_lambda  = invocations_per_hour * duration_avg_s * $0.0000166667 * (mb/1024)

SE avgRemainingWork > X  E  custo_lambda > custo_extra_ec2  ->  SCALE UP
SE avgRemainingWork < Y por > 5min  E  custo_lambda < custo_actual_ec2  ->  SCALE DOWN
```

### 8.3 Instrumentação real de basic blocks

Substituir heurística `bytecodeLength/15` por análise verdadeira:

- Iterar `MethodInfo.getCodeAttribute().iterator()`
- Detectar `if*`, `goto*`, `tableswitch`, `lookupswitch`, exception handlers como delimitadores de BB
- Inserir contadores **dentro** de cada BB (não só à entrada do método) usando `CodeIterator.insertAt`

**Trade-off para o relatório:** instrumentar BBs reais aumenta overhead em ~30-50% mas dá métricas que diferenciam pedidos com loops de tamanhos diferentes. Justificar a escolha.

### 8.4 Cache de consultas MSS — refinamento

Já implementado (`ComplexityEstimator` cache 30s). Possíveis melhorias:

- Pré-fetch periódico em vez de lazy
- Eviction adaptativo (mais frequente para tipos com maior variância)

### 8.5 Hardening pós-auditoria

- Alta prioridade: ✅ **feito em 19 Mai** (ver §6)
- Média prioridade: pendente (4 itens — cleanup órfãos, IAM least-privilege, race em `CREATING`, race em scaleDown drain)
- Baixa prioridade: pendente (3 itens — thresholds via property, WORKER_PORT na AMI, acumulação SG)

### 8.6 Testes contra a suite do professor

Quando a suite for divulgada:

- Correr suite com sistema actual e medir latency p50/p95/p99, throughput, custo
- Comparar configurações: só EC2 vs só Lambda vs híbrido
- Gerar gráficos para o relatório final

---

## 9. Deliverables

### 9.1 Checkpoint (22-23 Mai)

- [ ] **Relatório intermédio** (1 pág, 2 col):
  - a) o que está implementado: arquitectura + estruturas dados + algoritmos
  - b) pseudocódigo do LB+AS final (usar §8.1 e §8.2 deste doc como base)
- [ ] **Vídeo demo** (~3 min):
  1. `mvn package` → JARs gerados
  2. `01-setup-iam.sh` + `02-setup-network.sh` + `03-create-ami.sh`
  3. `04-launch-worker.sh` → 1 worker em AWS
  4. `05-launch-lb.sh <worker_id>` → LB
  5. `curl http://<LB>:8080/fractals?w=400&h=400&iterations=200 | sed 's/^data:image\/png;base64,//' | base64 -d > demo.png` → PNG abre em visualizador (resposta vem como data URL)
  6. **Burst de 20 pedidos `2000³`** → mostrar `[AutoScaler] SCALE UP` nos logs → `aws ec2 describe-instances` revela 3 workers
  7. `aws dynamodb scan --table-name cnv-metrics --select COUNT` → métricas persistidas
  8. `99-cleanup.sh` → cleanup limpo

### 9.2 Final (5-6 Jun)

- [ ] 3 Lambda functions deployed (fractals, grayscott, dna)
- [ ] LB com routing por complexidade (`remainingWork`-based)
- [ ] LB com decisão EC2 vs Lambda
- [ ] AS cost-aware
- [ ] Instrumentação refinada **OU** justificação rigorosa da heurística com dados experimentais
- [ ] Hardening dos pontos Alta prioridade da auditoria (§6)
- [ ] **Relatório final** (até 6 pág, 2 col): arquitectura + algoritmos + justificação + dados experimentais + charts
- [ ] **Vídeo final** demonstrando contra a suite do professor

---

## 10. Apêndice A — Setup AWS Rápido

### Pré-requisitos por membro

1. AWS CLI v2 instalado (`aws --version` >= 2.x)
2. IAM user (não root) — criado pela `laura` com `AdministratorAccess`
3. `aws configure` com region `eu-west-1`, output `json`
4. Validar: `aws sts get-caller-identity` deve devolver o teu ARN

### Provisioning inicial (1 vez por conta)

```bash
cd scripts
./01-setup-iam.sh         # Roles + Instance Profiles + iam:PassRole
./02-setup-network.sh     # 2 SGs + cnv-keypair.pem (chmod 400 auto)
cd .. && mvn clean package -DskipTests && cd scripts
./03-create-ami.sh        # AMI worker pre-cozida (~5 min)
```

### Workflow de cada sessão

```bash
cd scripts
./04-launch-worker.sh                                              # 1 worker EC2
./05-launch-lb.sh $(head -1 .state/worker-instance-ids.txt)        # LB com worker

# ... testes com curl ...

./99-cleanup.sh           # IMPORTANTE no fim de cada sessao (Free Tier)

# No fim do projecto:
./99-cleanup.sh --deep    # Apaga IAM/SG/AMI/DynamoDB (pede "YES")
```

### IAM Roles em uso

| Role | Trust | Policies |
|---|---|---|
| `CNV-LoadBalancer-Role` | `ec2.amazonaws.com` | EC2Full + DynamoDBFull + LambdaFull + LogsFull + inline `iam:PassRole`→`Worker-Role` |
| `CNV-Worker-Role` | `ec2.amazonaws.com` | DynamoDBFull + LogsFull |
| `CNV-Lambda-ExecutionRole` | `lambda.amazonaws.com` | LambdaBasicExec + DynamoDBFull |

### System properties do LB (passadas em `05-launch-lb.sh`)

```
-Daws.region=eu-west-1
-Dcnv.ami.id=ami-xxxx                      # de scripts/.state/worker-ami-id.txt
-Dcnv.worker.sg.id=sg-xxxx                 # de scripts/.state/worker-sg-id.txt
-Dcnv.keypair.name=cnv-keypair
-Dcnv.worker.instance.profile=CNV-Worker-Role
-Dcnv.instance.type=t3.micro
-Dcnv.worker.port=8000
```

Sem estas, o `AutoScaler` cai em **local mode** (só logs, sem chamadas EC2 reais) — útil para dev local sem custos.

---

## 11. Apêndice B — Comandos Úteis

### Estado da infra

```bash
# EC2s do projecto
aws ec2 describe-instances \
    --filters "Name=tag:Project,Values=NatureAtCloud" "Name=instance-state-name,Values=running" \
    --region eu-west-1 \
    --query "Reservations[].Instances[].[InstanceId,Tags[?Key=='Role']|[0].Value,PublicIpAddress]" \
    --output table

# Metricas no DynamoDB
aws dynamodb scan --table-name cnv-metrics --region eu-west-1 --select COUNT --query Count

# Logs do LB em tempo real
ssh -i scripts/cnv-keypair.pem ec2-user@<LB_IP> "tail -f /opt/cnv/lb.log"

# Logs do worker
ssh -i scripts/cnv-keypair.pem ec2-user@<WORKER_IP> "sudo journalctl -u cnv-worker.service -f"
```

### Provocar scale-up (demo/teste)

```bash
LB_IP=<LB_PUBLIC_IP>
# Stress test: descartamos a resposta (-o /dev/null) - o objectivo e ocupar
# os workers, nao visualizar imagens. Para visualizar, ver "Local-only dev".
for i in {1..20}; do
    curl -s -o /dev/null "http://$LB_IP:8080/fractals?w=2000&h=2000&iterations=2000" &
done; wait

ssh -i scripts/cnv-keypair.pem ec2-user@$LB_IP "tail -50 /opt/cnv/lb.log | grep AutoScaler"
```

### Local-only dev (sem custos AWS)

```bash
mvn clean package -DskipTests

# Worker 1 (porta 8000)
java -javaagent:javassist/target/javassist-agent-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
     -cp webserver/target/webserver-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
     pt.ulisboa.tecnico.cnv.webserver.WebServer 8000

# Worker 2 (porta 8001) noutro terminal
java -javaagent:javassist/target/javassist-agent-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
     -cp webserver/target/webserver-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
     pt.ulisboa.tecnico.cnv.webserver.WebServer 8001

# LB em local mode (AutoScaler so faz logs, nao chama EC2)
java -cp loadbalancer/target/loadbalancer-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
     pt.ulisboa.tecnico.cnv.loadbalancer.LoadBalancer 8080 localhost:8000 localhost:8001

# Nota: /fractals e /grayscott devolvem uma data URL
# ("data:image/png;base64,<b64>"), NAO bytes PNG. Decodifica antes de gravar:
curl -s "http://localhost:8080/fractals?w=400&h=300&iterations=100" \
    | sed 's/^data:image\/png;base64,//' | base64 -d > test_fractal.png

curl -s "http://localhost:8080/grayscott?size=128&maxIterations=1000" \
    | sed 's/^data:image\/png;base64,//' | base64 -d > test_grayscott.png

# /dna devolve HTML como texto - pode ir directo para ficheiro:
curl -s "http://localhost:8080/dna?seq1=seq1:ATGCATGC&seq2=seq2:ATGCATGC&minLength=3&stopOnFirst=false" \
    > test_dna.html
```

---

## 12. Apêndice C — Histórico de Decisões e Fixes

> Detalhe completo em `docs/FIX_01..03_*.md`, `docs/05-handoff-fase2.md`, e `docs/_archive/01-roadmap-fase1-2_archive.md`.

### Fase 1 — Fundação (commits `ed2e895`, `60f5adb`, `186a6be`)

- **FIX 01** — `MetricRegistry` refactored para dados estruturados:
  - `CompletedRequest` imutável com campos tipados
  - `RequestMetrics.parseUri()` decompõe URI em `requestType` + `parameters`
  - Storage `ConcurrentLinkedDeque` bounded a 1000 entradas
- **FIX 02** — `MetricsStorageService` (DynamoDB MSS):
  - Singleton + escritas async em thread daemon
  - Auto-cria tabela `cnv-metrics` (PAY_PER_REQUEST)
  - Graceful degradation sem credenciais AWS
  - Schema: PK=`requestType`, SK=`requestId`, métricas como `N`, params com prefixo `param_`
- **FIX 03** — `ComplexityEstimator` no LB:
  - Estratégia 2 camadas: ratio-based (histórico) → heuristic fallback
  - Cache 30s para evitar bottleneck no MSS
  - Features: `w*h*iter` (fractals), `size^2 * maxIter` (grayscott), `len(seq1)*len(seq2)/minLen` (dna)

### Fase 2 — Deploy AWS (commit `98b58d7`)

- 6 scripts bash idempotentes (`scripts/`)
- `AutoScaler` reescrito com EC2 SDK real (era esqueleto-só-logs)
- `AwsConfig` para system properties
- `WorkerPool` com health checks periódicos (15s, 3 falhas)
- 6 bugs encontrados durante validação real e fixados:
  1. `RootHandler` health check `(200,0)` → `(200,-1)` (corrompia HTTP framing)
  2. `iam:PassRole` inline policy adicionada (LB não conseguia lançar workers)
  3. Race `createTable` em scale-up paralelo (`ResourceInUseException` ignorada)
  4. Dedup state file via `sort -u` (evita IDs duplicados em re-execuções)
  5. AS thresholds afinados (15s / 3.0 / 0.5 → 5s / 1.0 / 0.25) para apanhar bursts
  6. IMDSv1 obsoleto em AL2023 (documentado em `02-fase2-aws-deployment.md §5`)

### Fase 3 — Lambda (TBD)

### Fase 4 — Refinamentos finais (TBD)

---

> **Próximo passo concreto:** escrever o relatório intermédio (1 pág) hoje/amanhã, usando §3 como base do "what's done" e §8.1 + §8.2 como pseudocódigo do "what remains".
