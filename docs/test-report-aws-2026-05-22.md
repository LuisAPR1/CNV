# Nature@Cloud — Test Report AWS (2026-05-22)

> **Sessão:** 3d — Deploy & Testes End-to-End na AWS
> **Data:** 2026-05-22 16:00–16:30 WEST
> **Região:** eu-west-1
> **Conta:** 577267183760 (cnv-admin-luis)
> **Infra:** 1× t3.micro LB + 1–3× t3.micro Workers (AutoScaler)

---

## 1. Pipeline de Deploy

| Step | Script | Resultado |
|---|---|---|
| Cleanup | `99-cleanup.sh --deep` | ✅ EC2s, SGs, keypair, IAM roles, AMIs, DynamoDB — tudo apagado |
| IAM | `01-setup-iam.sh` | ✅ 3 roles + 2 instance profiles criados |
| Network | `02-setup-network.sh` | ✅ Key pair + 2 SGs (worker + LB) criados |
| Build | `mvn clean package -DskipTests` | ✅ Build limpo (exit 0) |
| AMI | `03-create-ami.sh` | ✅ AMI `ami-02da0c7da02b0ce4d` criada (~5 min) |
| Worker | `04-launch-worker.sh` | ✅ Worker `i-0332a140b5637ed7b` @ `54.75.63.201:8000` |
| LB | `05-launch-lb.sh` | ✅ LB `i-08f8c683af1899b4d` @ `108.130.64.3:8080` |

**Tempo total de deploy (após deep cleanup):** ~12 minutos.

### ⚠️ Issue encontrada: Key pair + WSL

O `chmod 400` não funciona em paths `/mnt/c/...` (Windows FS montado no WSL). O script `02-setup-network.sh` criava a key com permissões 0555, e o SSH rejeitava (`UNPROTECTED PRIVATE KEY FILE`).

**Fix aplicado:** Alterado `aws-config.sh` para guardar a key em `~/.ssh/cnv-keypair.pem` (Linux FS). Ver `scripts/aws-config.sh:41`.

---

## 2. Smoke Tests (funcionalidade básica)

| # | Endpoint | HTTP | Time | Size | Status |
|---|---|---|---|---|---|
| 1 | `GET /` (LB health) | 200 | 0.15s | — | ✅ |
| 2 | `GET /fractals?w=200&h=200&iterations=50` | 200 | 1.38s | 19 KB | ✅ |
| 3 | `GET /grayscott?size=64&maxIterations=200` | 200 | 0.42s | 382 B | ✅ |
| 4 | `GET /dna?seq1=...&seq2=...&minLength=3` | 200 | 0.32s | 2.5 KB | ✅ |
| 5 | Worker direct `GET /` | 200 | 0.61s | — | ✅ |

**Conclusão:** Todos os 3 workloads funcionam através do LB e diretamente no worker.

---

## 3. JavassistAgent — Instrumentação

Worker log confirma que o agente instrumenta corretamente as 3 classes:

```
[JavassistAgent] Instrumented: pt.ulisboa.tecnico.cnv.fractals.FractalsHandler (27 blocks, 287 static instructions)
[JavassistAgent] Instrumented: pt.ulisboa.tecnico.cnv.dna.DnaHandler (35 blocks, 345 static instructions)
[JavassistAgent] Instrumented: pt.ulisboa.tecnico.cnv.grayscott.GrayScottHandler (30 blocks, 361 static instructions)
```

---

## 4. Métricas — DynamoDB Schema

16 registos armazenados. Schema verificado:

| Atributo | Tipo | Exemplo |
|---|---|---|
| `requestId` | S | `"1779463219243_d1d59b07"` |
| `requestType` | S | `"grayscott"` |
| `instructionCount` | N | `53774915482` |
| `methodCallCount` | N | `1638554` |
| `elapsedTimeMs` | N | `107546` |
| `timestamp` | N | `1779463218909` |
| `param_*` | S | `"64"`, `"200"`, etc. |

✅ Schema correto: `instructionCount` (não `basicBlockCount`), `methodCallCount` presente, parâmetros armazenados.

---

## 5. ComplexityEstimator — Heurísticas

### 5.1 Validação dos multiplicadores

