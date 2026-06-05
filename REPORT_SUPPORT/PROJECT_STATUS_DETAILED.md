# Nature@Cloud — Estado Atual do Projeto (Análise Detalhada)

**Data:** 2026-06-05

Este documento reúne uma análise aprofundada e detalhada do estado atual
do projeto Nature@Cloud com base em todo o código-fonte, documentação e
evidências presentes no workspace. O objetivo é fornecer uma visão
exhaustiva—tecnicamente precisa—para suportar a redação do relatório
final, gravação de vídeo e conclusão das tarefas pendentes.

---

## Sumário executivo

- **Estado geral:** Código implementado para os componentes principais
  (workers EC2, LB, AutoScaler, Javassist agent, MSS/DynamoDB, Lambda
  invocations). Muitas funcionalidades validadas em AWS real.
- **Checkpoint:** Fase 2 (deploy AWS e instrumentação primária) validada.
- **Pendências críticas para entrega final:** automação completa do
  deployment (orquestração única), limpeza / re-calibração do histórico
  DynamoDB (dados legados), testes finais e relatório (6 páginas) + vídeo.
- **Recomendações imediatas:** (1) eliminar tabela `cnv-metrics` legacy
  e recolher novo histórico; (2) executar matriz de benchmarks automatizada
  na AWS (scripts/test) para gerar dados finais; (3) produzir relatório
  final e vídeo de demonstração.

---

## Índice

1. Visão geral do projecto
2. Principais componentes e estado (por componente)
3. Instrumentação e métricas (detalhes técnicos)
4. Estimador de complexidade (algoritmo e heurísticas)
5. Balanceador de carga (algoritmo de routing)
6. Auto-scaler (política e parâmetros calibrados)
7. Integração Lambda (FaaS)
8. Persistência de métricas (DynamoDB — MSS)
9. Benchmarks e evidências empíricas (valores chave)
10. Scripts de deployment e operação
11. Problemas conhecidos, riscos e limitações
12. Tarefas pendentes e plano de prioridades
13. Como reproduzir: comandos e cenários de teste
14. Ficheiros chave e referências

---

## 1. Visão geral do projecto

Nature@Cloud é um serviço para executar cargas de trabalho inspiradas na
Natureza (Julia fractals, Gray-Scott, DNA matching) em ambiente elástico
na AWS. A solução usa **VM workers (EC2)** e **FaaS workers (Lambda)**,
com um **Load Balancer (LB)** que decide, para cada pedido, usar EC2
ou Lambda com base numa estimativa de complexidade baseada em
instrumentação bytecode (Javassist) e histórico guardado no MSS (DynamoDB).

O repositório tem um POM raiz com módulos: `javassist`, `webserver`,
`fractals`, `grayscott`, `dna`, `loadbalancer`.

---

## 2. Principais componentes e estado

Nesta secção registo o estado funcional e os ficheiros principais para cada
componente.

- **Workers (WebServer)**
  - Ficheiro: [webserver/src/main/java/pt/ulisboa/tecnico/cnv/webserver/WebServer.java](webserver/src/main/java/pt/ulisboa/tecnico/cnv/webserver/WebServer.java)
  - Estado: Implementado e validado. Serve `/fractals`, `/grayscott`,
    `/dna` e `/` (root). Configuração por porta; executor `CachedThreadPool`.
  - Nota: worker é executado com `-javaagent` para habilitar a instrumentação.

- **Javassist Agent (instrumentação ICount)**
  - Ficheiro: [javassist/src/main/java/pt/ulisboa/tecnico/cnv/javassist/JavassistAgent.java](javassist/src/main/java/pt/ulisboa/tecnico/cnv/javassist/JavassistAgent.java)
  - Estado: Implementado. Instrumentação por basic block com contagem de
    instruções por bloco (ICount). Injeção de `MetricRegistry.incrementInstructions(N)`
    por entrada de bloco; `incrementMethodCalls()` injetado por método.
  - Observações técnicas: usa `ControlFlow` para obter blocos e injeta os
    payloads em ordem decrescente de offset; reconstrói stack maps.

- **MetricRegistry / RequestMetrics**
  - Ficheiro: [javassist/src/main/java/pt/ulisboa/tecnico/cnv/javassist/MetricRegistry.java](javassist/src/main/java/pt/ulisboa/tecnico/cnv/javassist/MetricRegistry.java)
  - Estado: Implementado. `ThreadLocal<RequestMetrics>` trata contadores;
    snapshot imutável em `CompletedRequest`. Campos: `instructionCount`,
    `methodCallCount`, `allocatedBytes` (ThreadMXBean) e `elapsedTimeMs`.
  - Armazenamento in-memory: `ConcurrentLinkedDeque<CompletedRequest>` limitado
    a 1000 entradas (mais recentes primeiro).

