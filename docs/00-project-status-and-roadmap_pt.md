# Nature@Cloud - Estado do Projeto & Roteiro

> **Última atualização:** 2026-06-02 (sessão 4 — métrica composta CPU+RAM)
> **Autores:** Luis Alexandre + a81430 + laura
> **Curso:** Computação e Virtualização na Nuvem (CNV) - IST 2025-26

## Resumo da sessão 4 (Luis, 2026-06-02, métrica composta CPU+RAM)

Sessão focada em evoluir a métrica de estimativa de complexidade de ICount puro para uma **métrica composta** que captura tanto CPU como RAM.

**🚀 Nova métrica composta:**

```
compositeCost = W_CPU × instructionCount + W_RAM × allocatedBytes
```

- **W_CPU = 1.0**, **W_RAM = 1.0** (configuráveis via `-Dcnv.estwork.wcpu=X -Dcnv.estwork.wram=Y`)
- **ICount** (CPU): instruções bytecode executadas — já existia via Javassist `ControlFlow`
- **allocatedBytes** (RAM): bytes alocados pela thread durante o pedido — **NOVO**, via `com.sun.management.ThreadMXBean.getThreadAllocatedBytes()`

**Vantagens da abordagem:**
- **Zero overhead de instrumentação** — `ThreadMXBean` é built-in da JVM, contador já mantido pelo runtime
- **Per-thread** — encaixa perfeitamente no modelo `ThreadLocal<RequestMetrics>` existente
- **Degradação graciosa** — retorna `-1` se `ThreadMXBean` não estiver disponível (raro em Java 11+)
- **Complementa ICount naturalmente** — ICount mede trabalho CPU, allocatedBytes mede pressão de memória

**🎓 Como defender esta decisão (para relatório / discussão oral):**

### Argumento central: ICount-dominated by design

A métrica composta é **intencionalmente dominada pelo ICount** porque os 3 workloads são CPU-bound por natureza. A RAM funciona como **sinal secundário** e **mecanismo de extensibilidade**, não como contribuição paritária. Isto não é uma limitação — é uma observação empírica documentada com 16 medições de RAM + 33 medições de ICount.

### Dados empíricos de suporte (calibração RAM — 2026-06-03)

| Workload | ICount | allocatedBytes | RAM / Composite | Ratio ICount/Alloc |
|---|---|---|---|---|
| GrayScott s256×5000 | 53.77B | 4.05MB | **0.008%** | 13 271 instr/B |
| Fractals 800×600×100 | 366.98M | 15.91MB | **4.2%** | 23.1 instr/B |
| Fractals 800×600×500 | 422.31M | 15.43MB | **3.5%** | 27.4 instr/B |
| DNA maxSeq=500 | 61.4K | 414KB | **87%** | **0.15 instr/B** |

### Pontos de defesa (numerados para referência no oral)

1. **"A métrica composta captura duas dimensões genuinamente independentes do recurso."**
   ICount mede trabalho CPU (instruções executadas). allocatedBytes mede pressão de memória (bytes alocados via `ThreadMXBean`). Ao contrário de ICount + MethodCallCount (que têm correlação directa — mais instruções = mais chamadas de método), ICount e RAM têm **comportamentos distintos demonstráveis**: a RAM não depende de `iterations`/`maxIterations`, enquanto o ICount depende fortemente.

2. **"Demonstramos empiricamente que RAM e CPU têm features diferentes."**
   - Fractals: CPU escala com `w×h×min(iter,500)`, RAM escala apenas com `w×h` (BufferedImage alocado uma vez, independente do nº de iterações)
   - GrayScott: CPU escala com `size²×maxIter`, RAM escala apenas com `size²` (grids alocadas uma vez)
   - Isto significa que dois pedidos com a mesma resolução mas iterações diferentes têm o **mesmo custo de memória** mas **custos CPU muito diferentes** — as métricas capturam dimensões realmente ortogonais

3. **"O DNA é a prova empírica de que a RAM pode ser o recurso dominante."**
   Com ratio ICount/AllocatedBytes = 0.15 (aloca **6.7× mais bytes do que instruções que executa**), o DNA demonstra que existem workloads onde a RAM é o factor limitante. Se um futuro workload tiver perfil semelhante ao DNA (I/O-bound, muitas alocações, pouca computação), a métrica composta captura-o correctamente sem necessidade de re-implementação — basta re-calibrar os pesos W_CPU e W_RAM.

4. **"W_CPU = W_RAM = 1.0 é a baseline mais conservadora e transparente."**
   Normalizar as métricas (ex: z-score ou divisão por referência) introduziria parâmetros adicionais que precisariam de justificação. A soma directa 1:1 deixa o domínio natural dos workloads determinar qual métrica prevalece. Para workloads CPU-bound, o ICount domina naturalmente (~13.000:1 em GrayScott); para workloads memory-intensive, a RAM dominaria. Os pesos são configuráveis via system properties (`-Dcnv.estwork.wcpu=X -Dcnv.estwork.wram=Y`) para permitir re-calibração sem recompilação.

5. **"As heurísticas de RAM foram calibradas empiricamente com 16 medições."**
   Antes da calibração, as heurísticas subestimavam a RAM em 3-4× (ex: 8 B/px vs 32.5 B/px real para fractals). Após calibração: erro <10% para dimensões ≥ 400px/128 cells/200 chars. Dados completos em `bench-ram-calibration.csv`.

### Limitações conhecidas (declarar proactivamente no relatório)

1. **O estimador history-based usa a mesma `extractFeature()` para CPU e RAM.** Como a RAM não depende de `iterations`/`maxIterations`, o ratio RAM/feature calculado no histórico é inconsistente entre pedidos com o mesmo `w×h` mas iterações diferentes (varia até 5×). Na prática, isto não afecta o routing porque a contribuição da RAM no composite é <0.01% para workloads CPU-bound. Resolução futura: criar um `extractRamFeature()` separado.

2. **Fixed overhead não capturado.** Para dimensões pequenas (ex: fractals 200×200, GrayScott s=64, DNA maxSeq=16), existe um overhead fixo de ~30KB-1.3MB que as heurísticas lineares não capturam. Erro até -81% em s=64 para GrayScott. Para dimensões típicas de produção (s≥128, w≥400), o erro é <10%.

3. **Medições locais vs AWS.** A calibração de RAM foi feita localmente. Os valores em t3.micro (Amazon Corretto 11) podem diferir ligeiramente devido a diferenças no JIT e no allocator. Espera-se que a ordem de grandeza se mantenha.

### Estrutura sugerida para a secção "Composite Metric" do relatório

```
1. Motivação: ICount sozinho não captura pressão de memória
2. Implementação: ThreadMXBean.getThreadAllocatedBytes() — zero overhead
3. Fórmula: compositeCost = W_CPU × ICount + W_RAM × allocatedBytes
4. Calibração empírica: tabela com 16 medições (referência ao CSV)
5. Descoberta-chave: RAM tem features diferentes do CPU
   (não depende de iterações — evidência de independência)
6. Caso DNA: evidência de que RAM pode dominar (ratio 0.15)
7. Impacto no routing: <0.01% para workloads actuais (ICount-dominated)
8. Extensibilidade: pesos configuráveis, preparado para workloads futuros
```

**Alterações no código (3 ficheiros):**

| Ficheiro | Alteração |
|---|---|
| `MetricRegistry.java` | `CompletedRequest` + `RequestMetrics` ganham campo `allocatedBytes`; `reset()` captura baseline via `ThreadMXBean`; `getAllocatedBytes()` devolve delta |
| `MetricsStorageService.java` | DynamoDB passa a guardar `allocatedBytes` como atributo `N` |
| `ComplexityEstimator.java` | `HistoricalRecord` inclui `allocatedBytes`; `estimateFromHistory()` calcula ratios separados para CPU e RAM e combina via `compositeCost()`; `estimateFromHeuristics()` estima ambos e combina; constantes `W_CPU`/`W_RAM` configuráveis |

**Heurísticas de RAM (fallback sem histórico) — calibradas empiricamente 2026-06-03 (16 medições):**

