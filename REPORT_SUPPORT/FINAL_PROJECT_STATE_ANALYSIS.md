# Nature@Cloud — Análise Profunda e Detalhada do Estado Atual do Projeto (Fase Final de Entrega)

**Data da análise:** 2026-06-04 (utilizando acesso completo a todo o código fonte, docs/, Project.txt, report/checkpoint-report.tex, PROJECT_STATUS.md, changes-summary.md, benchmarks/, scripts/, evidências e logs gerados).

**Autores da análise:** Grok 4.3 (xAI) — com uso extensivo de ferramentas de exploração (list_dir, read_file em 50+ ficheiros com offset/limit, grep, run_terminal_command para git/FS, spawn_subagent para revisão paralela profunda).

**Objectivo:** Fornecer uma visão *profunda* (sem poupar recursos) para servir de base ao relatório final (≤6 páginas double-column). Todo o conteúdo é factual, com referências a ficheiros:linhas, tabelas de calibração, decisões de design justificadas e análise crítica.

**Nota:** Esta análise baseia-se em ~18 ficheiros .java (únicos sources), 6 módulos Maven, dezenas de docs em docs/ (incluindo 00-project-status-and-roadmap_pt.md com histórico completo de sessões), evidências de calibração/validação AWS, scripts de deployment/testes, e commits git (19 commits principais desde inicial académico até métrica composta + Lambda).

---

## 1. Visão Geral do Projeto e Cumprimento do Enunciado (Project.txt)

**Nome:** Nature@Cloud (Cloud Computing and Virtualization, IST 2025-26, Grupo 35).

**Objectivo (Project.txt §1):** Serviço elástico em AWS para 3 workloads Nature-inspired computacionalmente intensivos:
- `/fractals`: Julia-set (w, h, iterations) → PNG.
- `/grayscott`: Gray-Scott reaction-diffusion (size, maxIterations, f, k, stopOnExtinction, seedMode) → PNG.
- `/dna`: DNA sequence matcher FASTA (seq1, seq2, minLength, stopOnFirst) → HTML/texto com matches.

**Requisitos chave (Project.txt §2-3 + FAQ):**
- Workers: VMs EC2 (t3.micro, instrumentados com Javassist) + FaaS Lambda (512MB, instrumentação opcional). Portabilidade máxima (sem platform-specific nos workers).
- LB: Único entrypoint (VM EC2 com webserver Java); estima complexidade *antes* de encaminhar usando params + MSS (DynamoDB); escolhe worker ou invoca Lambda; esconde falhas (retries).
- AS: Ajusta #workers EC2 com base em métricas/performance/custo; co-localizado com LB permitido.
- MSS: DynamoDB para métricas dinâmicas de complexidade (bytecode instrs/basic blocks, calls, etc.); permite estimativa realista (wall-clock imprevisível sob overcommit).
- Instrumentation: Javassist para métricas dinâmicas (balance overhead vs. precisão); workers enviam métricas para MSS.
- Deployment: Totalmente automatizado via scripts; delete recursos após sessão; t3.micro + Lambda 512MB.
- Final (além de checkpoint): LB/AS com estimativa de complexidade de MSS + params; auto-scaling eficiente custo/perf; balanceamento minimizando custo/latência; relatório + vídeo.

**Estado global de cumprimento:** **Quase 100% implementado e validado end-to-end em AWS real** (múltiplas sessões: 2026-05-19 revalidação, 05-22, 06-02/03/04 com métrica composta + Lambda + calibração). Ver PROJECT_STATUS.md (Níveis 0-7 DONE ✅), docs/00-..._pt.md (checklists), docs/test-report-aws-2026-05-22.md, changes-summary.md, evidence-*/ e benchmarks/.

**Git history (resumo, 19 commits principais):**
- 38d465c: Inicial (materiais professor: handlers + webserver + workloads; handlers já dual HttpHandler + RequestHandler).
- 0a46066: Javassist + MetricRegistry inicial (heurística BB).
- 68d503c: LB básico + WorkerPool + AutoScaler esqueleto (sem AWS real, sem estimativa).
- ed2e895 / 60f5adb / 186a6be: FIX01 (MetricRegistry estruturado), FIX02 (DynamoDB MSS), FIX03 (ComplexityEstimator).
- 98b58d7: Fase2 AWS (scripts, EC2 SDK real no AS, health checks, AMI, discover).
- 7ba960c / cbcbba3: Revalidação sessão2 + 4 bugs fix (orphans, discover, localhost ghost, idempotência addWorker).
- f3935a1 / f43069e / 49ff3bb: Wiring ComplexityEstimator → AS/WorkerPool; calibração ICount 33 medições (rondas 1+2); features refinadas (saturação fractals 500, DNA max(seq), GS ratio 164 estável).
- 04c61b0 / 7dcaffe: Lambda integration (LambdaInvoker + routing fast-path/fallback no LB); scripts/06-deploy-lambdas.sh.
- 89da0d9: Métrica composta CPU+RAM (W_CPU/W_RAM=1.0, ThreadMXBean zero-overhead), calibração RAM 16 pts, thresholds wall-clock (2.5s/0.6s), scripts idempotentes.
- cdb2e71: Testes + small fixes.
- Ver git log --oneline para full.

**Dimensão do codebase (aprox., de list_dir + contagens parciais):**
- 18 ficheiros .java (excluindo target/).
- ~500+ KB sources não-target (Java + XML + sh + md + txt).
- LOC Java: ~2.5k-3k estimado (pequeno mas denso e bem documentado).
- Fat JARs (de targets anteriores + status): webserver ~15MB, javassist-agent ~10MB, loadbalancer ~19MB (com deps AWS SDK + assembly).
- Docs: 15+ .md em docs/ + subpastas evidence + benchmarks/ com CSVs/logs.

---

## 2. Arquitetura Completa e Fluxo de Dados