- **MetricsStorageService (DynamoDB — MSS)**
  - Ficheiro: [javassist/src/main/java/pt/ulisboa/tecnico/cnv/javassist/MetricsStorageService.java](javassist/src/main/java/pt/ulisboa/tecnico/cnv/javassist/MetricsStorageService.java)
  - Estado: Implementado. Singleton lazy que tenta ligar ao DynamoDB;
    cria a tabela `cnv-metrics` (PAY_PER_REQUEST) se não existir. Escritas
    são assíncronas (thread daemon).
  - Schema principal: partition key `requestType` (S), sort key `requestId` (S),
    atributos `instructionCount` (N), `allocatedBytes` (N), `methodCallCount` (N),
    `elapsedTimeMs` (N), e parâmetros prefixados `param_*`.

- **ComplexityEstimator**
  - Ficheiro: [loadbalancer/src/main/java/pt/ulisboa/tecnico/cnv/loadbalancer/ComplexityEstimator.java](loadbalancer/src/main/java/pt/ulisboa/tecnico/cnv/loadbalancer/ComplexityEstimator.java)
  - Estado: Implementado. Estratégia em duas camadas: (1) ratio-based a partir
    de histórico (últimos ≤50 registos) com cache local (TTL 30s); (2) heuristic
    fallback se histórico indisponível.
  - Métrica composta: compositeCost = wCpu * instructionCount + wRam * allocatedBytes
    (pesos configuráveis via `-Dcnv.estwork.wcpu` e `-Dcnv.estwork.wram`).

- **Load Balancer (LB)**
  - Ficheiro: [loadbalancer/src/main/java/pt/ulisboa/tecnico/cnv/loadbalancer/LoadBalancer.java](loadbalancer/src/main/java/pt/ulisboa/tecnico/cnv/loadbalancer/LoadBalancer.java)
  - Estado: Implementado. Recebe pedidos, estima custo via `ComplexityEstimator` e
    implementa routing híbrido pack/spread, retry (até 3) e integra Lambda fast-path
    e fallback.

- **WorkerPool**
  - Ficheiro: [loadbalancer/src/main/java/pt/ulisboa/tecnico/cnv/loadbalancer/WorkerPool.java](loadbalancer/src/main/java/pt/ulisboa/tecnico/cnv/loadbalancer/WorkerPool.java)
  - Estado: Implementado. Adição idempotente de workers, `startHealthChecks()`
    (intervalo 15s, remoção após 3 falhas), `selectForRequest(requestCost, excluded)`
    implementa política packing/spreading com `DEFAULT_MAX_CAPACITY`.

- **AutoScaler**
  - Ficheiro: [loadbalancer/src/main/java/pt/ulisboa/tecnico/cnv/loadbalancer/AutoScaler.java](loadbalancer/src/main/java/pt/ulisboa/tecnico/cnv/loadbalancer/AutoScaler.java)
  - Estado: Implementado e validado em AWS. Converte estimativa em segundos
    usando throughput calibrado do t3.micro; escala a cada 5s. Mecanismo de
    scale-down seguro com drenagem e adiamento se não drenar em 30s.

- **LambdaInvoker (FaaS)**
  - Ficheiro: [loadbalancer/src/main/java/pt/ulisboa/tecnico/cnv/loadbalancer/LambdaInvoker.java](loadbalancer/src/main/java/pt/ulisboa/tecnico/cnv/loadbalancer/LambdaInvoker.java)
  - Estado: Implementado. Singleton que invoca funções `cnv-fractals`,
    `cnv-grayscott`, `cnv-dna` via AWS Lambda SDK. LB usa Lambda fast-path e fallback.

- **Scripts de deployment**
  - Pasta: `scripts/` — scripts idempotentes (01-setup-iam.sh, 02-setup-network.sh,
    03-create-ami.sh, 04-launch-worker.sh, 05-launch-lb.sh, 06-deploy-lambdas.sh,
    99-cleanup.sh).
  - Estado: Criados e testados; pipeline validada em AWS (ver `PROJECT_STATUS.md`).

---

## 3. Instrumentação e métricas (detalhes técnicos)

- Métrica primária: **`instructionCount` (ICount)** — soma das instruções
  bytecode executadas nos basic blocks instrumentados. Implementação: por
  bloco, injeção de `incrementInstructions(N)` onde `N` é o número de
  instruções da região (contado antes de qualquer injeção).