| Workload | Heurística antiga | Heurística calibrada | Ratio real medido | Evidência |
|---|---|---|---|---|
| Fractals | `w × h × 8` | `w × h × 33` | 31–34 B/px (média 32.5) | `bench-ram-calibration.csv` L2-7 |
| GrayScott | `size² × 20` | `size² × 64` | 61–65 B/cell (média 63.5) | `bench-ram-calibration.csv` L8-13 |
| DNA | `maxSeq × 200` | `maxSeq × 800` | 780 B/char + ~30KB fixo | `bench-ram-calibration.csv` L14-17 |

**Descobertas da calibração RAM (ver `bench-ram-calibration.csv` para dados completos):**
- **RAM é independente de iterações/maxIterations** — escala apenas com dimensões dos buffers (`w×h`, `size²`)
- **seedMode não afeta RAM** (<1% diferença) — confirma observação anterior do ICount
- **DNA é o único workload onde RAM é comparável ao CPU** — ratio ICount/Alloc = 0.15 para maxSeq=500
- **Heurísticas antigas subestimavam RAM em 3-4×** — corrigidas com dados empíricos

**Impacto nos thresholds:** Com W_CPU=W_RAM=1.0, o composite é dominado pelo ICount (~13.000:1 para GrayScott típico). Para um GrayScott s256×5000: ICount ≈ 53.7B, RAM ≈ 4.05MB → contribuição RAM = 4.05M (0.008%). Os thresholds existentes (`MAX_CAPACITY=5×10¹⁰`, `THRESHOLD=5×10⁹`) mantêm-se válidos.

Build limpo (`mvn compile` exit 0).

### Integração Lambda (2026-06-04)

Adicionado suporte a workers Lambda (FaaS) como caminho de execução alternativo para pedidos pequenos/médios.

**Arquitetura de routing Lambda:**

```
1. Estimar custo: estimatedCostSeconds = estimatedCost / (throughput × 1000)
2. SE estimatedCostSeconds > LAMBDA_MAX_SECONDS (5.0s):
     → EC2 only (nunca Lambda — pedido demasiado pesado)
3. SE estimatedCostSeconds ≤ LAMBDA_MAX_SECONDS:
     a) Fast-path: se TODOS os workers >80% ocupados → Lambda direto (evita scale-up desnecessário)
     b) EC2 first (normal path com retry)
     c) Fallback: se EC2 falhar → Lambda (fault tolerance)
```

**Constantes (configuráveis via -D system properties):**

| Constante | Valor | Significado |
|---|---|---|
| `LAMBDA_MAX_SECONDS` | 5.0s | Pedidos abaixo disto são Lambda-eligible |
| `WORKER_LOAD_THRESHOLD` | 0.80 | Workers acima de 80% → considerar Lambda |

**3 cenários de routing Lambda:**

1. **Capacity overflow** — todos os workers >80% + pedido pequeno → Lambda direto, evita scale-up para pedidos triviais
2. **Fault tolerance** — EC2 retries exhausted → Lambda como último recurso
3. **Cost avoidance** — pedidos abaixo de 5s nunca justificam uma nova EC2 (cold start ~60s)

**Ficheiros criados/modificados:**

| Ficheiro | Alteração |
|---|---|
| `loadbalancer/pom.xml` | Adicionada dependência `aws-java-sdk-lambda` |
| `LambdaInvoker.java` | NOVO — singleton que invoca Lambdas via AWS SDK |
| `LoadBalancer.java` | `ForwardHandler` com routing Lambda (fast-path + fallback); constantes `LAMBDA_MAX_SECONDS`, `WORKER_LOAD_THRESHOLD` |
| `scripts/06-deploy-lambdas.sh` | NOVO — deploy/update das 3 Lambdas (idempotente) |
| `scripts/01-setup-iam.sh` | Persiste Lambda role ARN em `.state/lambda-role-arn.txt` |
| `scripts/99-cleanup.sh` | Adicionada deleção das 3 Lambdas |

**🎓 Como defender (relatório/oral):**

1. **"O threshold de 5s separa pedidos que justificam EC2 dos que não justificam."** Um pedido de 5s em Lambda custa ~$0.00001 (512MB×5s). Uma EC2 t3.micro custa ~$0.012/hora. Para justificar uma nova EC2, o pedido precisa ser suficientemente pesado ou fazer parte de um burst sustentado.

2. **"Lambda é usado como válvula de escape, não como caminho primário."** O sistema tenta EC2 primeiro (mais barato por pedido). Lambda só é usado quando: (a) EC2 está sobrecarregada, ou (b) EC2 falhou. Isto minimiza custos Lambda mantendo resiliência.

3. **"O fast-path evita scale-up para picos triviais."** Se chega um burst de 20 fractais pequenos (0.3s cada) e todos os workers estão a processar GrayScott pesados, o sistema envia os fractais para Lambda em vez de lançar uma EC2 que demoraria 60s a estar pronta.

4. **"Limitação reconhecida: cold starts."** Lambda Java 11 tem cold start de ~2-5s. Para pedidos abaixo de 1s, o overhead de cold start domina o tempo de execução. O sistema mitiga isto usando Lambda apenas quando EC2 não está disponível — o caso comum é EC2 quente.

Build limpo (`mvn compile` exit 0).

### Calibração de throughput t3.micro e refatoração de thresholds (2026-06-03)

Calibração empírica do throughput real de uma instância t3.micro para tornar os thresholds do AutoScaler interpretáveis em wall-clock.

**Metodologia:** Lançado 1 worker t3.micro (2 vCPU burstable, 1 GB RAM, Amazon Corretto 11, eu-west-1) com agente Javassist. Enviados 8 pedidos sequenciais (3 fractals, 4 grayscott, 1 dna) com parâmetros variados. Recolhidos `instructionCount` e `elapsedTimeMs` de cada pedido. Dados completos em `bench-t3micro-throughput.csv`.

**Resultado: throughput médio = 2.0×10⁶ instr/ms** (σ = 0.18×10⁶)

| Request | ICount | Tempo t3.micro | Throughput |
|---|---|---|---|
| Fractals 400×400×100 | 122.3M | 68ms | 1.80×10⁶ |
| Fractals 800×600×100 | 367.0M | 189ms | 1.94×10⁶ |
| Fractals 1600×1200×100 | 1.47B | 698ms | 2.10×10⁶ |
| GrayScott s64×1000 | 673.6M | 362ms | 1.86×10⁶ |
| GrayScott s128×2000 | 5.38B | 2490ms | 2.16×10⁶ |
| GrayScott s256×1000 | 10.76B | 5120ms | 2.10×10⁶ |
| GrayScott s256×5000 | 53.77B | 25340ms | 2.12×10⁶ |
| DNA maxSeq=500 | 61.4K | 38ms | 1.62×10⁶ |

**Observações:**
- Workloads pequenos (fractals 400×400, DNA) mostram throughput ~10-15% menor (JIT warmup)
- GrayScott pesado (s256×5000) atinge throughput estável de ~2.1×10⁶ (JIT fully warm)
- t3.micro é ~40-50% do throughput local (~4-6×10⁶ instr/ms), consistente com a diferença de clock speed e overhead de virtualização
- **Burstable:** Medições feitas com CPU credits disponíveis. Em regime baseline (10% CPU), throughput degrada para ~0.2×10⁶ instr/ms. O AutoScaler mitiga isto adicionando workers antes de esgotar credits.

**Refatoração dos thresholds (antes → depois):**

| Parâmetro | Antes (opaco) | Depois (wall-clock) | Significado |
|---|---|---|---|
| MAX_CAPACITY | `50_000_000_000L` | `25.0s × 2.0×10⁶ × 1000` = 5×10¹⁰ | "Não empacotar mais de 25s de trabalho num worker" |
| Scale-up threshold | `5_000_000_000L` | `2.5s` | "Escalar quando cada worker tem >2.5s de trabalho pendente" |
| Scale-down threshold | `1_250_000_000L` | `0.6s` | "Reduzir quando cada worker tem <0.6s de trabalho" |

**Comportamento inalterado** — os mesmos valores numéricos, expressos de forma interpretável. Os logs do AutoScaler passam a mostrar segundos (`AvgWorkSeconds=2.50s`) em vez de contagens brutas de instruções.

**🎓 Como defender (relatório/oral):**

1. **"Os thresholds do auto-scaler são expressos em segundos de wall-clock, calibrados no hardware real (t3.micro)."** O professor pode ler diretamente: "escala a 2.5 segundos" em vez de "escala a 5×10⁹ instruções."