Ver README.md + 00-project-status-and-roadmap_pt.md §2 + subagent deep map + código:

```
Client (curl / pagina.html / browser)
  └─► LoadBalancer (EC2 t3.micro :8080, HttpServer CachedThreadPool, ForwardHandler)
        ├─ parse path + query → requestType + params
        ├─ ComplexityEstimator.estimate(type, params) → Estimate(cost composite ICount+alloc, source="history"|"heuristic")
        │     (cache 30s TTL por type; Query Dynamo limit=50; retry 60s)
        ├─ estSeconds = cost / (2e6 instr/ms * 1000)
        │     ├─ if (estSeconds <= 5.0 && lambda available && allWorkersBusy(>80% MAX_CAP)) → Lambda fast-path (proativo)
        │     └─ else: WorkerPool.selectForRequest(cost, tried) [híbrido pack/spread]
        │           → forward HttpClient (timeout 120s) → worker:8000 + incrementActive + addEstWork
        │           → retry até 3 (exclui falhados) ou Lambda fallback (reativo, FT)
        │           → finally: removeEstWork + decrementActive
        ├─ AutoScaler (scheduler 5s background) co-localizado
        │     ├─ avgEstWorkSeconds (usando throughput calibrado t3.micro)
        │     ├─ scale-up (>2.5s && <5 workers): runInstances (AMI + tags + profile) → wait IP → addWorker
        │     ├─ scale-down (<0.6s && >1): pick least (com instanceId), remove pool, drain-poll 30s, if still active → re-add + ADIAR (no cooldown update); else terminate
        │     └─ discoverExistingWorkers (tags Project=NatureAtCloud + Role=worker no LB start)
        └─ WorkerPool health checks (15s daemon): ping / , 3 fails → remove + onUnhealthyEviction cb → AS terminate (evita orphans)
Workers EC2 (AMI pré-cozida, systemd cnv-worker.service auto-start):
  └─ WebServer (:8000, CachedThreadPool) → /fractals etc → Handlers (implementam HttpHandler + RequestHandler para dual)
        ├─ (instrumentado por -javaagent no arranque)
        │     startRequest(uri) [ThreadLocal reset + parse params]
        │     workload core (loops → incrementInstructions(N) por BB visitado + incrementMethodCalls)
        │     stopRequest() → log [Metrics] + snapshot CompletedRequest (incl. allocatedBytes delta via ThreadMXBean) → store local bounded deque + MSS storeAsync
        └─ Metrics → DynamoDB (PAY_PER_REQUEST, auto-create table cnv-metrics)
Lambda workers (FaaS, 512MB, 120s):
  └─ cnv-fractals / cnv-grayscott / cnv-dna (deploy via 06, invoke via LambdaInvoker AWS SDK; handlers portáveis; sem agente por default)
MSS (DynamoDB):
  └─ Partition: requestType (fractals/grayscott/dna)
     Sort: requestId (timestamp_uuid8)
     Attrs: instructionCount(N), allocatedBytes(N), methodCallCount(N), elapsedTimeMs(N), timestamp(N), param_* (S)
```

**Fluxo completo ponta-a-ponta (pedido típico):**
1. LB recebe → estimate (history se cache/Dynamo fresco → ratios CPU+RAM separados → composite; else heuristic calibrada).
2. Decisão Lambda vs EC2 (5s threshold + busy check para fast-path; evita scale-up desnecessário para picos triviais).
3. Se EC2: select (pack no mais carregado que caiba em 25s wall-clock MAX_CAP; fallback spread) → forward + track estWork/active.
4. Worker: handle → start (ThreadLocal) → workload (instrumentado dinamicamente: cada iteração de loop re-visita BB → ICount escala com trabalho real) → stop (snapshot + async Dynamo + log).
5. Response volta.
6. Background: AS avalia avg work (em segundos reais t3), decide scale; health remove maus + termina EC2 via cb.

**Degradação graciosa:** Sem AWS creds → local mode (LB/AS só log; heuristic; sem scale real). Sem Dynamo → heuristic only + retry. Sem Lambda → só EC2 + 502 se falhar. Workers falham → retry + health eviction + Lambda fallback.

**Diferenças EC2 vs Lambda (per spec):** EC2 mais barato/por-pedido mas cold ~30-60s (AMI+systemd); Lambda caro/por-pedido mas arranque rápido (mas Java cold 2-5s). Sistema usa Lambda só para <5s quando EC2 saturada ou falha.

---

## 3. Análise Detalhada dos Componentes Principais (com referências a código + docs)

### 3.1 Javassist Instrumentation (Balance Overhead/Precisão — Requisito Final Crítico)

**Ficheiros chave:** [javassist/src/main/java/pt/ulisboa/tecnico/cnv/javassist/JavassistAgent.java](javassist/src/main/java/pt/ulisboa/tecnico/cnv/javassist/JavassistAgent.java) (premain:63, CNVTransformer:71-249, instrumentBasicBlocks:197-248), [MetricRegistry.java](javassist/src/main/java/pt/ulisboa/tecnico/cnv/javassist/MetricRegistry.java) (start/stop/increments + allocated + ThreadLocal + snapshot).

**Estratégia final (após FIX04 real BB + FIX05 ICount migration, docs/01.4 + 01.5 + 01.6):**
- Só instrumenta TARGET_PACKAGES (fractals/grayscott/dna) + HANDLER_CLASSES específicas (evita overhead em JDK/webserver/framework — per FAQ).
- Para cada método declarado:
  1. ControlFlow cf = new ControlFlow(...) ; Block[] blocks = cf.basicBlocks().
  2. Phase 1 (ORIGINAL bytecode offsets): countInstructionsInBlock por Block (CodeIterator walk).
  3. Phase 2 (descending position sort): para cada bloco, injecta `lconst N; invokestatic MetricRegistry.incrementInstructions(J)V` (N = instrs reais no bloco).
  4. insertBefore para incrementMethodCalls() (secundária).
  5. Para handlers: wrap handle() com startRequest(__uri do HttpExchange) + insertAfter stop (asFinally=true).
