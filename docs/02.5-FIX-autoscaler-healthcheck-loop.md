# Fix: AutoScaler Loop Infinito (Health Check + instanceId)

**Data:** 2026-05-21
**Status:** Corrigido e validado em AWS real (eu-west-1)

---

## Sintoma

Após `05-launch-lb.sh`, o AutoScaler entrava num ciclo infinito:

1. Health check falhava 3x consecutivas (45s) no worker manual
2. Worker evicto do pool → pool vazio
3. AutoScaler criava novo worker (`SCALE UP forçado`)
4. Novo worker também falhava health checks → evicto + EC2 terminada
5. Pool vazio → repetia desde o passo 3

Resultado: ~1 worker novo criado e terminado a cada 2 minutos. O worker manual original mantinha-se sempre `running` (órfão, nunca terminado).

```
[WorkerPool] Health check FAILED (1/3): 3.252.x.x:8000
[WorkerPool] Health check FAILED (2/3): 3.252.x.x:8000
[WorkerPool] Health check FAILED (3/3): 3.252.x.x:8000
[WorkerPool] Removing unhealthy worker: 3.252.x.x:8000
[AutoScaler] Abaixo do mínimo (1) — SCALE UP forçado.
[AutoScaler] Instância lançada: i-xxx — a aguardar IP...
... (repetia infinitamente)
```

---

## Causas Raiz (2 bugs)

### Bug 1 — IPs públicos vs Security Groups

O LB comunicava com os workers usando **IPs públicos**, mas o Security Group do worker (`cnv-worker-sg`) só permitia porta 8000 a partir do SG do LB (`cnv-lb-sg`), que faz match por **IPs privados**.

Como o tráfego saía pela internet gateway, o source IP visto pelo worker era o IP público do LB — que não estava na regra do SG. Tráfego bloqueado → health check falhava.

**Ficheiros afetados:**
- `scripts/05-launch-lb.sh` — resolvia `PublicIpAddress` para passar como argumento ao LB
- `loadbalancer/.../AutoScaler.java` — `waitForPublicIp()` + `discoverExistingWorkers()` usavam `getPublicIpAddress()`

### Bug 2 — `addWorker` idempotente não atualizava `instanceId`

`WorkerPool.addWorker()` é idempotente por `(host, port)`: se um worker com o mesmo endpoint já existe no pool, retorna o existente em vez de adicionar duplicado.

**Problema:** Quando o worker era adicionado via CLI do `05-launch-lb.sh` (sem `instanceId`), e depois o `discoverExistingWorkers()` o encontrava via describe-instances (com `instanceId`), o método retornava o worker existente **sem atualizar o `instanceId`**.

Consequência: o worker ficava com `instanceId = null`. No `handleUnhealthyEviction`, o AutoScaler via `instanceId == null` e **não terminava a EC2** — a instância ficava órfã, a consumir recursos.

**Ficheiro afetado:**
- `loadbalancer/.../WorkerPool.java` — método `addWorker(String host, int port, String instanceId)`

---

## Correções Aplicadas

### 1. `scripts/05-launch-lb.sh` — linha 38

```diff
- --query "Reservations[0].Instances[0].PublicIpAddress"
+ --query "Reservations[0].Instances[0].PrivateIpAddress"
```

### 2. `scripts/05-launch-lb.sh` — linha 87 (novo)

Adicionado `mkdir -p /opt/cnv` antes do SCP, para garantir que o diretório existe (o user-data por vezes não o criava a tempo):

```bash
ssh "${SSH_OPTS[@]}" "ec2-user@$LB_IP" "mkdir -p /opt/cnv"
scp "${SSH_OPTS[@]}" "$LB_JAR" "ec2-user@$LB_IP:/opt/cnv/loadbalancer.jar"
```

### 3. `loadbalancer/.../AutoScaler.java`

- `waitForPublicIp()` → `waitForPrivateIp()`, usando `getPrivateIpAddress()`
- `discoverExistingWorkers()`: `getPublicIpAddress()` → `getPrivateIpAddress()`
- `scaleUp()`: referência atualizada para `waitForPrivateIp`

### 4. `loadbalancer/.../WorkerPool.java` — `addWorker()`

Quando encontra um worker duplicado com `instanceId == null` e a nova chamada tem `instanceId != null`, substitui o worker antigo pelo novo (com `instanceId` correto):

```java
if (existing.getInstanceId() == null && instanceId != null) {
    workers.remove(existing);
    Worker updated = new Worker(host, port, instanceId);
    workers.add(updated);
    System.out.println("[WorkerPool] Updated worker instanceId: " + updated);
    return updated;
}
```

---

## Resultado Pós-Fix

Sistema estável com 1 worker, sem health check failures, sem scaling loop:

```
[WorkerPool] Added worker: 172.31.37.70:8000 (active=0)
[WorkerPool] Updated worker instanceId: 172.31.37.70:8000 [i-0f3efb5e44e910426] (active=0)
[AutoScaler] discoverExistingWorkers: 1 EC2(s) Role=worker running encontradas e adoptadas.
[AutoScaler] Workers=1, TotalActive=0, AvgLoad=0.00
... (estável, sem health check failures)
```

---

## Notas

- O `ComplexityEstimator` reporta `DynamoDB indisponível` porque a tabela `cnv-metrics` não é criada automaticamente. Isto é esperado e não afeta o funcionamento — o sistema usa heurísticas baseadas em parâmetros como fallback.
- A comunicação LB↔Worker passou a usar IPs privados, o que é mais correto e seguro para recursos na mesma VPC.