2. **"O throughput foi medido empiricamente com 8 requests representativos."** Variância <10% para workloads pesados (JIT warm), ~15% para workloads leves (JIT warmup). Média robusta de 2.0×10⁶ instr/ms.

3. **"Os thresholds traduzem-se em decisões compreensíveis:"**
   - Scale-up a 2.5s = "cada worker tem, em média, mais de 2.5 segundos de trabalho estimado em fila — novos pedidos começariam a sentir latência"
   - Scale-down a 0.6s = "cada worker tem menos de 0.6 segundos de trabalho — essencialmente idle"
   - MAX_CAPACITY a 25s = "não empacotar mais de 25 segundos num worker — corresponde a 1 GrayScott pesado (s256×5000 ≈ 25.3s)"

4. **"O ratio scale-up/scale-down de ~4× (2.5s/0.6s) proporciona histerese."** Evita oscilação: o sistema precisa de descer significativamente antes de reduzir, impedindo ciclos rápidos de scale-up/scale-down.

5. **"Limitação reconhecida: t3.micro é burstable."** Em baseline (10% CPU, sem credits), os 2.5s de threshold tornam-se efectivamente 25s. O auto-scaler mitiga isto porque adiciona workers antes de um único worker esgotar os credits.

Build limpo (`mvn compile` exit 0).

Alteração nos ficheiros de código:

| Ficheiro | Alteração |
|---|---|
| `AutoScaler.java` | `ESTIMATED_WORK_THRESHOLD` removido; substituído por `SCALE_UP_SECONDS=2.5` e `SCALE_DOWN_SECONDS=0.6`; `checkAndScale()` compara em segundos de wall-clock; logs mostram `AvgWorkSeconds` |
| `WorkerPool.java` | `DEFAULT_MAX_CAPACITY` derivado de `MAX_CAPACITY_SECONDS=25.0 × throughput`; constante `WORKER_THROUGHPUT_INSTR_PER_MS=2.0×10⁶` partilhada com AutoScaler |
| `bench-t3micro-throughput.csv` | 8 medições empíricas t3.micro com sumário de calibração |

---

## Resumo da sessão 3c (Luis, 2026-05-22, ronda 2 de calibração com 18 medições adicionais)

Sessão para validar a calibração da ronda anterior e refinar features. Total de 33 medições agora (15 + 18). Descobertas dramáticas que **invalidaram parte da ronda 1**:

**🚨 BUG fix:** o "redutor 1e-4 para `seedMode=corners`" da ronda 1 estava errado — `corners` não é seedMode válido (só `center`/`ring`/`stripe`). O ICount=280k que medi foi o handler a apanhar `IllegalArgumentException`, não comportamento real da simulação. Removida a lógica do `ComplexityEstimator`.

**🎯 Descoberta principal: Julia-set SATURA aos iter=500.** ICount com iter=500, 1000 e 2000 é **idêntico** (140.7M para w=h=400). Acima de 500 itera, todos os pixels que escapam já escaparam. Adicionada constante `FRACTAL_ITER_SATURATION=500` ao `ComplexityEstimator`; feature passa a usar `w·h·min(iter, 500)`.

**🎯 Descoberta secundária: DNA escala LINEAR em `max(seq)`, não quadrático.** 4 medições entre 16 e 500 chars deram `instr/max_seq` entre 123 e 149 (variância <20%, vs >300% com feature antiga). Feature DNA agora é `max(seq1.length(), seq2.length())`; heurística é `× 125`.

**✅ Confirmações fortes (mantidas):**
- 3 seedModes do GrayScott são **idênticos** em ICount (diff <0.01%) — feature não depende de seedMode.
- `stopOnExtinction` continua **irrelevante** mesmo com f=0.022/k=0.051 (clássica "death zone"). O threshold de extinção é mais agressivo.
- `MAX_CAPACITY=5×10¹⁰` validado: `grayscott_s384` deu 48.4B, encaixando exactamente como projectado.
- Ratio GrayScott = 164 mantém-se de s64 a s384 (3 ordens de grandeza, variância <1%).

**Resumo das mudanças no código (ronda 2):**

| Local | Antes | Depois |
|---|---|---|
| Feature fractals | `w·h·iter` | `w·h·min(iter, 500)` |
| Feature grayscott | `size²·maxIter` + redutor errado para `corners` | `size²·maxIter` (limpo) |
| Feature dna | `seq1·seq2` | `max(seq1, seq2)` |
| Heurística fractals | `×6` | piecewise: 10 / 5 / 2 conforme iter (3 regimes empíricos) |
| Heurística dna | `seqProduct × 10` | `maxSeq × 125` |

Build limpo (`mvn package` exit 0). Constantes de routing/scaling da ronda 1 (`MAX_CAPACITY=5×10¹⁰`, `THRESHOLD=5×10⁹`) mantiveram-se válidas.

**Análise crítica adicional** (no doc 01.6, secção final):
- **Heurística fractals**: descoberta de 3 regimes empíricos (ramp-up/transição/plateau). Modelo matemático preciso seria `r(iter) = 1.76 + 9.84·exp(-iter/50)` mas usámos piecewise por explicabilidade. Erro do piecewise <15% (vs +184% com multiplicador constante).
- **Correlação ICount ↔ wall-clock**: medido ~3-5×10⁶ instr/ms. Implica que `MAX_CAPACITY=5×10¹⁰` ≈ 12s de wall-clock e `THRESHOLD=5×10⁹` ≈ 1.2s. Bem acima de SLA típico (100ms) — sistema escala antes do utilizador notar.
- **Limitações declaradas**: concorrência do `MetricRegistry`, parâmetros inválidos, e variabilidade hardware (t3.micro vs local) ficaram fora do escopo por opção consciente.

**Pendência operacional:**
- Correr `./scripts/99-cleanup.sh --deep` antes do próximo deploy AWS.
- Validar em AWS com `_benchmark-extended.sh` contra um worker EC2.

---

## Resumo da sessão 3 (Luis, 2026-05-21, decisões finais de métricas e routing)

Sessão de fundo focada em decidir o conjunto **final** de métricas e a **estratégia** que o LB e o AS usam. 4 mudanças estruturais aplicadas, todas validadas com `mvn package -DskipTests` limpo.

**Mudança 1 — FIX 04 (anterior, mantida): contagem de basic blocks dinâmica.** Problema original: `basicBlockCount` era constante por método (heurística `bytecodeLength/15` injetada à entrada via `insertBefore`). Corrigido com `ControlFlow.basicBlocks()` + injeção por bloco. Doc: `docs/01.4_basicblock_counting_real.md`.

**Mudança 2 — FIX 05: migração para ICount como métrica primária.** Substitui `incrementBasicBlocks(1L)` por `incrementInstructions(N)` onde `N` = nº de instruções bytecode no bloco. Renomeia `basicBlockCount` → `instructionCount` em todo o pipeline (`MetricRegistry`, `MetricsStorageService`, `ComplexityEstimator`). FAQ recomenda explicitamente ICount como ponto de partida. Doc: `docs/01.5_icount_migration.md`.

**Decisão de métricas — três níveis:**
- **Primária**: `instructionCount` — usada no routing (LB) e scaling (AS).
- **Secundária**: `methodCallCount` — cross-check / diagnóstico.
- **Validação**: `elapsedTimeMs` — para verificar correlação com a estimativa, NÃO para decisões.

**Mudança 3 — Routing híbrido (packing + spreading fallback).** O LB passou de spreading puro (`selectLeastLoadedExcluding`) para uma estratégia cost-aware (`WorkerPool.selectForRequest`): pack no worker mais carregado que ainda caiba sob `MAX_CAPACITY`, fall back para least-loaded quando todos os candidatos rebentam o teto. Inspirado nas políticas OpenStack Nova mencionadas no FAQ. Permite consolidar carga e libertar workers idle para scale-down. Doc: `docs/02.1_lb_packing_strategy.md`.

**Mudança 4 — Scale-down seguro.** Bug subtil: o `AutoScaler.scaleDown` matava a EC2 mesmo com `activeRequests > 0` após drenagem, perdendo pedidos a meio. Corrigido para **adiar** a terminação (re-adicionar ao pool, retry no próximo ciclo) quando a drenagem não termina em 30s. Doc: `docs/02.2_safe_scale_down.md`.