| Workload | Feature | Heurística | Estimativa | ICount Real | Ratio | Erro |
|---|---|---|---|---|---|---|
| Fractals w200×h200×50 | w·h·min(iter,500) | ×10 (iter≤100) | 20.0M | 23.1M | 11.56 | -13% |
| Fractals w400×h400×100 | w·h·min(iter,500) | ×10 (iter≤100) | 160.0M | 122.3M | 7.64 | +31% |
| Fractals w500×h500×200 | w·h·min(iter,500) | ×5 (100<iter≤300) | 250.0M | 215.9M | 4.32 | +16% |
| Fractals w800×h800×1000 | w·h·min(iter,500) | ×2 (iter>300) | 640.0M | 563.6M | 1.76 | +14% |
| GrayScott s64×200 | size²·maxIter | ×164 | 134.3M | 134.9M | 164.7 | -0.4% |
| GrayScott s64×500 | size²·maxIter | ×164 | 335.9M | 336.9M | 164.5 | -0.3% |
| GrayScott s128×1000 | size²·maxIter | ×164 | 2.69B | 2.69B | 164.2 | -0.1% |
| GrayScott s128×2000 | size²·maxIter | ×164 | 5.37B | 5.38B | 164.2 | -0.1% |
| GrayScott s256×5000 | size²·maxIter | ×164 | 53.74B | 53.77B | 164.1 | -0.1% |
| DNA 17 chars | max(seq1,seq2) | ×125 | 2,125 | 2,130 | 125.3 | -0.2% |
| DNA 50 chars | max(seq1,seq2) | ×125 | 6,875 | 6,278 | 125.6 | +9.5% |

**Conclusão:**
- **GrayScott**: Erro <1% em 5 medições (64 a s256, 3 ordens de grandeza). ⭐ Excelente.
- **Fractals**: Erro máximo ~31% (regime ramp-up). Piecewise cobre os 3 regimes corretamente. ⭐ Bom.
- **DNA**: Erro <10%. ⭐ Bom.

### 5.2 ⚠️ Ratio-based estimator NÃO activo

**Bug:** O `ComplexityEstimator` tenta conectar ao DynamoDB no arranque. Se a tabela não existe (acabou de ser apagada pelo deep cleanup), regista `DynamoDB indisponível` e fica **permanentemente** em modo heuristic-only. Nunca faz retry.

**Impacto:** O sistema funciona corretamente com heurísticas (erros baixos), mas não melhora com dados históricos como esperado. O ratio-based learning nunca é ativado.

**Recomendação:** Adicionar retry periódico ao DynamoDB no `ComplexityEstimator` (ex.: tentar reconectar a cada 60s ou em cada `estimate()` call).

**Log relevante:**
```
[ComplexityEstimator] DynamoDB indisponível: ... ResourceNotFoundException: Table: cnv-metrics not found
[ComplexityEstimator] A usar apenas heurísticas baseadas em parâmetros.
```

---

## 6. AutoScaler — Scale-Up

### Teste: 6× GrayScott s256×5000 em paralelo

| Fase | Workers | avgEstWork | Ação |
|---|---|---|---|
| Pré-burst | 1 | 0 | — |
| Durante burst (t+5s) | 1 | 322.4B | **SCALE UP** → +1 worker |
| Durante burst (t+10s) | 2 | 161.2B | **SCALE UP** → +1 worker |
| Pico | 3 | 107.5B | Estável |
| Pós-burst (t+120s) | 3 | 0 | Aguarda... |

**Resultado:** 1 → 3 workers. Todos os 6 pedidos completaram com HTTP 200 (~108s cada).

### Thresholds verificados:
- `ESTIMATED_WORK_THRESHOLD = 5×10⁹` (5B): scale-up quando avgEstWork > 5B ✅
- `MAX_CAPACITY = 5×10¹⁰` (50B): packing funciona (s256×5000 = 53.7B ≈ 1 por worker) ✅

---

## 7. AutoScaler — Scale-Down

### Fase 1: 3→2 workers

| Tempo após burst | Workers | avgEstWork | Ação |
|---|---|---|---|
| t+0s | 3 | 0 | — |
| t+~60s | 3 | 0 | **SCALE DOWN** → drenar worker original |
| t+~120s | 2 | 0 | Worker `i-0332a...` terminado |

### Fase 2: 2→1 workers

| Tempo após burst | Workers | avgEstWork | Ação |
|---|---|---|---|
| t+180s | 2 | 0 | **SCALE DOWN** → drenar worker #2 |
| t+~240s | 1 | 0 | Worker `i-0fd3...` terminado |

**Resultado:** 3 → 2 → 1 workers. Drenagem correta (workers removidos do pool antes de terminar). ✅

### Thresholds verificados:
- `SCALE_DOWN_THRESHOLD = 1.25×10⁹` (1.25B): scale-down quando avgEstWork < 1.25B ✅
- `SCALE_DOWN_COOLDOWN`: respeitado (~2 min entre scale-downs) ✅

---

## 8. Health Checks

```
[WorkerPool] Health check FAILED (1/3): 172.31.47.102:8000 [i-021c5b85d5ee024e4]
[WorkerPool] Health recovered: 172.31.47.102:8000 [i-021c5b85d5ee024e4]
```

Health checks funcionam: detectam falha temporária (worker ainda a arrancar) e recuperam quando fica ready. Threshold de 3 falhas antes de remoção configurado. ✅

---

## 9. Resumo — O que funciona