- Depois: computeMaxStack + rebuildStackMapIf6 (essencial para Java 7+ verifier).
- Catch em ControlFlow falha (pathological methods) → skip graceful.

**Métricas recolhidas (3 níveis, docs/01.5):**
- **Primária:** instructionCount (ICount) — usada em routing (LB), scaling (AS), estimativa. Dinâmica: loops re-entram blocos → escala com trabalho real (ex: while no JuliaFractal visitado w*h*iter vezes).
- **Secundária:** methodCallCount — cross-check/diagnóstico (útil DNA).
- **Validação + RAM:** elapsedTimeMs (nano → ms; NÃO usada para decisões — per Project.txt aviso sobre wall-clock ruidoso); allocatedBytes (delta via com.sun.management.ThreadMXBean.getThreadAllocatedBytes(Thread.currentThread().getId()) — built-in JVM, **zero overhead de instrumentação/bytecode**; -1 graceful).

**Composite final (2026-06-02, changes-summary + 00-roadmap § "Resumo sessão 4"):** `C = W_CPU * ICount + W_RAM * allocatedBytes` (W=1.0 default, configurável -Dcnv.estwork.wcpu / wram). ICount domina (~13k-40k:1 instr/B para GS/fractals CPU-bound); RAM é "sinal secundário + extensibilidade" (DNA prova: ratio 0.15 instr/B, RAM pode dominar; alocações fixas vs. iter).

**Overhead vs. precisão:** Por BB executado: 1 chamada estática + ldc long (stack gerido). Muito melhor que heurística antiga (bytecodeLen/15 flat por método — constante, não escalava com loops/params). Precisão alta (ver calibração). Tradeoff documentado extensivamente (javadocs + docs/01.x + 00-roadmap "como defender").

**Thread-safety:** Perfeita — ThreadLocal<RequestMetrics> (reset por pedido; threads do pool reused mas resetam); increments simples += no holder (1 thread/pedido). Sem contenção.

**Evidência instrumentação:** Logs AWS/worker: "Instrumented: ... (27 blocks, 287 static instructions)" etc. para handlers. Ver docs/test-report-aws-2026-05-22.md §3.

**Limitações conhecidas (declaradas):** ControlFlow pode falhar (raro, logged); overhead fixo em requests pequenos (benches mostram); não instrumenta Lambda por default (opcional per spec).

**Referências defesa (para relatório/oral):** 00-roadmap_pt.md linhas ~28-80 (5 pontos + tabela RAM/CPU ratios); docs/01.6 (33+16 medições); 01.5 (3 níveis + FAQ hint ICount).

### 3.2 MetricRegistry + MSS (DynamoDB)

**Registry:** CompletedRequest (imutável DTO com toMap() para Dynamo + toString formatado); RequestMetrics (acumulador mutável + parseUri + getAllocated + snapshot + elapsed). Deque bounded (1000 newest-first, pollLast). Métodos getByType/clear.

**MSS (MetricsStorageService.java):** Singleton lazy; AmazonDynamoDB defaultClient (degrada se !creds → available=false, no-op). ensureTableExists (describe or create PAY_PER_REQUEST + poll 30s). storeAsync (single-thread daemon Executor submit). store: requestId=timestamp+UUID8, putItem com N/S fields + param_* prefix. Catch ResourceInUse para race creates.

**Integração:** stopRequest() faz log + deque + storeAsync (após snapshot).

**Design decisions (docs/01.2 + 00-roadmap):** Partition=requestType (query eficiente por tipo para estimator); async daemon (não bloqueia resposta user); graceful degrade; tabela no módulo javassist (ponto natural de stopRequest).

**Evidência:** 41+ items em runs AWS; schema confirmado (instructionCount etc., não legacy basicBlock); old items ignorados pelo estimator.

**Qualidade:** Thread-safe (deque + writer single-thread); bounded mem; async não impacta latência pedido.

### 3.3 ComplexityEstimator (Coração do LB/AS — 2-tier + Composite)

**Ficheiro:** [loadbalancer/src/main/java/pt/ulisboa/tecnico/cnv/loadbalancer/ComplexityEstimator.java](loadbalancer/src/main/java/pt/ulisboa/tecnico/cnv/loadbalancer/ComplexityEstimator.java) (full ~455 linhas, javadocs densos).

**Estratégia 2 camadas (docs/01.3 + 01.6 + 00-roadmap):**
1. History-based (se Dynamo available + cache válido): Query últimos 50 por requestType; para cada: ratio = count / feature_hist; avgRatio; est = avgRatio * feature_new. Separa CPU/RAM ratios → composite. Cache ConcurrentHashMap (30s TTL por type, MAX 50).
2. Heuristic fallback (sem history ou 0): fórmulas calibradas empiricamente.

**Features (refinadas em 2 rondas 33 medições ICount + 16 RAM, bugs fix como "corners" inexistente):**
- fractals: w * h * min(iter, 500) [saturação empírica descoberta: iter=500/1000/2000 idêntico ICount para w=h=400].
- grayscott: size² * maxIter [ratio 164 estável <1% var 3 ordens grandeza + 3 seedModes válidos; stopOnExtinction irrelevante mesmo em death-zone].
- dna: max(|seq1|, |seq2|) [linear, não produto; minLength/stopOnFirst sem efeito mensurável no *trabalho* (só output)].

**Heurísticas (calibradas, com RAM separada):**
- Fractals CPU: piecewise (10/5/2 por regime iter) ou w*h*33; RAM w*h*33.
- GS: *164 / size²*64.
- DNA: max*125 / max*800 + fixed ~30kB.

