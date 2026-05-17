# FIX 03 — Estimador de Complexidade no Load Balancer

> **Data:** 2025-05-17  
> **Ficheiros alterados/criados:**
> - `loadbalancer/pom.xml` — nova dependência DynamoDB
> - `loadbalancer/.../ComplexityEstimator.java` — novo ficheiro
> - `loadbalancer/.../LoadBalancer.java` — integração do estimador

---

## Objetivo

Dar ao Load Balancer a capacidade de **estimar o custo computacional de cada pedido** antes de o encaminhar, usando dados históricos do DynamoDB e heurísticas de fallback.

## Modelo de Estimação

### Estratégia em 2 camadas

```
Pedido chega ao LB
       │
       ▼
  Há histórico no DynamoDB? ──sim──→ Ratio-based estimation
       │                              avgRatio = média(BBcount / feature)
       │no                            estimativa = avgRatio × feature_novo
       ▼
  Heurística baseada em parâmetros
  (fórmulas calibráveis)
```

### Features por tipo de workload

| Workload | Feature numérica | Racional |
|---|---|---|
| `fractals` | `w × h × iterations` | Cada pixel corre o loop de iteração Julia |
| `grayscott` | `size² × maxIterations` | Grid NxN iterado |
| `dna` | `len(seq1) × len(seq2) / minLength × stopFactor` | Matching proporcional ao tamanho, inversamente ao minLength; stopOnFirst reduz ~70% |

### Modelo ratio-based (com histórico)

Para cada tipo de workload, consulta os últimos 50 registos no DynamoDB:
1. Para cada registo histórico: `ratio = basicBlockCount / feature(params_historico)`
2. Média dos ratios: `avgRatio = soma(ratios) / count`
3. Estimativa: `avgRatio × feature(params_novo_pedido)`

Isto equivale a uma **regressão linear simples** passando pela origem: `basicBlocks = k × feature`

### Heurísticas de fallback (sem histórico)

Multiplicadores fixos (calibráveis quando houver dados reais):
- **fractals:** `w × h × iterations × 10` BBs
- **grayscott:** `size² × maxIterations × 5` BBs
- **dna:** `seqProduct / minLength` (min 1000 BBs)

## Cache do DynamoDB

O enunciado avisa: *"consultar e iterar exaustivamente este sistema de armazenamento pode tornar-se um gargalo"*.

Implementação:
- **TTL de 30 segundos** por tipo de workload
- `ConcurrentHashMap<String, CachedHistory>` em memória
- Máximo de 50 registos por tipo (só os mais recentes)
- Logs quando o cache é atualizado

## Integração no LoadBalancer

O `ForwardHandler.handle()` agora:
1. Parseia os query parameters (`parseQuery()`)
2. Chama `complexityEstimator.estimate(requestType, params)`
3. Loga a estimativa: `[LoadBalancer] Complexity estimate for /fractals: cost=480000000 (heuristic)`

Por agora, a estimativa é apenas **logada** (informativa). Na Fase 4 (routing inteligente), será usada para:
- Escolher o worker com menos trabalho **estimado** restante (não apenas contagem de pedidos)
- Decidir entre EC2 e Lambda com base no custo estimado

## Resultado da Estimate

```java
ComplexityEstimator.Estimate {
    long estimatedBasicBlocks;  // custo previsto
    String source;              // "history" ou "heuristic"
}
```

## Degradação graciosa

Sem DynamoDB → `source = "heuristic"` sempre, sem erros.

## Exemplo de output esperado

```
[ComplexityEstimator] DynamoDB indisponível: ...
[ComplexityEstimator] A usar apenas heurísticas baseadas em parâmetros.
[LoadBalancer] Listening on port 8080
[LoadBalancer] Complexity estimate for /fractals: cost=480000000 (heuristic)
[LoadBalancer] Forwarding /fractals -> localhost:8000 (attempt 1)
```