- Métrica secundária: **`methodCallCount`** — +1 por entrada de método.

- Métrica RAM: **`allocatedBytes`** — delta de `ThreadMXBean.getThreadAllocatedBytes(threadId)`;
  devolve `-1` se indisponível. Capturada sem instrumentação (built-in JVM counter).

- Snapshot: `MetricRegistry.CompletedRequest` contém
  `{ requestType, parameters, methodCallCount, instructionCount, allocatedBytes, elapsedTimeMs, timestamp }`.

- Persistência: `MetricsStorageService.storeAsync(snapshot)` grava registros no DynamoDB
  (tabela `cnv-metrics`). Escrita assíncrona para não impactar latência.

Observação: dados antigos no DynamoDB usam o esquema legado `basicBlockCount`;
recomenda-se limpar a tabela antes de recolha final para evitar poluição do histórico.

---

## 4. Estimador de complexidade — algoritmo e heurísticas

Descrição resumida:

1. Tenta obter histórico (últimos ≤50 registos) do DynamoDB por `requestType`.
2. Para cada registo histórico computa `ratio_cpu = instructionCount / feature(params)`
   e (se existir) `ratio_ram = allocatedBytes / feature(params)`.
3. Média dos ratios → `avgCpuRatio`, `avgRamRatio` → estimativa: `estimatedICount = avgCpuRatio * featureNew`;
   `estimatedAlloc = avgRamRatio * featureNew`.
4. Combina em `compositeCost = wCpu*estimatedICount + wRam*estimatedAlloc`.
5. Se não houver histórico (ou DynamoDB indisponível) usa heurísticas calibradas.

Features por workload (implementadas):

- **fractals:** feature = `w * h * min(iter, 500)` (saturação empiricamente detectada aos 500 iterações)
- **grayscott:** feature = `size * size * maxIter`
- **dna:** feature = `max(seq1.length(), seq2.length())` (linear no comprimento máximo)

Heurísticas calibradas (fallback):

- fractals: piecewise multiplier por regime de `iter` (<=100 → ×10, <=300 → ×5, >300 → ×2),
  estimatedAlloc ≈ `w*h*33` bytes
- grayscott: multiplier ≈ 164 instr/(cell·iter), estimatedAlloc ≈ `size*size*64` bytes
- dna: multiplier ≈ 125 instr/char, estimatedAlloc ≈ `maxSeq * 800` bytes

Cache: resultados do DynamoDB cacheados 30s por `requestType` para reduzir custo e latência.

---

## 5. Load Balancer — algoritmo de routing

- Principais características:
  - Recebe pedido, parseia `requestType` e `params`.
  - Chama `ComplexityEstimator.estimate()` para obter `estimatedCost` (ICount-composite).
  - Converte `estimatedCost` em segundos wall-clock via throughput calibrado para avaliar elegibilidade Lambda.
  - Lambda fast-path: se `estimatedCostSeconds <= 5.0s` e todos os workers estão > 80% ocupados → invoca Lambda direto.
  - Caso contrário, tenta forward para worker escolhido por `WorkerPool.selectForRequest(estimatedCost, excluded)` com até 3 retries.
  - Se retries esgotarem e Lambda elegível, tenta Lambda fallback.

- Política `selectForRequest` (WorkerPool):
  - Packing: escolher worker mais carregado cujo `projected = currentEstimatedWork + requestCost` fique ≤ `DEFAULT_MAX_CAPACITY`.
  - Spreading fallback: se nenhum worker cumpre cap, escolher least-loaded worker.

Benefício: consolida carga em workers já ocupados para permitir scale-down seguro de nós ociosos.

---

## 6. Auto-scaler — política e parâmetros calibrados

- Parâmetros chave (valores atuais):
  - `CHECK_INTERVAL_SECONDS` = 5s
  - `SCALE_UP_SECONDS` = 2.5s (média de trabalho pendente por worker que dispara scale-up)
  - `SCALE_DOWN_SECONDS` = 0.6s (média que dispara scale-down)
  - `COOLDOWN_MS` = 60_000 ms
  - `MIN_WORKERS` = 1, `MAX_WORKERS` = 5
  - `DRAIN_POLL_ITERATIONS` = 15 (2s each → 30s total de drenagem)

- Throughput calibrado (t3.micro): `WORKER_THROUGHPUT_INSTR_PER_MS` = 2_000_000 instr/ms
  - `DEFAULT_MAX_CAPACITY` derivado: `25s * 2e6 instr/ms * 1000 = 50_000_000_000` instr (5×10^10)