**Composite + pesos:** Ver 3.1. toString inclui wCpu/wRam.

**Cache/robustez:** TTL 30s (endereça aviso Project.txt sobre MSS bottleneck); retry 60s; ignora records com count=0 (legacy schema); feature <=0 → -1.

**Evidência/Calibração:** docs/01.6_calibration_evidence.md (tabelas completas rondas); benchmarks/bench-icount.csv, bench-ext.csv, bench-ram-calibration.csv, bench-t3micro-throughput.csv; logs confirmam "DynamoDB disponível. A usar dados históricos." vs "heuristic".

**Limitação declarada:** Reuse extractFeature para RAM ratios (RAM indep de iter → variância até 5x em history; impacto <0.01% no composite actual; futuro extractRamFeature separado).

**Impacto no routing/scaling:** Estimativa *antes* de forward + track estWork; thresholds em segundos wall-clock (calibrados t3).

### 3.4 WorkerPool + Load Balancing (Híbrido Packing/Spreading)

**Ficheiro:** [loadbalancer/src/main/java/pt/ulisboa/tecnico/cnv/loadbalancer/WorkerPool.java](loadbalancer/src/main/java/pt/ulisboa/tecnico/cnv/loadbalancer/WorkerPool.java) (Worker inner + pool logic + health).

**Worker state:** host/port/instanceId (opt); AtomicInteger active; AtomicLong estimatedWork; isHealthy (root ping, 2s timeout, *novo* client cada vez); toString com active/estWork.

**add/remove:** Idempotente por (host,port) — crítico para discover + CLI args coexistirem. COW list.

**Routing principal (usado por LB):** selectForRequest(cost, excluded):
- Para cada não-excluído: projected = load + cost.
- bestPack = most-loaded com projected <= DEFAULT_MAX_CAPACITY (packing para consolidar → idle workers → scale-down possível).
- bestSpread = least-loaded (sempre).
- Retorna pack se existir, senão spread.
- (Legacy: selectLeastLoaded, roundRobin, etc. — não primários).

**MAX_CAPACITY:** 25.0s wall-clock → 5e10 raw instr (via WORKER_THROUGHPUT 2e6 instr/ms). ~1 heavy GS (s256x5000 ~25s) ou 5 medium.

**Health (15s, 3 fails):** startHealthChecks (daemon scheduled); run (snapshot list, CHM consecutiveFailures); 3 fails → remove + cb onUnhealthyEviction (AS usa para terminate EC2).

**setOnUnhealthyEviction:** Registado pelo AS para fechar loop de orphans (bug histórico fixado em revalidação).

**Docs chave:** docs/02.1_lb_packing_strategy.md (algoritmo + tabelas cenários + inspiração OpenStack Nova FAQ §227).

**Qualidade:** Thread-safe (atomics + COW + CHM); estWork add antes send / remove finally (mesmo em falha); packing permite scale-down agressivo sem perder latência (fallback spread).

### 3.5 Lambda Integration + EC2/Lambda Balance

**Ficheiros:** [LambdaInvoker.java](loadbalancer/src/main/java/pt/ulisboa/tecnico/cnv/loadbalancer/LambdaInvoker.java) (singleton AWSLambda, FUNCTION_NAMES map, buildJsonPayload manual escape, invoke), LoadBalancer.java (consts LAMBDA_MAX_SECONDS=5.0 / WORKER_LOAD_THRESHOLD=0.80 via props; fast-path 112-128; fallback 189-201; allWorkersBusy 211-225).

**Lógica (changes-summary §3 + 00-roadmap + 02.8):**
- estSeconds <=5s ? Lambda-eligible : EC2 only.
- Eligible + all >80% busy → Lambda *direto* (fast-path; evita scale-up para trivial enquanto heavy GS corre).
- EC2 normal + retries → se falha + eligible → Lambda fallback (FT).
- Deploy: 06-deploy-lambdas.sh (idempotente create/update, 512MB Java11, role de 01); handlers já existiam (professor).

**Payload/return:** JSON manual (params como strings); response body como string (base64 img ou HTML).

**Defesa (para oral):** 5s separa pedidos que justificam VM (cold 60s) dos que não; Lambda "válvula escape" (EC2 first mais barato); fast-path para picos triviais; cold-start mitigado (só usa quando EC2 indisponível).

**Scripts:** 01 persiste lambda-role-arn; 99 deleta 3 Lambdas; 06 usa.

### 3.6 AutoScaler (Efficient Cost/Perf + Safe Scale-Down)

**Ficheiro:** [loadbalancer/src/main/java/pt/ulisboa/tecnico/cnv/loadbalancer/AutoScaler.java](loadbalancer/src/main/java/pt/ulisboa/tecnico/cnv/loadbalancer/AutoScaler.java) (full, javadocs excelentes com refs benches).

**Modos:** Local (sem ami/sg → só logs); AWS (SDK EC2 real + discover + terminate).

**Thresholds (wall-clock, calibrados t3.micro burst 2e6 instr/ms, ver 00-roadmap calib t3 + bench-t3micro-throughput.csv):**
- CHECK 5s, MIN1/MAX5, COOLDOWN 60s.
- UP 2.5s avg/worker (~1 GS médio); DOWN 0.6s (idle); hysteresis ~4x.
- MAX_CAP 25s (derivado 5e10 raw em WorkerPool).
- DRAIN: 15 polls * 2s = 30s max; se ainda active >0 → ADIAR (re-add pool, *não* actualiza lastScaling, retry próximo ciclo).

**scaleUp:** runInstances (AMI, t3.micro, key, worker-sg, profile CNV-Worker-Role, userData marker, tags Project=NatureAtCloud/Role=worker/ManagedBy); waitForPrivateIp (describe poll); addWorker(ip,8000,id).

**scaleDown:** pick least estWork *com* instanceId (ignora manuais); remove pool; drain poll; if active>0 → log ADIAR + re-add + return (sem update cooldown); else terminate + update last.