| Componente | Estado | Notas |
|---|---|---|
| Deploy infra (scripts 01-05) | ✅ | ~12 min |
| Cleanup (script 99) | ✅ | Deep clean completo |
| Worker auto-start (systemd) | ✅ | Boot em ~30-60s |
| LB routing | ✅ | Forwarding correto |
| JavassistAgent | ✅ | 3 classes instrumentadas |
| Métricas (ICount, MCC, elapsed) | ✅ | 16 registos em DynamoDB |
| DynamoDB schema | ✅ | `instructionCount` (não `basicBlockCount`) |
| Heurísticas GrayScott | ⭐ | Erro <1%, variância <0.01% |
| Heurísticas Fractals | ✅ | Piecewise 3 regimes, erro máx ~31% |
| Heurísticas DNA | ✅ | Erro <10% |
| Scale-up | ✅ | 1→3 workers sob carga |
| Scale-down | ✅ | 3→1 workers após carga |
| Drenagem antes terminate | ✅ | Workers removidos do pool primeiro |
| Health checks | ✅ | Detecta falha e recuperação |
| Workload fractals | ✅ | PNG gerado corretamente |
| Workload grayscott | ✅ | Dados gerados corretamente |
| Workload dna | ✅ | Resultados gerados corretamente |

## 10. Bug fixes aplicados

### 10.1 Ratio-based nunca ativa → ✅ CORRIGIDO

**Problema:** `ComplexityEstimator` tentava conectar ao DynamoDB apenas no construtor. Se a tabela não existisse (acabou de ser apagada pelo deep cleanup), ficava permanentemente em modo heuristic-only.

**Fix:** Adicionado retry automático a cada 60s. O método `retryIfNeeded()` é chamado em cada `estimate()`. Se `available=false` e já passaram 60s desde a última tentativa, tenta reconectar.

**Verificação pós-fix:**
```
[ComplexityEstimator] DynamoDB disponível. A usar dados históricos.
[ComplexityEstimator] Cache atualizado para 'grayscott': 10 registos
[LoadBalancer] Complexity estimate for /grayscott: cost=2690690738 (history)
[ComplexityEstimator] Cache atualizado para 'fractals': 4 registos
[LoadBalancer] Complexity estimate for /fractals: cost=56878594 (history)
[ComplexityEstimator] Cache atualizado para 'dna': 2 registos
[LoadBalancer] Complexity estimate for /dna: cost=1556 (history)
```

✅ Os 3 workloads usam estimativas ratio-based `(history)`.

### 10.2 Key pair no WSL → ✅ CORRIGIDO

**Problema:** `chmod 400` não funciona em `/mnt/c/...` (Windows FS). SSH rejeitava a key.

**Fix:** `aws-config.sh` alterado para guardar a key em `~/.ssh/cnv-keypair.pem` (Linux FS).

## 11. Recomendações para a Demo

1. **Ordem de execução na demo:**
   ```bash
   cd scripts
   ./99-cleanup.sh --deep    # (se quiseres começar limpo)
   ./01-setup-iam.sh
   ./02-setup-network.sh
   # mvn clean package -DskipTests (na raiz do projeto)
   ./03-create-ami.sh
   ./04-launch-worker.sh
   ./05-launch-lb.sh $(cat .state/worker-instance-ids.txt)
   ```

2. **Demonstração do scale-up:** Enviar 4-6 pedidos GrayScott s256 em paralelo:
   ```bash
   for i in 1 2 3 4 5 6; do
     curl -s "http://$LB_IP:8080/grayscott?size=256&maxIterations=5000" &
   done
   ```
   Mostrar LB status page a mostrar workers a aumentar.

3. **Demonstração do scale-down:** Esperar ~3-4 min e mostrar workers a diminuir.

4. **Mostrar DynamoDB:**
   ```bash
   aws dynamodb scan --table-name cnv-metrics --select COUNT --region eu-west-1
   aws dynamodb scan --table-name cnv-metrics --limit 2 --region eu-west-1
   ```

5. **Se quiseres o ratio-based a funcionar na demo:** Reiniciar o LB após a tabela DynamoDB já existir (depois do primeiro pedido). Ou aplicar o fix de retry.

---

## 12. Evidências

- **AMI ID:** `ami-02da0c7da02b0ce4d`
- **LB IP:** `108.130.64.3:8080`
- **Worker final:** `54.229.164.78:8000` (`i-021c5b85d5ee024e4`)
- **DynamoDB:** 16 items, tabela `cnv-metrics`
- **Logs LB:** `/opt/cnv/lb.log` (scale-up/down, estimates, health checks)
- **Logs Worker:** `/var/log/cnv-worker.log` (JavassistAgent, requests)
- **CSV calibração:** `docs/evidence-2026-05-21-calibration/bench-ext.csv` (33 medições locais)

---

> **Conclusão:** A infraestrutura está funcional. Scale-up/down, métricas, heurísticas — tudo opera conforme esperado. O único ponto a melhorar é o retry do DynamoDB no ComplexityEstimator para ativar o ratio-based learning.