- Política operacional:
  - Average estimated work por worker (transformado em segundos) > 2.5s → scale-up (lançar 1 worker)
  - < 0.6s e > MIN → tentar scale-down com drenagem e terminação segura
  - Descobre EC2s existentes com tags e as adopta no pool (idempotência)

Observações: t3.micro é burstable; em situação sem créditos throughput tende a ~0.2e6 instr/ms.
AutoScaler foi calibrado em regime com credits; considerar a quota de CPU credits para ensaios prolongados.

---

## 7. Integração Lambda (FaaS)

- LambdaInvoker implementado para funções com nomes `cnv-fractals`, `cnv-grayscott`, `cnv-dna`.
- LB usa Lambda como válvula de escape (fast-path quando todos os workers ocupados e pedido curto; fallback quando EC2 falha).
- Considerações: cold starts Java ~2–5s; threshold `LAMBDA_MAX_SECONDS = 5.0s` escolhido para evitar usar Lambda quando a execução excede vantagem do FaaS.

---

## 8. Persistência de métricas (DynamoDB — MSS)

- Tabela: `cnv-metrics` (partition key: `requestType`, sort key: `requestId` = `{timestamp}_{uuid8}`).
- Campos gravados: `instructionCount`, `allocatedBytes`, `methodCallCount`, `elapsedTimeMs`, `timestamp`, `param_*`.
- Criada automaticamente por `MetricsStorageService` se não existir (espera até ACTIVE).

Recomendação: apagar tabela legacy que contém `basicBlockCount` antigo e forçar recolha fresca, para que `ComplexityEstimator` aprenda ratios válidos sem ruído.

---

## 9. Benchmarks e evidências empíricas (valores chave)

- Throughput t3.micro (calibração): **2.0×10^6 instr/ms** (média, σ ≈ 0.18×10^6). Fonte: `bench-t3micro-throughput.csv`.
- GrayScott: **ratio ≈ 164 instr/(cell·iter)** (variância <1% em s64→s384). Fonte: calibração (`docs/01.6_calibration_evidence.md`).
- Fractals: Julia-set **satura** a `iter ≈ 500` (ICount não cresce para iter > 500).
- RAM calibration (2026-06-03):
  - fractals: ≈ **33 B/px**
  - grayscott: ≈ **64 B/cell**
  - dna: ≈ **800 B/char**
- DNA: escala **linear** em `max(seq1.length, seq2.length)` com ratio ≈ 125 instr/char.

Evidências e logs: pasta `docs/evidence-2026-05-21-calibration/`, `benchmarks/bench-*.csv`, `docs/test-report-aws-2026-05-22.md`.

---

## 10. Scripts de deployment e operação

- `scripts/01-setup-iam.sh` — cria IAM roles, policies e acrescenta `iam:PassRole`.
- `scripts/02-setup-network.sh` — cria security groups e keypair.
- `scripts/03-create-ami.sh` — cria AMI worker pré-cozida com Java e JARs.
- `scripts/04-launch-worker.sh` — lança worker a partir da AMI.
- `scripts/05-launch-lb.sh` — lança LB/AS (passa `-D` propriedades necessárias).
- `scripts/06-deploy-lambdas.sh` — deploy/update das Lambdas.
- `scripts/99-cleanup.sh [--deep]` — encerra instâncias e (opcional) apaga recursos AWS.

Nota operacional: recomenda-se correr `99-cleanup.sh --deep` ao terminar para evitar custos.

---

## 11. Problemas conhecidos, riscos e limitações

- **Histórico DynamoDB legacy**: registos antigos com `basicBlockCount` (esquema legado). Recomenda-se apagar tabela antes de recolha final.
- **Quota EC2 / vCPU**: contas novas podem ter quotas pequenas (p.ex. 5 vCPUs). Testes de `MAX_WORKERS=5` podem exceder quota; pedir aumento de quota ou reduzir `MAX_WORKERS` para testes.
- **t3.micro é burstable**: desempenho em baseline pode ser muito inferior; calibrar ensaios longos.
- **ThreadMXBean**: `getThreadAllocatedBytes()` pode não estar disponível em todas as JVMs → `allocatedBytes = -1` e estimador comporta-se corretamente.
- **Lambda cold-start**: invocação Java pode introduzir latência; limiar `5s` é heurístico.
- **Dependência de heurísticas**: heurísticas de fallback são calibradas localmente; idealmente revalidar em AWS para a entrega final.

---

## 12. Tarefas pendentes e plano de prioridades