**Health wiring:** ctor regista cb; handleUnhealthyEviction (awsMode + id → terminate).

**discoverExistingWorkers:** no start (se aws); filtra tags Project+Role+running → addWorker (idempotente).

**Degradação + local:** AwsConfig.isAwsScalingEnabled() = !ami.empty && !sg.empty; se não, só simula logs.

**Evidência:** PROJECT_STATUS Nível7 (scale 1→2+ com logs "[AutoScaler] SCALE UP (avgWork=75.98s > 2.5s)" → launch; SCALE DOWN 0s <0.6s + terminate); health recovery logs; test-report + evidence.

**Docs:** 02.2_safe_scale_down.md (before/after + porque re-add + no-update cooldown + 30s drain); 02.5 (FIX loop infinito anterior); 02.6 (wiring estimator).

**Qualidade:** Correctness-first (nunca mata in-flight); burstable mitigation explícita (scale antes de esgotar credits); discover adopta workers manuais/órfãos.

### 3.7 WebServer, Handlers e Workloads (O que é Instrumentado)

**WebServer.java:** Simples (18 linhas) — HttpServer 8000 CachedThreadPool; contexts / → RootHandler (CORS + echo), /fractals→FractalsHandler etc.

**Handlers:** Implementam HttpHandler.handle (para EC2) + RequestHandler.handleRequest (para Lambda, fornecido professor). Fazem parse query, chamam core workload, devolvem base64 PNG ou texto/HTML + CORS.

**Core workloads (instrumentados):**
- fractals/JuliaFractal.generate: nested for y/x + while (zx²+zy²<4 && i>0) { update zx/zy; i-- }; setRGB. (Loops → BBs visitados múltiplas vezes).
- grayscott/GrayScott: grids U/V double[size][size]; itera maxIterations com f/k diffusion/extinction checks.
- dna/Dna: parse FASTA, runDna (findSeed + extendRight/Left loops por minLength); stopOnFirst só afecta output (não trabalho, per calib).

**Ver:** fractals/src/.../JuliaFractal.java (generate loops); grayscott/.../GrayScott.java; dna/.../Dna.java + DnaHtmlRenderer.

**Handlers também instrumentados** (wrap start/stop) mas core workload é o heavy.

### 3.8 Build, Dependências, JARs e Config

**POM raiz:** Reactor; modules ordem javassist → webserver → fractals/dna/grayscott → loadbalancer (javassist primeiro crítico).

**Módulo javassist:** artifact "javassist-agent"; dep javassist 3.30.2-GA + aws-dynamodb; assembly fatjar + MANIFEST (Premain-Class).

**webserver:** deps fractals/dna/grayscott/javassist-agent (compile); assembly fatjar + manifest ../javassist/.../MANIFEST (para agent no worker).

**loadbalancer:** aws-ec2 + dynamodb + lambda (v1.12.528); assembly fatjar (sem manifest agent).

**Outros workloads:** aws-lambda-java-core + jackson (para Lambda).

**Java 11** em todos properties + compiler.

**Config:** Tudo via System.getProperty (aws.region, cnv.ami.id, cnv.worker.sg.id, cnv.keypair.name, cnv.worker.instance.profile, cnv.instance.type=t3.micro, cnv.worker.port=8000, cnv.estwork.wcpu=1.0, cnv.estwork.wram=1.0, cnv.lambda.maxseconds=5.0, cnv.lambda.loadthreshold=0.80). AwsConfig centraliza; isAwsScalingEnabled() simples check.

**Degradação:** Defaults sensatos → local-only (sem scale real, heuristic, etc.).

**JARs fat (com deps):** Ver list_dir targets; status menciona tamanhos ~10-19MB.

**Manifest:** Permite java -javaagent:javassist-...jar=... (args para agent, mas código ignora args e usa hardcoded targets).

### 3.9 Deployment Scripts e Operações (Automação Total)

**Ficheiro principal:** [scripts/README.md](scripts/README.md) + aws-config.sh (shared vars, ensure_base_ami via SSM + fallback, key em ~/.ssh para WSL, sanitize_crlf).

**Pipeline (~7-12 min):**
1. 01-setup-iam.sh: 3 roles (Worker-Role, LoadBalancer-Role, Lambda-Role) + 2 instance profiles + inline CNV-AllowPassWorkerRole (iam:PassRole) + persist lambda arn.
2. 02-setup-network.sh: keypair (chmod fix WSL) + 2 SGs (worker: 8000 só de lb-sg + 22 do /32 current IP; lb: 8080/0 +22 /32).
3. mvn clean package -DskipTests.
4. 03-create-ami.sh: launch builder (base AL2023 via SSM), dnf java11, scp jars, install systemd unit (ExecStart java -javaagent:... -cp webserver... WebServer), create-image (name cnv-worker-ami-YYYYMMDD-HHMM), terminate builder. ID em .state/.
5. 04-launch-worker.sh: runInstances from AMI + worker-sg + profile + tags → wait running + IP.
6. 05-launch-lb.sh [workers...]: launch base, scp LB jar, nohup java -Daws... -Dcnv... LoadBalancer 8080 [workers or empty for AWS discover].
7. (Opcional) 06-deploy-lambdas.sh: create/update 3 funcs (cnv-*) 512MB Java11 + role + handler.
8. 99-cleanup.sh [--deep]: terminate EC2s (or + delete SGs/key/IAM/AMI/snapshots/Lambdas/Dynamo).

**Test helpers em scripts/test/:** _smoke-test, _test-scale, _test-resilience, _watch-scaledown, _benchmark-*, _verify-build (javap checks), _collect-logs, _check-state, _diagnose-orphans, _validate-sg.

