# Nature@Cloud - Estado do Projeto & Roteiro

> **Última atualização:** 2025-05-17  
> **Autores:** Luis Alexandre + a81430  
> **Curso:** Computação e Virtualização na Nuvem (CNV) - IST 2025-26

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

### A funcionar localmente

| Componente | Estado | Notas |
|---|---|---|
| **WebServer (workers)** | **A funcionar** | Executa em porta configurável, serve todos os 3 endpoints, multi-threaded (CachedThreadPool) |
| **Agente Javassist** | **A funcionar** | Instrumenta no momento do carregamento; regista chamadas de métodos + BBs estimados + tempo por pedido no stdout |
| **MetricRegistry** | **A funcionar** | Métricas thread-local, armazenamento concorrente de métricas concluídas |
| **Balanceador de Carga** | **Parcialmente funcional** | Encaminha pedidos, encaminhamento least-loaded, retry com exclusão |
| **AutoScaler** | **Apenas esqueleto** | Regista carga média a cada 10s, limiares definidos mas nenhuma ação tomada |
| **Workers Lambda** | **Não iniciado** | Interfaces de handler existem (fornecidas pelo professor) mas sem deployment Lambda |
| **DynamoDB (MSS)** | **Não iniciado** | Nenhum código existe |
| **Deployment AWS** | **Não iniciado** | Sem scripts, sem AMI, sem security groups, sem automação de deployment |
| **Estimativa de complexidade** | **Não iniciado** | O LB não utiliza parâmetros do pedido para estimar custo |

### Estrutura do projeto

```
CNV/
├── pom.xml                  (POM raiz, 6 módulos)
├── Project.txt              (enunciado do trabalho)
├── README.md                (instruções básicas)
├── docs/                    (esta pasta - para acompanhar o progresso)
├── scripts/                 (vazia - deve conter automação AWS)
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
| 1 | Workers VM multi-threaded | **FEITO** |
| 2 | Instrumentação Javassist a recolher métricas | **FEITO** (com ressalvas na precisão dos BB) |
| 3 | Deploy no AWS EC2 (t3.micro) | **NÃO FEITO** |
| 4 | LB configurado na AWS a funcionar | **NÃO FEITO** (LB local existe mas sem integração AWS) |
| 5 | AS configurado na AWS a funcionar | **NÃO FEITO** (apenas esqueleto, sem chamadas à API EC2) |
| 6 | Lógica inicial LB/AS ou pseudocódigo | **PARCIALMENTE FEITO** (código existe mas sem estimativa de complexidade) |
| 7 | DynamoDB MSS para métricas | **NÃO FEITO** |
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
- [ ] Workers enviam métricas para o DynamoDB após cada pedido
- [ ] LB executa no AWS EC2 (t3.micro)
- [ ] Worker(s) executa(m) no AWS EC2 (t3.micro)
- [ ] AutoScaler pode lançar/terminar instâncias EC2
- [ ] LB tem lógica inicial de estimativa de complexidade (pelo menos pseudocódigo)
- [ ] Scripts/automação de deployment em `scripts/`
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

## Apêndice: Referência de Ficheiros Chave

| Ficheiro | Propósito |
|---|---|
| `javassist/JavassistAgent.java` | Instrumenta classes de carga de trabalho no momento do carregamento |
| `javassist/MetricRegistry.java` | Recolha de métricas thread-local por pedido |
| `webserver/WebServer.java` | Servidor HTTP worker (executa no EC2 com `-javaagent`) |
| `loadbalancer/LoadBalancer.java` | Ponto de entrada LB, encaminha para workers |
| `loadbalancer/WorkerPool.java` | Gestão do pool de workers, estratégias de seleção |
| `loadbalancer/AutoScaler.java` | (Esqueleto) Monitoriza carga, decide aumentar/diminuir |
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
