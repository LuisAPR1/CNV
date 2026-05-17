# FIX 01 — Refatorar MetricRegistry para Dados Estruturados

> **Data:** 2025-05-17  
> **Ficheiro alterado:** `javassist/src/main/java/pt/ulisboa/tecnico/cnv/javassist/MetricRegistry.java`

---

## Problema

O `MetricRegistry` original armazenava as métricas de pedidos concluídos como strings formatadas num `ConcurrentHashMap<String, String>`. Isto causava 3 problemas:

1. **Perda de dados estruturados** — ao guardar apenas o resultado de `toString()`, era impossível extrair programaticamente valores individuais (method calls, basic blocks, tempo)
2. **Crescimento ilimitado** — o mapa nunca era limpo, crescendo indefinidamente em memória
3. **URI não era decomposto** — o `requestId` era a URI completa (ex: `/fractals?w=400&h=300&iterations=100`), sem separação entre tipo de pedido e parâmetros

## Solução

### Nova classe `CompletedRequest` (snapshot imutável)

- Criada no final de cada pedido via `RequestMetrics.snapshot()`
- Campos tipados: `requestType`, `parameters`, `methodCallCount`, `basicBlockCount`, `elapsedTimeMs`, `timestamp`
- Método `toMap()` que produz `Map<String, String>` pronto para DynamoDB (parâmetros prefixados com `param_`)

### `RequestMetrics` refatorado (acumulador mutável)

- Novo método `parseUri(String uri)` que decompõe a URI:
  - `/fractals?w=400&h=300&iterations=100` → `requestType="fractals"`, `parameters={w:400, h:300, iterations:100}`
- Campo `requestId` substituído por `requestType` + `parameters`
- Novo método `snapshot()` para criar `CompletedRequest` imutável

### Armazenamento limitado

- `ConcurrentHashMap<String, String>` substituído por `ConcurrentLinkedDeque<CompletedRequest>`
- Limitado a 1000 entradas (mais recentes primeiro, mais antigas descartadas)

### Novos métodos utilitários

- `getCompletedMetricsByType(String)` — filtra histórico por tipo de workload
- `clearCompletedMetrics()` — limpa todo o histórico

## Impacto

- **Zero alterações no `JavassistAgent.java`** — as 4 assinaturas injetadas (`startRequest`, `stopRequest`, `incrementMethodCalls`, `incrementBasicBlocks`) mantêm-se idênticas
- **Compilação verificada** — `mvn compile` passa sem erros em todos os módulos
- **Preparado para DynamoDB** — o `CompletedRequest.toMap()` está pronto para ser usado diretamente como item do DynamoDB na fase seguinte
