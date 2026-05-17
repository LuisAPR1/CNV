# FIX 02 — Integração DynamoDB (Metrics Storage Service)

> **Data:** 2025-05-17  
> **Ficheiros alterados/criados:**
> - `javassist/pom.xml` — nova dependência
> - `javassist/.../MetricsStorageService.java` — novo ficheiro
> - `javassist/.../MetricRegistry.java` — chamada ao MSS no `stopRequest()`

---

## Objetivo

Implementar o **MSS (Metrics Storage System)** usando DynamoDB para que as métricas de cada pedido sejam persistidas na cloud, permitindo ao Load Balancer (no futuro) consultar histórico de complexidade.

## O que foi feito

### 1. Dependência Maven

Adicionada `aws-java-sdk-dynamodb` (v1.12.528) ao módulo `javassist`, consistente com a versão já usada no `loadbalancer`.

### 2. MetricsStorageService (nova classe)

**Padrão:** Singleton com inicialização lazy.

**Comportamento:**
- Na primeira chamada a `getInstance()`, tenta ligar-se ao DynamoDB via `AmazonDynamoDBClientBuilder.defaultClient()` (usa credenciais de `~/.aws/credentials` e região de `~/.aws/config`)
- Se credenciais não existirem → `available = false`, sem erros — métricas continuam locais
- Se disponível → verifica/cria a tabela `cnv-metrics` automaticamente

**Tabela DynamoDB `cnv-metrics`:**

| Campo | Tipo | Papel |
|---|---|---|
| `requestType` | String (S) | **Partition Key** — "fractals", "dna", "grayscott" |
| `requestId` | String (S) | **Sort Key** — `{timestamp}_{uuid8}` para unicidade |
| `methodCallCount` | Number (N) | Métrica |
| `basicBlockCount` | Number (N) | Métrica |
| `elapsedTimeMs` | Number (N) | Métrica |
| `timestamp` | Number (N) | Epoch millis |
| `param_*` | String (S) | Parâmetros do pedido (ex: `param_w`, `param_h`, `param_iterations`) |

**Escrita assíncrona:** Usa `ExecutorService` com thread daemon dedicada para não bloquear o processamento dos pedidos.

**Billing:** `PAY_PER_REQUEST` (on-demand) para evitar configuração de throughput provisioned.

### 3. Integração no MetricRegistry

`stopRequest()` agora chama `MetricsStorageService.getInstance().storeAsync(snapshot)` após guardar localmente. Se DynamoDB não estiver disponível, a chamada é no-op.

## Decisões de design

- **Partition key = `requestType`:** Permite ao LB consultar eficientemente todos os pedidos de um tipo (ex: `Query` com `requestType = "fractals"`) para estimar complexidade de novos pedidos semelhantes
- **Async + daemon thread:** A escrita no DynamoDB não bloqueia o retorno da resposta ao utilizador
- **Degradação graciosa:** Sem credenciais AWS, o sistema funciona normalmente (apenas sem persistência remota)
- **No módulo javassist (não num módulo common):** O `stopRequest()` é o ponto natural de integração e já está neste módulo; evita complexidade extra de criar um novo módulo

## Para testar (quando tiveres credenciais AWS)

```bash
# Configurar credenciais (uma vez)
aws configure
# → Access Key, Secret Key, Região (ex: eu-west-1)

# Build completo
mvn clean package

# Correr worker com agent
cd webserver
java -javaagent:../javassist/target/javassist-agent-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
     -cp target/webserver-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
     pt.ulisboa.tecnico.cnv.webserver.WebServer

# Fazer um pedido
curl "http://localhost:8000/fractals?w=400&h=300&iterations=100"

# Na consola deves ver:
# [MetricsStorage] DynamoDB disponível. Tabela: cnv-metrics
# [Metrics] [fractals] params={w=400, h=300, iterations=100}, methods=..., basicblocks=..., time=...ms

# Na consola AWS DynamoDB → tabela cnv-metrics → deve ter 1 item
```

Sem credenciais, verás:
```
[MetricsStorage] DynamoDB indisponível: ...
[MetricsStorage] Métricas serão guardadas apenas localmente.
```
E tudo continua a funcionar normalmente.
