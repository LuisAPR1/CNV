# Nature@Cloud - Estado do Projeto & Roteiro

> **Última atualização:** 2026-05-19 (sessão 2 — re-validação assistida + 4 fixes)
> **Autores:** Luis Alexandre + a81430 + laura
> **Curso:** Computação e Virtualização na Nuvem (CNV) - IST 2025-26

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
| **Agente Javassist** | **Validado em AWS** | Instrumenta `pt.ulisboa.tecnico.cnv.{fractals,grayscott,dna}.*` no load time; conta method calls + BBs estimados + elapsed time |
| **MetricRegistry** | **Validado em AWS** | `CompletedRequest` estruturado, ThreadLocal por pedido, ConcurrentLinkedDeque para histórico in-memory |
| **MetricsStorageService (DynamoDB)** | **Validado em AWS** | Singleton + escritas assíncronas; auto-cria `cnv-metrics` (PAY_PER_REQUEST) com fix de race condition para scale-up; valida via IMDSv2 |
| **Balanceador de Carga** | **Validado em AWS** | Least-loaded routing, retry com exclusão, complexity estimate no path; corre via `nohup` em EC2 dedicada |
| **AutoScaler** | **Validado em AWS** | Scheduler 5s, threshold 1.0/0.25, cooldown 60s, cap 5 workers; lança/termina EC2 via SDK; **scale-up duplo real: 1→3 workers** com 20 pedidos `2000×2000×2000` |
| **ComplexityEstimator** | **Validado em AWS** | Ratio-based usando histórico DynamoDB (cache 30s) + heuristic fallback; valor escala com `w*h*iter` |
| **Health checks (WorkerPool)** | **Validado em AWS** | Pings cada 15s a `/`; remove worker após 3 falhas consecutivas; recover automático |
| **Deployment AWS** | **Validado end-to-end** | 6 scripts bash idempotentes em `scripts/`, AMI worker pré-cozida (Java + JARs + systemd), IAM Roles + Instance Profiles + inline `iam:PassRole` |

### Pendente (Fase 3+)

| Componente | Estado | Notas |
|---|---|---|
| **Workers Lambda** | **Não iniciado** | Interfaces de handler existem; falta deployment de 3 Lambdas |
| **Routing por complexidade** | **Parcial** | `Estimate` calculado mas LB ainda não o usa na escolha; só least-loaded |
| **Lambda vs EC2 routing** | **Não iniciado** | Decisão custo/latência por pedido |
| **Instrumentação real de BBs** | **Limitação conhecida** | Heurística `bytecodeLength/15` aplicada por entrada de método — `basicBlockCount` fica flat quando loops são intra-método (e.g. `JuliaFractal.generate`). Ver secção 5.2 A |

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

#### A. A Contagem de Blocos Básicos é Imprecisa

A abordagem atual estima blocos básicos como `bytecodeLength / 15`. Esta é uma heurística muito aproximada. Os verdadeiros blocos básicos são delimitados por instruções de salto (if/else, ciclos, switch, try/catch). O Javassist fornece `MethodInfo.getCodeAttribute()` que dá acesso ao bytecode. Deve-se analisar o bytecode para detetar instruções de salto reais (`goto`, `if*`, `tableswitch`, `lookupswitch`, exception handlers) e contar os blocos básicos entre elas.

**No entanto**, o enunciado também diz: _"os alunos devem considerar os trade-offs de utilidade/sobrecarga de cada uma e de todas as métricas utilizadas"_. Portanto, uma heurística pode ser aceitável **se for justificada no relatório**. O ponto-chave é que a métrica deve **correlacionar-se bem** com a complexidade real do pedido. Deve-se validar isto executando pedidos com parâmetros diferentes e verificando se os valores das métricas diferem proporcionalmente.

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
| 2 | Instrumentação Javassist a recolher métricas | **FEITO** (com limitação conhecida na contagem de BBs — ver 5.2 A) |
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
| 1 | Ferramenta de instrumentação balanceando sobrecarga vs. precisão | **NÃO FEITO** |
| 2 | Algoritmo de auto-scaling (custo + desempenho) | **NÃO FEITO** |
| 3 | Algoritmo de balanceamento de carga usando estimativas de complexidade do MSS | **NÃO FEITO** |
| 4 | Suporte a workers Lambda (FaaS) | **NÃO FEITO** |
| 5 | Balanceamento EC2 + Lambda (custo vs. latência) | **NÃO FEITO** |
| 6 | Automação completa de deployment | **NÃO FEITO** |
| 7 | Relatório final (até 6 páginas) | **NÃO FEITO** |
| 8 | Vídeo de demonstração | **NÃO FEITO** |

---

## 7. Checklist de Requisitos do Checkpoint

- [x] Workers VM multi-threaded (`Executors.newCachedThreadPool()`)
- [x] Agente Javassist carrega e instrumenta classes de carga de trabalho
- [x] Métricas recolhidas: chamadas de métodos, blocos básicos estimados, tempo decorrido
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
- [ ] Deployment Lambda para todas as 3 cargas de trabalho
- [ ] AS aumenta/diminui EC2 com base em métricas reais
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
| `loadbalancer/ComplexityEstimator.java` | Estima cost de pedidos (histórico DynamoDB + fallback heurístico, cache 30s) |
| `javassist/MetricsStorageService.java` | Persistência assíncrona de métricas no DynamoDB |
| `scripts/*.sh` | Provisionamento e cleanup de toda a infra AWS (01–iam, 02–network, 03–ami, 04–worker, 05–lb, 99–cleanup) |
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