**Evidência runs:** PROJECT_STATUS (AMI ami-00e3402f5d0f8206e, workers i-..., LB 54.229..., scale + Lambda fast-path logs); test-report 2026-05-22 (pipeline + smoke + Dynamo 16 items + instr logs); evidence dirs com logs.

**Idempotência + robustez:** Re-runs seguros; WSL CRLF fixes; IP /32 update; deep vs shallow; .state/ gitignored.

**Riscos operacionais (docs/02-fase2 + 00-roadmap §5):** Service quota 5 vCPU (t3=2 → ~2-3 inst simultaneous; pedir increase se necessário); IMDSv2 (usa SDK auto); PassRole explícito necessário (adicionado no 01); race table create (catch ResourceInUse); RootHandler 200,-1 fix (evitava timeout health).

---

## 4. Estado Atual: O Que Está Implementado, Pendente, Bugs/Limitações

**Do PROJECT_STATUS.md (Níveis 0-7, 2026-06-04) + 00-roadmap checklists + changes-summary + subagent review:**

**Implementado e Validado (AWS real, múltiplas sessões):**
- Build local + fat JARs + LambdaInvoker class presente.
- Worker local + agente (allocatedBytes logs).
- LB local + estimator composto (history/heuristic + pesos CPU/RAM).
- Creds AWS + pipeline scripts completo (IAM → network → AMI → worker → LB → Lambda).
- E2E AWS: 3 workloads via LB, Dynamo com métricas (41+ items), estimator history, Lambda fast-path/fallback logs, scale-up (1→2+ com avgWork >2.5s), scale-down (avg<0.6s + drain), health (fail/recover + eviction), discover workers, cleanup 0 running.
- Métrica composta + calib RAM + ICount thresholds wall-clock + Lambda routing.
- FT: retries exclusão + Lambda fallback + health 3-fail remove + eviction terminate + safe drain re-add.
- Tudo em 6+ scripts idempotentes + test helpers.

**Pendente (principalmente artifacts de submissão):**
- Relatório final (≤6p double-col após cover) + vídeo demo contra testes professor (NÃO FEITO per docs antigos; assume pronto agora com evidência rica).
- "Deployment totalmente automatizado" — scripts excelentes mas orquestração manual (mvn + cd scripts + seq run; sem single deploy-all.sh). WSL notes ainda necessárias.
- Análise custo $$$ mais profunda (mencionada como partial no roadmap; packing/Lambda ajudam mas sem modelo wired no AS).
- Predictive scaling / arrival trends (fora escopo).

**Bugs Históricos Fixados (documentados):**
- BB count heurística flat (FIX04/05: ControlFlow real + ICount).
- Metric strings vs structured (FIX01).
- Sem MSS (FIX02).
- Sem estimativa (FIX03).
- Orphans (eviction cb + terminate).
- Localhost ghost no LB AWS.
- Unsafe scale-down (re-add + no cooldown update).
- SeedMode bogus "corners" data (removido).
- Duplicate workers (idemp add).
- Health RootHandler timeout (200,-1).
- Table race (catch ResourceInUse).
- CRLF WSL, key perms, AMI base SSM, etc.

**Limitações Conhecidas / Declaradas (proativas para relatório):**
- History RAM usa mesma feature CPU (inconsistente iter-variando; impacto <0.01% composite; docs/00:63).
- Overhead fixo pequeno reqs (error alto s=64/200px; linear heuristics; benches mostram; prod sizes <10% error).
- Local calib vs t3.micro (JIT/allocator; ordem grandeza mantém; burstable baseline ~0.2M instr/ms explícito).
- DNA stop/minLength só afecta output (não trabalho; per calib).
- Sem instr em Lambda (opcional spec).
- LB single-point (per spec assume nunca falha).
- Broad IAM (FullAccess + PassRole; least-priv não full).
- Health new HttpClient cada check (minor).
- JSON manual LambdaInvoker (escapes limitados; sem Jackson no LB).
- Legacy methods/comments ainda no código.
- Old Dynamo items (pre-ICount schema) ignorados mas ocupam storage (recomendado delete table).
- Est drift possível (hardware/JIT change; mitigated por calib explícita + throughput note).
- Sem testes unitários (benches/scripts manuais em vez).
- Quota vCPU / AMI accumulation (mitigado por --deep + MAX5).
- Cold Lambda para reqs <1s (mitigado por threshold + "só quando EC2 indisponível").

**Sem active crashes aparentes; graceful degrade forte.**

---

## 5. Evidência Empírica, Calibração, Benchmarks e Validação AWS

**Locais principais:**
- benchmarks/: bench-t3micro-throughput.csv (8 pts), bench-ram-calibration.csv (16 pts), bench-ext.csv / bench-icount.csv (33 ICount), bench-*.log, bench-worker.log, bench-ext-metrics-lines.txt.
- docs/evidence-2026-05-21-calibration/ + evidence-validation-2026-05-19/ (logs scale/resilience/watch + README).
- docs/test-report-aws-2026-05-22.md + 00-roadmap (tabelas + "como defender") + 01.6 + changes-summary + PROJECT_STATUS (logs reais AWS).

**Números chave (t3.micro burst, Corretto 11, G1GC, credits sufficient):**
- Throughput: avg ~1.96M instr/ms (σ 0.18M, ~9.5% CoV); heavy GS s256x5000 ~2.12M (JIT warm); fractals 1600x ~2.10M; small/DNA ~1.6-1.8M (warmup 10-15%). 2 iters warmup descartados. Baseline (no credits) ~0.2M.
- ICount ratios (ronda 1+2, 33 pts): GS 164 (var <1% 6+ pts s64-s256, center/ring/stripe); fractals decresce com iter (satura 500); DNA ~123-149 (linear max seq).
- RAM (16 pts): fractals ~32.5 B/px (w*h, indep iter); GS ~63.5 B/cell (size²); DNA ~780 B/char + ~30k fixed. Heur antigas subestimavam 3-4x; corrigidas erro <10% tamanhos prod.
- Composite ratios: GS RAM ~0.008%; fractals 3.5-4%; DNA ~87% (prova valor).
- Thresholds traduzidos: 5e9/1.25e9 → 2.5s/0.6s; MAX 5e10 →25s (1 heavy GS).
- AWS runs: 41+ Dynamo items schema correcto (ICount + alloc + params); scale-up/down logs; health recover + eviction; Lambda fast-path "All workers busy (>80%) + ... routing directly to Lambda"; estimator history vs heuristic; 3/3 workloads 200 OK (fractals 106KB 1s, GS 0.8s, DNA 0.2s).