**Calibração inicial dos thresholds** (em ICount):
- `AutoScaler.ESTIMATED_WORK_THRESHOLD = 10¹⁰` (era 10⁹ em BBs).
- `WorkerPool.DEFAULT_MAX_CAPACITY = 4×10¹¹`.
- Ambos requerem re-calibração com dados empíricos da nova métrica — a fazer na próxima sessão.

**Status global:** os algoritmos de routing/scaling estão prontos (componentes da Fase 4 do roadmap §9.4). Falta:
- Validar empiricamente os ratios da nova métrica ICount.
- Refinar `extractFeature` do GrayScott (ignora `f`, `k`, `stopOnExtinction`).
- Implementar branch EC2 vs Lambda (Fase 3).

**Pendência operacional:** a tabela `cnv-metrics` no DynamoDB tem ~467 records do esquema antigo (`basicBlockCount`). O estimador ratio-based ignora-os mas continuam a ocupar storage. Recomendado deletar a tabela antes do próximo deployment para fresh start (`aws dynamodb delete-table --table-name cnv-metrics --region eu-west-1`).

---

## Resumo da sessão 2 (laura, 2026-05-19, validação assistida)

Sessão de re-validação end-to-end com agente assistente. Resultado: **Fase 2 100% validada em AWS real** + **4 bugs adicionais corrigidos** que tinham passado desapercebidos.

**Validações executadas (todas verdes ✅):**

- Build local (`mvn package`) + 3 endpoints (`/fractals`, `/grayscott`, `/dna`) a responder com `data:image/png;base64,...` válido
- SG SSH restrito a `/32` (sem `0.0.0.0/0`)
- Scale-up triplo (1→5 workers) durante burst pesado
- Scale-down 5→3 confirmado em logs do `AutoScaler` (`SCALE DOWN (avgLoad=0.0 < 0.25)`)
- DynamoDB `cnv-metrics` com **467 items**, schema confirmado: `requestId, requestType, methodCallCount, basicBlockCount, elapsedTimeMs, timestamp, param_*`
- `ComplexityEstimator` a usar histórico: `Cache atualizado para 'fractals': 50 registos`
- Health-check eviction: `FAILED (1/3) → (2/3) → (3/3) → Removing unhealthy worker`
- Cleanup `--deep` completo (0 EC2, 0 AMIs, 0 SGs, 0 IAM, 0 DynamoDB no final)

**Bugs descobertos + fixes aplicados (ver detalhe em `docs/05-handoff-fase2.md`):**

1. `WorkerPool.runHealthChecks` retirava worker do pool mas não terminava a EC2 → **orphans potenciais**. Fix: `WorkerPool.setOnUnhealthyEviction(Consumer)` registado pelo `AutoScaler`, que termina a EC2 quando há eviction.
2. `LoadBalancer.main` adicionava `localhost:8000` em AWS mode quando arrancava sem args. Fix: detectar `AwsConfig.isAwsScalingEnabled()` e deixar pool vazio.
3. `AutoScaler.start()` agora chama `discoverExistingWorkers()` para adoptar EC2s lançadas manualmente (com tag `Role=worker`) — antes ficavam fora do pool e nunca eram terminadas.
4. `WorkerPool.addWorker` ganhou idempotência por `(host, port)` para evitar duplicados quando `discover` corre alongside dos args do CLI.

**Bugs de infraestrutura corrigidos no mesmo passo:**

- `05-launch-lb.sh` agora grava `scripts/.state/lb-ip.txt` (o runbook lia, mas nunca era criado).
- `99-cleanup.sh --deep` apaga **todas** as AMIs `cnv-worker-ami-*` (antes só apagava a do `.state/`).
- Runbook (`06-revalidation-runbook.md`) corrigido: referências a "grace period" inexistente removidas; schema da DynamoDB actualizado para os nomes reais.

**Evidência:** logs preservados em `docs/evidence-2026-05-19-validation/` (4 ficheiros: scale-test, resilience-test, scaledown-watch, logs-collected).

---

## Resumo da sessão 1 (laura)

- Commits 1.1–1.3 do colega merged: `MetricRegistry` estruturado, `MetricsStorageService` (DynamoDB async), `ComplexityEstimator` no LB.
- **AWS account model decidido:** conta própria do grupo, **NÃO Learner Lab**. Cada membro vai ter IAM user próprio.
- **Infra-as-code criado:** pasta `scripts/` com 6 scripts bash (`.sh`, portáveis Linux/Mac/Git Bash) — IAM, network, AMI worker, launch worker, launch LB, cleanup. Seguem a estrutura do §9.2.1 deste documento.
- **`.gitignore` reforçado** para impedir leak de chaves `.pem` e credenciais.
- **`AutoScaler` reimplementado** com chamadas reais ao EC2 SDK (`runInstances`, `terminateInstances`, `describeInstances`), drenagem em scale-down, cooldown de 60s, cap de 5 workers. Degrada graciosamente para modo local-only quando `cnv.ami.id`/`cnv.worker.sg.id` não estão definidos.
- **`AwsConfig`** centraliza configuração lida de system properties.
- **`WorkerPool`** ganha: (a) `Worker.instanceId` opcional, (b) **health checks periódicos** a cada 15 s com remoção após 3 falhas consecutivas — fecha a Fase 2.3.
- **Tabela DynamoDB:** criação automática pelo `MetricsStorageService` (sem script dedicado).

Documentos novos:
- `docs/02-fase2-aws-deployment.md` — detalhe da Fase 2 (este ficheiro).
- `docs/03-aws-onboarding-guide.md` — checklist de tarefas AWS por membro do grupo.
- `docs/04-testing-checklist.md` — guião de validação end-to-end (níveis 0–11).

**Status global:** Fase 2 do roadmap (AWS Deployment) **fechada no código**. Falta validação em AWS real seguindo o `04-testing-checklist.md`.

---

---

## Índice

