# Nature@Cloud — Resumo de Alterações (2026-06-02 a 2026-06-04)

## 1. Métrica Composta CPU+RAM (2026-06-02)

**Fórmula:** `compositeCost = W_CPU × instructionCount + W_RAM × allocatedBytes`

- **W_CPU = 1.0, W_RAM = 1.0** — ambos contribuem linearmente, ICount domina (~40.000:1) porque os workloads são CPU-bound
- **allocatedBytes** recolhido via `ThreadMXBean.getThreadAllocatedBytes()` — zero overhead, built-in da JVM
- Pesos configuráveis: `-Dcnv.estwork.wcpu=X -Dcnv.estwork.wram=Y`

### Ficheiros alterados

| Ficheiro | Alteração |
|---|---|
| `MetricRegistry.java` | +campo `allocatedBytes` em `CompletedRequest` e `RequestMetrics` |
| `MetricsStorageService.java` | DynamoDB guarda `allocatedBytes` |
| `ComplexityEstimator.java` | `HistoricalRecord` inclui `allocatedBytes`; estimativa composta com ratios CPU+RAM separados |

---

## 2. Calibração Empírica de RAM (2026-06-03)

16 medições locais para calibrar heurísticas de `allocatedBytes`.

### Heurísticas corrigidas

| Workload | Antes | Depois | Ratio real |
|---|---|---|---|
| Fractals | `w×h×8` | `w×h×33` | ~32.5 B/px |
| GrayScott | `size²×20` | `size²×64` | ~63.5 B/cell |
| DNA | `maxSeq×200` | `maxSeq×800` | ~780 B/char |

### Descobertas

- RAM **não depende** de `iterations`/`maxIterations` — só de dimensões dos buffers
- `seedMode` não afeta RAM (<1%)
- DNA é o único workload onde RAM é comparável ao CPU (ratio 0.15)

### Ficheiro novo

| Ficheiro | Descrição |
|---|---|
| `bench-ram-calibration.csv` | 16 medições + análise completa |

---

## 3. Integração Lambda (2026-06-04)

Suporte a workers Lambda como caminho alternativo para pedidos pequenos/médios.

### Lógica de routing

```
estimatedCostSeconds ≤ 5.0s?
  ├─ SIM + all workers >80% → Lambda (fast-path)
  ├─ SIM + EC2 disponível    → EC2 (normal)
  ├─ SIM + EC2 falha         → Lambda (fallback)
  └─ NÃO                     → EC2 only
```

### Constantes

| Constante | Valor | Propriedade |
|---|---|---|
| `LAMBDA_MAX_SECONDS` | 5.0s | `-Dcnv.lambda.maxseconds` |
| `WORKER_LOAD_THRESHOLD` | 0.80 | `-Dcnv.lambda.loadthreshold` |

### Ficheiros criados/modificados

| Ficheiro | Ação |
|---|---|
| `LambdaInvoker.java` | **NOVO** — singleton AWS SDK Lambda |
| `LoadBalancer.java` | +routing Lambda (fast-path + fallback) |
| `loadbalancer/pom.xml` | +`aws-java-sdk-lambda` |
| `scripts/06-deploy-lambdas.sh` | **NOVO** — deploy idempotente das 3 Lambdas |
| `scripts/01-setup-iam.sh` | Persiste Lambda role ARN |
| `scripts/99-cleanup.sh` | +deleção das 3 Lambdas |

---

## 4. Documentação Atualizada

| Ficheiro | Alterações |
|---|---|
| `docs/00-project-status-and-roadmap_pt.md` | Sessão 4 completa (métrica composta + calibração RAM + Lambda); checklists atualizados |
| `report/checkpoint-report.tex` | Secções de métrica composta, LB, AS atualizadas; pseudocódigo corrigido |

---

## Thresholds (inalterados)

| Constante | Valor | Significado |
|---|---|---|
| `MAX_CAPACITY` | 5×10¹⁰ (25s wall-clock) | Packing cap por worker |
| Scale-up | 2.5s avg/worker | Dispara AutoScaler |
| Scale-down | 0.6s avg/worker | Reduz workers |