**Calibração metodologia:** Local para ICount (hardware-indep, bytecode count); t3 real para throughput/RAM (8-16 pts representativos); scripts _benchmark-*.sh (curl seq, parse [Metrics] lines).

**Rationale decisões (defensável, ver 00-roadmap "como defender" + 01.6):**
- Composite: 2 dimensões independentes (RAM indep iter vs CPU depende; DNA caso RAM-domina); zero overhead; pesos props para re-calib; ICount ainda domina correctamente.
- Packing híbrido: consolida para scale-down ($$ baixo) + spread safety; usa estCost real (não só active count).
- Safe drain: correctness (nunca perde pedidos) > velocidade; re-add permite retry rápido.
- 5s Lambda: justifica VM? cold VM 60s vs Lambda cheap para trivial; fast-path alivia sem scale.
- Thresholds wall-clock: legíveis ("2.5s work por worker"); calib empírica t3 (8 pts, var <10% heavy); burstable mitigado por scale precoce.
- Cache + limit: cumpre aviso Project.txt MSS (custo + bottleneck).

**Validação FT/resilience/scale:** _test-resilience.sh + logs evidence (scale + health + Lambda + drain); PROJECT_STATUS Nível7 + Nível5 (end-to-end + scale + Lambda).

---

## 6. Análise Crítica: Qualidade de Código, Decisões, Riscos, Melhorias

**Do subagent review + leitura directa (cobriu todos sources + docs):**

**Thread-safety:** Excelente (Atomics active/estWork por Worker; CopyOnWrite workers list; ConcurrentHashMap failures/cache; ThreadLocal metrics por request; volatile lastScaling/onUnhealthyEviction; synchronized health start/stop; single-thread executors/schedulers daemon). Sem races óbvias em hot path (select O(N) com N<=5; add/remove finally).

**Error handling:** Broad catch(Exception) + log + degrade (heuristic, local mode, skip Lambda, 502, -1 alloc). Bom para cloud (transient). Re-interrupt em Interrupted. Lambda FunctionError exposto. Críticos loggam (não silent).

**Resources:** Bom. try-with em Dna fasta. os.close() após write (múltiplos returns mas presentes). Daemon executors (Metrics, health, AS, storage). HttpClients short-lived (health/forward). SDK clients default (managed). Minor: novo client por health check 15s (podia share static).

**Custos AWS:** Design bom (PAY_PER_REQUEST DDB; packing reduz horas EC2; Lambda só small/overflow + threshold; t3.micro; scripts full cleanup). Riscos: políticas IAM broad (FullAccess + PassRole; norma estudante mas chamar atenção); AMIs/Dynamo antigos acumulam (manual --deep); sem cost tagging/alarms; burstable (mitigado mas não evitado). Lambda 512MB*5s barato mas billed duration cold.

**Security:** Bom. Sem creds hardcoded (SDK chain: IMDSv2 workers, env/profile dev/LB; sts verificado). .gitignore *.pem/.aws/credentials/.state/. Keypair runtime, ~/.ssh Linux FS (WSL). SGs: 22 só /32 current (idemp re-run IP change); 8000 só LB-SG; 8080 open. Tags ownership. Riscos: pem em FS (cleanup rm); broad policies (over-priv); sem IMDS hop limit/session tags; Lambda exec + DDB full. Cleanup preserva IAM (re-use next session). Bom: no root; IAM users recomendados em docs/faq.

**Performance:** Cache 30s + bounded history + async store + atomics + COW bons. Hotspots potenciais: 1-2 Dynamo queries/req (cached); health pings low-freq; new clients health. LB HttpClient static shared. Sem leaks/O(n^2) óbvios (select O(5)). Overhead instr medido/aceitável (benches). Edges (small DNA, burstable, JIT, saturação) explícitos + documentados.

**Outros:** Qualidade alta (javadocs com rationale + refs calib/benches em todo lado; comentários "why"; consistent naming; parsing tolerante NumberFormat→default). Minor: métodos legacy (selectLeastLoadedExcluding); JSON manual; broad catches; System.err vs logger. Sem unit tests (benches manuais ok para escopo). Build reactor limpo; fatjars assembly.

**Decisões de design + defesa (ver subagent §3 + docs "como defender"):**
- Composite + RAM ThreadMXBean: captura dimensões ortogonais (evidência empírica DNA + indep iter); zero overhead; extensível; ICount domina naturalmente.
- Híbrido pack/spread: packing para $$ (consolida → idle → scale-down); spread safety; usa estCost (não só active).
- Safe drain: nunca perde trabalho in-flight (re-add + no cooldown update permite retry rápido); correctness-first.
- 5s Lambda + fast-path: separa "justifica VM?"; alivia capacity sem scale-up 60s; EC2 first (barato).
- Thresholds wall-clock + calib t3: legíveis ("2.5s work"); histérise 4x; burstable mitigado.
- Cache + limit 50/30s: cumpre aviso MSS Project.txt.
- Idempotência + discover + eviction cb: robustez ops (adopta manuais/órfãos; evita leaks custo).