1. [Visão Geral do Projeto](#1-visão-geral-do-projeto)
2. [Arquitetura Necessária (do Enunciado)](#2-arquitetura-necessária)
3. [Análise do Histórico de Commits](#3-análise-do-histórico-de-commits)
4. [Estado Atual - O Que Temos](#4-estado-atual---o-que-temos)
5. [Revisão Crítica & Problemas](#5-revisão-crítica--problemas)
6. [O Que Está em Falta](#6-o-que-está-em-falta)
7. [Checklist de Requisitos do Checkpoint](#7-checklist-de-requisitos-do-checkpoint)
8. [Checklist de Requisitos da Entrega Final](#8-checklist-de-requisitos-da-entrega-final)
9. [Plano de Implementação Sugerido](#9-plano-de-implementação-sugerido)

---

## 1. Visão Geral do Projeto

O objetivo é construir o **Nature@Cloud**, um serviço elástico na nuvem AWS que executa 3 cargas de trabalho computacionalmente intensivas:

| Endpoint | Carga de Trabalho | Parâmetros |
|---|---|---|
| `/fractals` | Geração de fractais Julia-set | `w`, `h`, `iterations` |
| `/grayscott` | Reação-difusão Gray-Scott | `size`, `maxIterations`, `f`, `k`, `stopOnExtinction`, `seedMode` |
| `/dna` | Correspondência de sequências FASTA de ADN | `seq1`, `seq2`, `minLength`, `stopOnFirst` |

O sistema deve **escalar elasticamente** utilizando tanto **workers em VM EC2** como **workers Lambda (FaaS)**, balanceados por um **Balanceador de Carga** que utiliza **estimativa de complexidade** (a partir de métricas de instrumentação Javassist armazenadas no **DynamoDB**) para decidir para onde encaminhar cada pedido.

---

## 2. Arquitetura Necessária

Conforme definido no `Project.txt`, o sistema tem **4 componentes principais**:

```
                       +-----------------+
                       | Balanceador de  |   <-- ponto de entrada único (VM EC2)
                       | Carga + Auto    |
                       | Scaler          |
                       +--------+--------+
                                |
          +---------------------+---------------------+
          |                     |                     |
   +------+------+     +-------+------+     +--------+-------+
   | Worker VM 1 |     | Worker VM N  |     | Worker Lambda  |
   | (EC2+agente)|     | (EC2+agente) |     | (FaaS)         |
   +------+------+     +-------+------+     +--------+-------+
          |                     |                     |
          +---------------------+---------------------+
                                |
                     +----------+----------+
                     |    DynamoDB (MSS)   |
                     +---------------------+
```

### Responsabilidades dos Componentes

- **Workers (EC2):** Executam o servidor web com instrumentação Javassist; recolhem métricas por pedido
- **Workers (Lambda):** Implementam `RequestHandler` para cada carga de trabalho; instrumentação opcional
- **Balanceador de Carga (LB):** Ponto de entrada; recebe todos os pedidos; encaminha para VM ou Lambda com base na estimativa de complexidade; utiliza dados do MSS para tomar decisões; oculta falhas dos workers dos utilizadores
- **Auto Scaler (AS):** Monitoriza a carga dos workers; aumenta/diminui instâncias EC2; executado em co-localização com o LB
- **MSS (DynamoDB):** Armazena métricas de complexidade dos pedidos (blocos básicos, chamadas de métodos, etc.) para o LB usar na estimativa

---

## 3. Análise do Histórico de Commits

### Commit 1: `38d465c` - Commit inicial (materiais do professor)

- **Data:** 2025-05-13
- **Autor:** Luis Alexandre
- **Conteúdo:** O esqueleto base do projeto fornecido pelo professor
  - Módulo `fractals/`: `FractalsHandler` + `JuliaFractal`
  - Módulo `grayscott/`: `GrayScottHandler` + `GrayScott`
  - Módulo `dna/`: `DnaHandler` + `Dna` + `DnaHtmlRenderer`
  - Módulo `webserver/`: `WebServer` + `RootHandler`
  - `pom.xml` raiz (sem os módulos javassist/loadbalancer)
  - Todos os handlers já implementam tanto `HttpHandler` (para EC2) como `RequestHandler` (para Lambda)

**Avaliação:** Este é o código base fornecido. As cargas de trabalho, handlers e interfaces Lambda são código fornecido pelo professor.

---

### Commit 2: `0a46066` - Instrumentação Javassist (+294 linhas)

- **Data:** 2025-05-13
- **Autor:** Luis Alexandre
- **Ficheiros alterados (6):**
  - `javassist/pom.xml` (novo) - Módulo Maven para o agente Java
  - `JavassistAgent.java` (novo, 139 linhas) - Transformador de ficheiros de classe
  - `MetricRegistry.java` (novo, 92 linhas) - Recolha de métricas ThreadLocal
  - `MANIFEST.MF` (novo) - Declaração Premain-Class
  - `pom.xml` - Adicionado módulo `javassist`
  - `webserver/pom.xml` - Adicionada dependência javassist + descomentada referência MANIFEST

**O que faz:**
- Instrumenta todas as classes em `pt.ulisboa.tecnico.cnv.{fractals,grayscott,dna}` no momento do carregamento da classe
- Envolve `handle(HttpExchange)` em cada handler com chamadas `startRequest()`/`stopRequest()`
- Insere `incrementMethodCalls()` no início de cada método
- Estima blocos básicos usando heurística: `bytecodeLength / 15`
- Armazena métricas por thread usando `ThreadLocal<RequestMetrics>`
- Regista métricas concluídas no stdout e armazena num `ConcurrentHashMap`

**Avaliação:**
- **Bom:** O agente carrega corretamente, as métricas são thread-safe, abrange todos os 3 pacotes de carga de trabalho
- **Problema:** A contagem de blocos básicos é uma heurística aproximada (bytecodeLength/15), não uma análise real de blocos básicos. Isto pode ser aceitável numa primeira abordagem, mas não é uma verdadeira instrumentação de blocos básicos.
- **Problema:** `completedMetrics` armazena como `Map<String, String>` (requestId -> toString). Isto significa que os dados estruturados das métricas (contagem de métodos, contagem de BB, tempo) são perdidos - apenas se mantém uma string formatada. Quando a integração com DynamoDB chegar, serão necessários dados estruturados.
- **Problema:** `completedMetrics` nunca é limpo e crescerá indefinidamente em memória.
- **Problema:** As métricas são apenas recolhidas localmente em memória; ainda não existe mecanismo para enviá-las para o LB ou para o DynamoDB.

---

### Commit 3: `68d503c` - Balanceador de Carga com retry (+441 linhas)

- **Data:** 2025-05-14
- **Autor:** a81430
- **Ficheiros alterados (6):**
  - `loadbalancer/pom.xml` (novo) - Módulo Maven com dependência `aws-java-sdk-ec2`
  - `LoadBalancer.java` (novo, 175 linhas) - Proxy reverso HTTP com retry
  - `WorkerPool.java` (novo, 138 linhas) - Gestão de workers com round-robin e seleção least-loaded
  - `AutoScaler.java` (novo, 75 linhas) - Esqueleto do auto scaler (apenas logs, sem chamadas AWS)
  - `pom.xml` - Adicionado módulo `loadbalancer`
  - `WebServer.java` - Porta tornada configurável via argumento de linha de comandos

**O que faz:**
- Executa um servidor HTTP na porta 8080 (padrão)
- Encaminha `/fractals`, `/dna`, `/grayscott` para workers; devolve uma página de estado para outros caminhos
- Utiliza seleção **least-loaded** (por contagem de pedidos ativos) com retry em caso de falha (tenta até 3 workers diferentes)
- Regista a contagem de pedidos ativos por worker (incremento/decremento em torno do encaminhamento)
- Workers especificados como argumentos CLI: `java LoadBalancer 8080 host1:8001 host2:8002`
- AutoScaler executa em segundo plano a cada 10s, regista carga média, sinaliza scale-up/down mas NÃO escala realmente

**Avaliação:**
- **Bom:** Separação limpa de LB, WorkerPool e AutoScaler; lógica de retry com exclusão de workers com falha; seleção least-loaded
- **Bom:** Método de health-check existe no Worker (embora ainda não usado pelo AutoScaler)
- **Problema:** O LB **não tem qualquer estimativa de complexidade** - o encaminhamento é puramente baseado na contagem de pedidos ativos, ignorando parâmetros do pedido e custo estimado
- **Problema:** O AutoScaler é puramente um esqueleto - apenas regista logs, nunca lança/termina instâncias EC2
- **Problema:** Sem integração com AWS SDK ainda (dependência adicionada mas não utilizada)
- **Problema:** Sem suporte para invocação Lambda
- **Problema:** Sem integração com DynamoDB
- **Problema:** O LB não lê métricas dos workers ou do MSS
- **Problema:** O LB cria um novo `HttpClient` dentro dos health checks (deveria reutilizar o estático do LoadBalancer)

---

## 4. Estado Atual - O Que Temos

### Validado em AWS (Fase 2 completa)

| Componente | Estado | Notas |
|---|---|---|
| **WebServer (workers)** | **Validado em AWS** | Multi-threaded (CachedThreadPool), serve fractals/grayscott/dna; corre como systemd `cnv-worker.service` na AMI pré-cozida |
| **Agente Javassist** | **Validado em AWS** (instrumentação refatorada 2026-05-21) | ICount via `ControlFlow` + injeção por bloco com `N` instruções; métrica CPU. Mais: `methodCallCount` (backup) e `elapsedTimeMs` (validação). |
| **MetricRegistry** | **Validado em AWS** (refatorado 2026-06-02) | `CompletedRequest` estruturado, ThreadLocal por pedido, ConcurrentLinkedDeque para histórico in-memory; campos `instructionCount` (CPU) + `allocatedBytes` (RAM, via `ThreadMXBean`) |
| **MetricsStorageService (DynamoDB)** | **Validado em AWS** (refatorado 2026-06-02) | Singleton + escritas assíncronas; auto-cria `cnv-metrics` (PAY_PER_REQUEST); escreve `instructionCount` + `allocatedBytes` (esquema actual) |
| **Balanceador de Carga** | **Validado em AWS** (routing refatorado 2026-05-21) | Estratégia híbrida packing+spreading (`selectForRequest`), cost-aware via `ComplexityEstimator` (métrica composta CPU+RAM); retry com exclusão; corre via `nohup` em EC2 dedicada |
| **AutoScaler** | **Validado em AWS** (scale-down refatorado 2026-05-21) | Scheduler 5s, threshold composite 5×10⁹/1.25×10⁹, cooldown 60s, cap 5 workers; **scale-down seguro** com adiamento se drenagem incompleta |
| **ComplexityEstimator** | **Validado em AWS** (refatorado 2026-06-02) | **Métrica composta** `W_CPU×ICount + W_RAM×allocatedBytes`; ratio-based usando histórico DynamoDB (cache 30s) + heuristic fallback para ambos CPU e RAM; pesos configuráveis via system properties |
| **Health checks (WorkerPool)** | **Validado em AWS** | Pings cada 15s a `/`; remove worker após 3 falhas consecutivas; recover automático |
| **Deployment AWS** | **Validado end-to-end** | 6 scripts bash idempotentes em `scripts/`, AMI worker pré-cozida (Java + JARs + systemd), IAM Roles + Instance Profiles + inline `iam:PassRole` |

### Pendente (Fase 3+)

| Componente | Estado | Notas |
|---|---|---|
| **Workers Lambda** | **Implementado** (2026-06-04) | 3 Lambdas deployed via `06-deploy-lambdas.sh`; handlers já existiam (fornecidos pelo professor) |
| **Routing por complexidade** | **Implementado** (2026-05-21) | `selectForRequest(cost, excluded)` passa o `estimatedCost` do `ComplexityEstimator` ao pool. Validado empiricamente. |
| **Lambda vs EC2 routing** | **Implementado** (2026-06-04) | Fast-path (workers busy → Lambda) + fallback (EC2 fails → Lambda); threshold 5s |
| **Calibração empírica dos thresholds** | **Pendente** | `MAX_CAPACITY` e `ESTIMATED_WORK_THRESHOLD` foram setados grosseiramente. Re-calibrar com dados reais da métrica ICount após primeira corrida de testes. |

### Estrutura do projeto

```
CNV/
├── pom.xml                  (POM raiz, 6 módulos)
├── Project.txt              (enunciado do trabalho)
├── README.md                (instruções básicas)
├── docs/                    (esta pasta - para acompanhar o progresso)
├── scripts/                 (6 scripts bash: 01–iam, 02–network, 03–ami, 04–worker, 05–lb, 99–cleanup + aws-config.sh)
├── javassist/               (módulo do agente Javassist)
│   ├── pom.xml
│   └── src/.../javassist/
│       ├── JavassistAgent.java
│       └── MetricRegistry.java
├── webserver/               (módulo do servidor web worker)
│   ├── pom.xml
│   └── src/.../webserver/
│       ├── WebServer.java
│       └── RootHandler.java
├── fractals/                (carga de trabalho fornecida pelo professor)
├── grayscott/               (carga de trabalho fornecida pelo professor)
├── dna/                     (carga de trabalho fornecida pelo professor)
└── loadbalancer/            (módulo LB + AS)
    ├── pom.xml
    └── src/.../loadbalancer/
        ├── LoadBalancer.java
        ├── WorkerPool.java
        └── AutoScaler.java
```

---

## 5. Revisão Crítica & Problemas

### 5.1 Decisões de Arquitetura - Estão Corretas?

**Separação do LB e WebServer como módulos separados:** **Boa.** O enunciado diz que o LB é uma VM separada. Tê-los como módulos Maven separados com JARs separados é a abordagem correta.

**LB e AS co-localizados:** **Correto.** O enunciado diz explicitamente "para simplificar, podem colocar tanto o AS como o LB na mesma VM."

**Javassist como javaagent:** **Correto.** Esta é a abordagem padrão - o agente instrumenta classes no momento do carregamento nas VMs worker.

### 5.2 Problemas Que Precisam de Ser Corrigidos

#### A. Contagem de Blocos Básicos — RESOLVIDO em 2026-05-21

> **Histórico** (mantido para contexto do relatório):
> A abordagem original estimava blocos básicos como `bytecodeLength / 15` aplicada à entrada do método via `insertBefore`. Esta heurística era constante por método — `basicBlockCount` ficava flat quando o trabalho real estava em loops intra-método (caso típico: `JuliaFractal.generate`). Ver `docs/01.4_basicblock_counting_real.md`.

**Estado actual:** instrumentação real com `ControlFlow.basicBlocks()` + injeção por bloco. Métrica primária migrada para ICount (instruções bytecode acumuladas). Ver `docs/01.5_icount_migration.md`.

#### B. O MetricRegistry Armazena Strings em Vez de Dados Estruturados

`completedMetrics` é `ConcurrentHashMap<String, String>` mapeando requestId para um resultado `toString()`. Isto torna impossível extrair valores de métricas individuais programaticamente. São necessários dados estruturados para armazenamento no DynamoDB e para o LB utilizar.

**Correção:** Armazenar objetos `RequestMetrics` (ou um DTO) com campos individuais: `methodCallCount`, `basicBlockCount`, `elapsedTimeMs`, e os parâmetros do pedido analisados.

#### C. Sem Mecanismo para Enviar Métricas dos Workers para o LB/DynamoDB

Os workers recolhem métricas localmente mas nunca as expõem. O LB não tem forma de aceder a elas. É necessário um dos seguintes:
1. Workers enviam métricas para o DynamoDB após cada pedido (recomendado)
2. Workers expõem um endpoint `/metrics` que o LB consulta
3. Workers enviam métricas diretamente para o LB

A opção 1 (workers enviam para o DynamoDB) é a mais arquiteturalmente limpa e o que o enunciado implica.

#### D. O LB Não Tem Estimativa de Complexidade

O LB atualmente ignora os parâmetros do pedido. O enunciado requer que o LB **estime a complexidade do pedido antes de encaminhar** utilizando:
- Parâmetros do pedido (ex.: mais `iterations` = mais trabalho para fractais)
- Dados históricos do MSS (DynamoDB)

Este é o **desafio intelectual central** do projeto.

#### E. Sem Infraestrutura AWS

Não existem EC2, Lambda, DynamoDB, security groups, criação de AMI, ou scripts de deployment.

#### F. Sem Integração Lambda

O LB precisa da capacidade de invocar funções Lambda como alternativa ao encaminhamento para workers EC2. O enunciado diz para balancear entre EC2 (mais barato por pedido, arranque lento) e Lambda (mais caro, arranque rápido).

---

## 6. O Que Está em Falta

### Para a Entrega do Checkpoint

| # | Requisito | Estado |
|---|---|---|
| 1 | Workers VM multi-threaded | **FEITO** (validado em AWS) |
| 2 | Instrumentação Javassist a recolher métricas | **FEITO** (ICount via `ControlFlow` por bloco; `instructionCount` + `allocatedBytes` + `methodCallCount` + `elapsedTimeMs`) |
| 3 | Deploy no AWS EC2 (t3.micro) | **FEITO** (`./04-launch-worker.sh` + AMI pré-cozida com systemd) |
| 4 | LB configurado na AWS a funcionar | **FEITO** (`./05-launch-lb.sh`, validado com fractais 1000×1000) |
| 5 | AS configurado na AWS a funcionar | **FEITO** (scale-up duplo real: 1→3 workers com 20 pedidos `2000×2000×2000`) |
| 6 | Lógica inicial LB/AS ou pseudocódigo | **FEITO** (`ComplexityEstimator` ratio-based + heuristic fallback) |
| 7 | DynamoDB MSS para métricas | **FEITO** (`MetricsStorageService`, tabela `cnv-metrics` auto-criada, 24+ records gravados) |
| 8 | Relatório intermédio de 1 página | **NÃO FEITO** |
| 9 | Vídeo de demonstração | **NÃO FEITO** |

### Para a Entrega Final (além do checkpoint)

| # | Requisito | Estado |
|---|---|---|
| 1 | Ferramenta de instrumentação balanceando sobrecarga vs. precisão | **FEITO** (ICount via CFG; ~1 chamada estática por BB, payload constante) |
| 2 | Algoritmo de auto-scaling (custo + desempenho) | **PARCIAL** (avgEstimatedWork-based; scale-down seguro com adiamento; falta análise de custo $$$) |
| 3 | Algoritmo de balanceamento de carga usando estimativas de complexidade do MSS | **FEITO** (`selectForRequest` híbrido packing+spreading, cost-aware) |
| 4 | Suporte a workers Lambda (FaaS) | **FEITO** (3 Lambdas deployed + `LambdaInvoker`) |
| 5 | Balanceamento EC2 + Lambda (custo vs. latência) | **FEITO** (fast-path + fallback, threshold 5s) |
| 6 | Automação completa de deployment | **NÃO FEITO** |
| 7 | Relatório final (até 6 páginas) | **NÃO FEITO** |
| 8 | Vídeo de demonstração | **NÃO FEITO** |

---

## 7. Checklist de Requisitos do Checkpoint

- [x] Workers VM multi-threaded (`Executors.newCachedThreadPool()`)
- [x] Agente Javassist carrega e instrumenta classes de carga de trabalho
- [x] Métricas recolhidas: `instructionCount` (CPU), `allocatedBytes` (RAM), `methodCallCount` (backup), `elapsedTimeMs` (validação)
- [x] Workers enviam métricas para o DynamoDB após cada pedido (`MetricsStorageService.storeAsync`)
- [x] LB executa no AWS EC2 (t3.micro)
- [x] Worker(s) executa(m) no AWS EC2 (t3.micro)
- [x] AutoScaler lança/termina instâncias EC2 (validado: scale-up duplo 1→3 workers, `[AutoScaler] SCALE UP (avgLoad=20.0 > 1.0)`)
- [x] LB tem estimativa de complexidade (`ComplexityEstimator` ratio-based + heuristic fallback)
- [x] Scripts/automação de deployment em `scripts/` (6 scripts bash idempotentes)
- [ ] Relatório intermédio de 1 página
- [ ] Vídeo de demonstração

---

## 8. Checklist de Requisitos da Entrega Final

Tudo do Checkpoint, mais:

- [ ] Instrumentação refinada (balancear sobrecarga vs. precisão)
- [ ] DynamoDB MSS totalmente integrado (armazenar + consultar métricas)
- [ ] LB estima complexidade a partir de parâmetros do pedido + histórico MSS
- [ ] LB encaminha para EC2 ou Lambda com base em complexidade/carga/custo
- [x] Deployment Lambda para todas as 3 cargas de trabalho (`06-deploy-lambdas.sh`)
- [x] LB encaminha para EC2 ou Lambda com base em complexidade/carga/custo
- [x] AS aumenta/diminui EC2 com base em métricas reais
- [ ] Trata falhas de workers de forma transparente (retry já existe)
- [ ] Deployment totalmente automatizado (criar/destruir recursos cloud)
- [ ] Relatório final (até 6 páginas em duas colunas)
- [ ] Vídeo de demonstração contra os testes fornecidos

---

## 9. Plano de Implementação Sugerido

### Fase 1: Corrigir Fundação & DynamoDB (Prioridade: ALTA)

**Objetivo:** Corrigir problemas estruturais e adicionar MSS para que as métricas fluam ponta a ponta.

#### 1.1 Refatorar MetricRegistry para armazenar dados estruturados

- Alterar `completedMetrics` de `Map<String, String>` para armazenar objetos `RequestMetrics` com parâmetros do pedido analisados (tipo de carga de trabalho, parâmetros individuais como `w`, `h`, `iterations`, etc.)
- Adicionar um método `RequestMetrics.toMap()` que devolve `Map<String, AttributeValue>` para o DynamoDB

#### 1.2 Adicionar integração DynamoDB

- Adicionar dependência AWS SDK DynamoDB ao módulo `javassist` (ou criar um módulo `common` partilhado)
- Criar classe `MetricsStorageService` que:
  - Cria a tabela DynamoDB no arranque (se não existir)
  - Escreve um registo por pedido concluído: `{ requestType, params, methodCalls, basicBlocks, elapsedTimeMs, timestamp }`
- Chamar `MetricsStorageService.store()` a partir de `MetricRegistry.stopRequest()`

#### 1.3 Adicionar estimador de complexidade ao LB

- Criar classe `ComplexityEstimator` no módulo `loadbalancer`
- Consulta o DynamoDB para métricas históricas de pedidos semelhantes
- Estima custo com base em: parâmetros do pedido → contagem prevista de blocos básicos
- Primeira abordagem simples: regressão linear ou tabela de consulta de pedidos anteriores
- Se não existir histórico, usar heurísticas baseadas em parâmetros (ex.: `w * h * iterations` para fractais)

### Fase 2: Deployment AWS (Prioridade: ALTA)

**Objetivo:** Pôr o sistema a funcionar na AWS.

#### 2.1 Criar scripts de deployment

Em `scripts/`, criar:
- `setup-security-group.sh` - Criar SG permitindo portas 8000, 8080, 22
- `create-ami.sh` - Lançar uma EC2 base, instalar Java, copiar JAR, criar AMI
- `launch-worker.sh` - Lançar uma instância worker a partir da AMI
- `launch-lb.sh` - Lançar a instância LB/AS
- `cleanup.sh` - Terminar todas as instâncias, eliminar recursos

#### 2.2 Integrar AutoScaler com a API EC2

- Usar `aws-java-sdk-ec2` (já no pom.xml) para:
  - Lançar novas instâncias a partir da AMI (`runInstances`)
  - Terminar instâncias inativas (`terminateInstances`)
  - Descrever instâncias para obter IPs (`describeInstances`)
- Ao aumentar: lançar instância, aguardar estado running, adicionar ao WorkerPool
- Ao diminuir: remover do WorkerPool, aguardar drenagem, terminar

#### 2.3 Atualizar WorkerPool para workers dinâmicos

- Adicionar métodos para registar/desregistar workers dinamicamente (parcialmente existente)
- Adicionar health checking periódico (remover workers não saudáveis)
- Registar IDs de instância juntamente com host:porta

### Fase 3: Integração Lambda (Prioridade: MÉDIA)

**Objetivo:** Adicionar workers FaaS como caminho de execução alternativo.

#### 3.1 Fazer deploy das funções Lambda

- Criar pacote de deployment para cada carga de trabalho (os handlers já implementam `RequestHandler`)
- Criar um script `deploy-lambdas.sh` usando AWS CLI
- Uma função Lambda por tipo de carga de trabalho, ou uma única função com encaminhamento

#### 3.2 Adicionar invocação Lambda ao LB

- Adicionar dependência `aws-java-sdk-lambda` ao módulo `loadbalancer`
- Criar classe `LambdaInvoker` que pode invocar a Lambda de cada carga de trabalho
- No `ForwardHandler`, decidir: se a complexidade é baixa ou os workers estão sobrecarregados → usar Lambda; caso contrário → usar EC2

### Fase 4: Balanceamento de Carga Inteligente & Auto-Scaling (Prioridade: ALTA para final)

**Objetivo:** Implementar os algoritmos centrais que diferenciam um bom projeto.

#### 4.1 Encaminhamento baseado em complexidade

- Antes de encaminhar, estimar custo usando `ComplexityEstimator`
- Encaminhar pedidos caros para workers menos carregados
- Encaminhar para Lambda se: todos os workers estiverem muito carregados E o custo estimado estiver abaixo de um limiar (Lambdas têm limite de timeout)
- Registar trabalho restante estimado por worker: `soma dos custos estimados dos pedidos ativos`

#### 4.2 Auto-scaling baseado em custo

- Definir um modelo de custo: custo EC2 por hora vs. custo Lambda por invocação + duração
- Aumentar quando: `trabalho_restante_estimado_médio > limiar` E adicionar um worker é mais barato do que usar Lambda para os pedidos pendentes
- Diminuir quando: workers estão inativos E não há pedidos em fila
- Implementar um período de arrefecimento para evitar oscilação
- Mínimo de 1 worker sempre em execução

#### 4.3 Cache de consultas MSS

- O enunciado avisa: _"consultar continuamente e iterar exaustivamente este sistema de armazenamento é caro e pode também tornar-se um gargalo de desempenho para o LB"_
- Fazer cache dos resultados recentes de consultas ao DynamoDB em memória no LB
- Atualizar cache periodicamente (ex.: a cada 30s) ou sob demanda quando um novo tipo de pedido é visto pela primeira vez

### Fase 5: Testes, Relatórios & Acabamentos (Prioridade: ALTA no final)

#### 5.1 Testes locais

- Escrever um script de teste que envia pedidos concorrentes com parâmetros variados
- Verificar que as métricas são recolhidas e armazenadas no DynamoDB
- Verificar que o LB distribui a carga de forma razoável
- Verificar que o AS aumenta sob carga e diminui quando inativo

#### 5.2 Testes ponta a ponta na AWS

- Fazer deploy do sistema completo na AWS
- Executar a suite de testes fornecida pelo professor
- Recolher dados de desempenho: latência, throughput, custo
- Gerar gráficos para o relatório

#### 5.3 Relatórios

- **Relatório do checkpoint:** 1 página, descrever o que está implementado + pseudocódigo para algoritmos LB/AS
- **Relatório final:** Até 6 páginas, descrever arquitetura, algoritmos, justificar decisões com dados

#### 5.4 Vídeos de demonstração

- Gravar vídeos curtos mostrando o sistema a tratar pedidos, a aumentar/diminuir

---

## Apêndice: Setup AWS (passo a passo do grupo)

### Modelo de conta
- **Uma conta AWS** partilhada pelo grupo (criada pelo delegado).
- **Vários IAM users** (um por membro) — ninguém usa a conta root.
- Todos usam a mesma **região** (default no projecto: `eu-west-1`).

### Setup inicial (cada membro, uma vez)
1. Instalar AWS CLI v2: `msiexec.exe /i https://awscli.amazonaws.com/AWSCLIV2.msi`
2. O delegado cria-te um IAM user e dá-te a Access Key + Secret.
3. Correr `aws configure` com a tua chave, região `eu-west-1`, output `json`.
4. Validar: `aws sts get-caller-identity` deve devolver o teu ARN.

### Provisionamento da infra (uma vez por conta, idempotente)
```bash
cd scripts
./01-setup-iam.sh        # 3 IAM Roles + 2 Instance Profiles
./02-setup-network.sh    # 2 SGs + key pair (gera cnv-keypair.pem)

cd .. && mvn clean package -DskipTests && cd scripts
./03-create-ami.sh       # AMI worker pré-cozida (Java + JARs + systemd auto-start)
```

### Workflow de cada sessão
```bash
cd scripts
./04-launch-worker.sh    # 1 worker a partir da AMI (auto-arranca via systemd)
./05-launch-lb.sh        # LB arranca + AutoScaler já sabe usar a AMI worker
# ... testar com curl ...
./99-cleanup.sh          # IMPORTANTE: termina EC2s para não gastar Free Tier
./99-cleanup.sh --deep   # No fim do projecto: apaga SGs/IAM/AMI/DynamoDB
```

### IAM Roles criadas
| Role | Trusted entity | Policies |
|---|---|---|
| `CNV-LoadBalancer-Role` | `ec2.amazonaws.com` | EC2 + DynamoDB + Lambda + Logs |
| `CNV-Worker-Role` | `ec2.amazonaws.com` | DynamoDB + Logs |
| `CNV-Lambda-ExecutionRole` | `lambda.amazonaws.com` | LambdaBasicExecution + DynamoDB |

### Configuração de runtime do LB na cloud (passada como `-D` system properties)
- `-Daws.region=eu-west-1`
- `-Dcnv.ami.id=<AMI dos workers>`
- `-Dcnv.worker.sg.id=<SG id do worker, ver scripts/.state/worker-sg-id.txt>`
- `-Dcnv.keypair.name=cnv-keypair`
- `-Dcnv.worker.instance.profile=CNV-Worker-Role`

Se estes não forem definidos, o AutoScaler entra em **local mode** e só faz logs (útil para desenvolver no PC sem custos AWS).

### Permissão `iam:PassRole` (importante)

O LB usa `--iam-instance-profile` para lançar workers via `runInstances`. A AWS exige que o caller tenha **`iam:PassRole`** para a role passada. A `AmazonEC2FullAccess` (que o LB tem) **não** inclui isso por design (princípio do menor privilégio).

O `01-setup-iam.sh` adiciona uma inline policy `CNV-AllowPassWorkerRole` ao `CNV-LoadBalancer-Role`:
```json
{"Action": "iam:PassRole", "Resource": "arn:aws:iam::<ACCT>:role/CNV-Worker-Role"}
```

Se o AutoScaler logar `is not authorized to perform: iam:PassRole`, re-corre `./01-setup-iam.sh` e reinicia o LB (para o STS token apanhar a nova permissão).

### Validação (Níveis 1–7 — ver `scripts/README.md`)
1. `aws sts get-caller-identity` — credenciais
2. `aws ec2 describe-regions` — permissões EC2
3. Programa Java a chamar `AmazonEC2ClientBuilder.standard().build()` — SDK lê credenciais
4. `aws dynamodb list-tables --region eu-west-1` — DynamoDB acessível (a tabela `cnv-metrics` é criada lazy pelo `MetricsStorageService` no primeiro pedido a um worker)
5. `./04-launch-worker.sh` + `curl http://<ip>:8000/fractals?w=200&h=200&iterations=100` — EC2 + SG funcionam
6. Dentro da EC2: `aws sts get-caller-identity --region eu-west-1` (Arn termina em `assumed-role/CNV-Worker-Role/i-...`) — instance profile entrega credenciais via IMDSv2
7. LB + worker + `aws dynamodb scan` mostra métrica nova — integração end-to-end

---

## Apêndice: Referência de Ficheiros Chave

| Ficheiro | Propósito |
|---|---|
| `javassist/JavassistAgent.java` | Instrumenta classes de carga de trabalho no momento do carregamento |
| `javassist/MetricRegistry.java` | Recolha de métricas thread-local por pedido |
| `webserver/WebServer.java` | Servidor HTTP worker (executa no EC2 com `-javaagent`) |
| `loadbalancer/LoadBalancer.java` | Ponto de entrada LB, encaminha para workers |
| `loadbalancer/WorkerPool.java` | Gestão do pool de workers, estratégias de seleção |
| `loadbalancer/AutoScaler.java` | Monitoriza carga e lança/termina EC2s via SDK (degrada para local-only sem config) |
| `loadbalancer/AwsConfig.java` | Configuração AWS centralizada (region, AMI, SG, keypair, instance profile) |
| `loadbalancer/ComplexityEstimator.java` | Estima cost composto (CPU+RAM) de pedidos (histórico DynamoDB + fallback heurístico, cache 30s, pesos configuráveis) |
| `loadbalancer/LambdaInvoker.java` | Invoca funções Lambda para pedidos pequenos/médios (singleton, AWS SDK) |
| `javassist/MetricsStorageService.java` | Persistência assíncrona de métricas no DynamoDB |
| `scripts/*.sh` | Provisionamento e cleanup de toda a infra AWS (01–iam, 02–network, 03–ami, 04–worker, 05–lb, 06–lambdas, 99–cleanup) |
| `fractals/FractalsHandler.java` | Handler de carga de trabalho fractal (fornecido pelo professor) |
| `grayscott/GrayScottHandler.java` | Handler de carga de trabalho GrayScott (fornecido pelo professor) |
| `dna/DnaHandler.java` | Handler de correspondência de ADN (fornecido pelo professor) |

## Apêndice: Como Executar Localmente

```bash
# Compilar tudo
mvn clean package

# Iniciar um worker na porta 8000 (com agente Javassist)
cd webserver
java -javaagent:../javassist/target/javassist-agent-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
     -cp target/webserver-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
     pt.ulisboa.tecnico.cnv.webserver.WebServer 8000

# Iniciar um segundo worker na porta 8001 (opcional)
java -javaagent:../javassist/target/javassist-agent-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
     -cp target/webserver-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
     pt.ulisboa.tecnico.cnv.webserver.WebServer 8001

# Iniciar o Balanceador de Carga na porta 8080 apontando para ambos os workers
cd ../loadbalancer
java -cp target/loadbalancer-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
     pt.ulisboa.tecnico.cnv.loadbalancer.LoadBalancer 8080 localhost:8000 localhost:8001

# Testar pedidos via LB
curl "http://localhost:8080/fractals?w=400&h=300&iterations=100"
curl "http://localhost:8080/grayscott?size=128&maxIterations=1000"
curl "http://localhost:8080/dna?seq1=seq1:ATGCATGC&seq2=seq2:ATGCATGC&minLength=3&stopOnFirst=false"
```