Prioridade alta — preparar entrega final:

1. **Limpar DynamoDB**: apagar `cnv-metrics` antigo e recolher novo histórico em AWS.
2. **Executar matriz de benchmarks em AWS** (scripts/test) para recolher dados finais (throughput, latências, custo).
3. **Completar automação**: criar um wrapper/Makefile ou script top-level que executa 01→06 em sequência com checks.
4. **Gerar relatório final (até 6 páginas)** e incluir tabelas/figuras (ICount vs elapsed, custo EC2 vs Lambda, scale traces).
5. **Gravar vídeo de demonstração** com os testes oficiais.

Prioridade média — melhorias robustez/observabilidade:

6. Adicionar métricas/monitorização (CloudWatch dashboards) e logs estruturados.
7. Coberturas de teste automatizadas e unit/integration tests para `ComplexityEstimator`.
8. Gerir quotas e custos AWS (script para estimativa de custo por cenário).

---

## 13. Como reproduzir (comandos principais)

Compilar projecto:

```bash
mvn clean package -DskipTests
```

Executar worker local (com agente):

```bash
cd webserver
java -javaagent:../javassist/target/javassist-agent-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
     -cp target/webserver-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
     pt.ulisboa.tecnico.cnv.webserver.WebServer 8000
```

Executar LB local apontando para worker(s):

```bash
cd loadbalancer
java -cp target/loadbalancer-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
     pt.ulisboa.tecnico.cnv.loadbalancer.LoadBalancer 8080 localhost:8000
```

Scripts AWS (exigir AWS credentials configuradas):

```bash
cd scripts
./01-setup-iam.sh
./02-setup-network.sh
./03-create-ami.sh
./04-launch-worker.sh
./05-launch-lb.sh
# deploy lambdas (opcional)
./06-deploy-lambdas.sh
# após testes
./99-cleanup.sh --deep
```

---

## 14. Ficheiros chave e referências rápidas

- Enunciado do projecto: [Project.txt](Project.txt)
- Checkpoint report (LaTeX): [report/checkpoint-report.tex](report/checkpoint-report.tex)
- Roadmap & notas: [docs/00-project-status-and-roadmap_pt.md](docs/00-project-status-and-roadmap_pt.md)
- Instrumentação: [javassist/JavassistAgent.java](javassist/src/main/java/pt/ulisboa/tecnico/cnv/javassist/JavassistAgent.java)
- Metric Registry: [javassist/MetricRegistry.java](javassist/src/main/java/pt/ulisboa/tecnico/cnv/javassist/MetricRegistry.java)
- MSS/DynamoDB: [javassist/MetricsStorageService.java](javassist/src/main/java/pt/ulisboa/tecnico/cnv/javassist/MetricsStorageService.java)
- Complexity Estimator: [loadbalancer/ComplexityEstimator.java](loadbalancer/src/main/java/pt/ulisboa/tecnico/cnv/loadbalancer/ComplexityEstimator.java)
- Load Balancer: [loadbalancer/LoadBalancer.java](loadbalancer/src/main/java/pt/ulisboa/tecnico/cnv/loadbalancer/LoadBalancer.java)
- AutoScaler & WorkerPool: [loadbalancer/AutoScaler.java](loadbalancer/src/main/java/pt/ulisboa/tecnico/cnv/loadbalancer/AutoScaler.java), [loadbalancer/WorkerPool.java](loadbalancer/src/main/java/pt/ulisboa/tecnico/cnv/loadbalancer/WorkerPool.java)
- Lambda invoker: [loadbalancer/LambdaInvoker.java](loadbalancer/src/main/java/pt/ulisboa/tecnico/cnv/loadbalancer/LambdaInvoker.java)
- Benchmarks: `benchmarks/bench-t3micro-throughput.csv`, `benchmarks/bench-ext.csv`
- Evidências: `docs/evidence-2026-05-21-calibration/` e `docs/test-report-aws-2026-05-22.md`

---

## Conclusão curta

O projeto está em excelente estado de maturidade: a instrumentação ICount,
o pipeline de métricas, o LB/AS e a integração Lambda estão implementados
e testados em AWS. As tarefas restantes são maioritariamente de **validação
final**, **limpeza de historial** e **documentação/entrega**. Se quiser, eu
posso (a) apagar e re-colecionar a tabela `cnv-metrics` em AWS (se tiveres
credenciais disponíveis), (b) correr a matriz de benchmarks em AWS e
inserir os resultados neste ficheiro, e (c) ajudar a redigir o relatório
final com secções e figuras já prontas.