**Riscos / call-outs proativos (honestidade relatório):**
- Broad IAM + pem FS + quota vCPU.
- Estimate drift (non-t3 / credits / JIT); small-req fixed overhead (bounds de benches).
- RAM feature reuse (limitação conhecida, impacto pequeno).
- Sem HA LB (per spec); sem instr Lambda (opcional); sem cost model wired.
- Scripts quase full-auto mas orquestração manual.
- Historical data pollution (old schema).
- Cold starts Java Lambda para reqs muito pequenos.

**Melhorias sugeridas (future work / polish relatório):**
- extractRamFeature separado + ratios por métrica.
- HttpClient pool/reuse (static WorkerPool).
- Jackson ou payload estruturado Lambda (evitar manual escape).
- Thresholds/config mais driven (além props).
- Cost estimator (EC2 $/h vs Lambda) no AS.
- Structured logs / Prometheus export (além stdout + Dynamo).
- IMDSv2 strict + session tags + least-priv policies.
- Single orchestrator script (mvn + pipeline).
- Mais testes death-zone + large DNA + stopOnFirst effect.
- Cache invalidation / warmer.
- Microbench overhead instr (agent vs no-agent).
- Circuit breaker básico (limitação explícita 02.8).

**Codebase maduro para escopo estudante:** Robusto, bem documentado internamente (fácil extrair para relatório), validado AWS, graceful, com forte evidência empírica.

---

## 7. Prontidão para Entrega Final e Recomendações

**Vs. checklist final (00-roadmap §8 + PROJECT_STATUS + 02.2-testing-checklist):**
- Todos os itens de código/implementação: FEITO (ver §4).
- Instrumentação balanceada: FEITO (CFG ICount + composite zero-overhead RAM).
- LB/AS complexidade-aware + custo/perf: FEITO (2-tier + hybrid + safe + Lambda).
- Lambda + balance EC2/Lambda: FEITO (deploy + fast/fallback).
- FT transparente: FEITO (retries + health + eviction + Lambda + drain).
- Automação deployment: FEITO (scripts completos + pipeline validado; "full" orquestração manual é menor gap).
- Relatório + vídeo: PENDENTE (artefactos de submissão; usar esta análise + docs/00 + 01.6 + benches + logs AWS + javadocs + changes-summary como base rica. Estrutura sugerida em 00-roadmap § "Estrutura sugerida para secção Composite" + "como defender" espalhados).

**Per spec final (Project.txt §5):** i) instr balancing overhead/precision (sim); ii) AS custo+perf eficiente (sim, packing + thresholds + Lambda); iii) LB minimiza custo/latência via complexidade MSS (sim, 2-tier + hybrid + estWork tracking). Vídeo + relatório.

**Recomendações para relatório (6p double-col):**
- Use diagrams (arch + LB routing pseudocode + AS drain before/after + dataflow).
- Secções por componente + "decisões + evidência empírica + como defender (5 pontos composite, packing rationale, 5s justification, wall-clock calib, safe drain)".
- Tabelas: calib throughput/RAM/ICount (refs CSVs); features/heurísticas; thresholds; AWS runs results.
- Gráficos: ICount vs feature (linearidade GS, saturação fractals); RAM vs iter (independência); scale-up/down traces; est vs elapsed (validação).
- Limitations + tradeoffs declarados proativamente (RAM reuse, small req overhead, burstable, IAM broad, etc.).
- Ops: scripts pipeline + cleanup + custos.
- Future: melhorias listadas.
- Leverage docs/ (00-roadmap tem "estrutura sugerida" + sessões resumo; 01.x são "diário de bordo" das fixes/decisões; 02.x design decisions).

**Riscos para submissão:** Quota EC2 (pedir increase com antecedência se >2-3 inst); cleanup sempre (evitar custos); WSL key perms (usar ~/.ssh); re-run pipeline se código muda (re-AMI); tabelas Dynamo antigas (delete antes demo fresca).

**Conclusão:** O projeto está num estado **excelente e maduro** para entrega final. Especificação cumprida + excedida com métrica composta (insight real), routing híbrido, scale-down seguro, integração Lambda inteligente, calibração empírica forte (33+16 pts + t3 throughput), FT robusta, e automação completa via scripts. Código limpo, thread-safe, com degrade graceful, e *extremamente bem documentado internamente* (javadocs + docs/ dedicados com tabelas + "como defender" — ouro para relatório). Evidência de validação AWS real (scale, Lambda fast-path, health, Dynamo, etc.) presente e citável. 

Falta principal: os artefactos de escrita (relatório + vídeo) — mas a análise, código, benches, logs e docs internos fornecem *tudo* o necessário para um relatório de alta qualidade sem esforço extra de descoberta.

**Ficheiros chave para referência imediata no relatório:**
- Project.txt (enunciado)
- docs/00-project-status-and-roadmap_pt.md (histórico + checklists + "como defender" + estrutura sugerida)
- docs/01.6_calibration_evidence.md + benchmarks/*.csv (dados empíricos)
- docs/02.1_lb_packing_strategy.md + 02.2_safe_scale_down.md + 02.8_fault_tolerance.md (decisões design)
- loadbalancer/src/.../*.java + javassist/.../*.java (código + javadocs densos)
- PROJECT_STATUS.md + changes-summary.md + scripts/README.md (estado ops + pipeline)
- report/checkpoint-report.tex (base para expandir)

**Próximos passos recomendados:** 1) Re-run pipeline + testes chave (smoke + scale + resilience) para vídeo fresco + logs actualizados. 2) Escrever relatório usando esta análise como esqueleto (copiar secções/tables). 3) Gravar vídeo narrado (EN per guia checkpoint, mas adaptado final). 4) Cleanup --deep pós-submissão.

Esta análise dá-te uma "profunda noção do projeto" como pedido — sem poupar tokens/uso. Boa sorte na entrega final!

---

*Gerado automaticamente com exploração exaustiva. Para actualizações, re-rodar análise ou focar secções específicas.*