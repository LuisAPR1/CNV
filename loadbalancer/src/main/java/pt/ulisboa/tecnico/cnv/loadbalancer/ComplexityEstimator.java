package pt.ulisboa.tecnico.cnv.loadbalancer;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.QueryRequest;
import com.amazonaws.services.dynamodbv2.model.QueryResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Estima a complexidade (contagem prevista de basic blocks) de um pedido
 * antes de este ser encaminhado para um worker.
 *
 * Estratégia em 2 camadas:
 *   1. Histórico do DynamoDB (MSS): usa ratio-based estimation com dados passados.
 *   2. Heurísticas de fallback: fórmulas baseadas nos parâmetros do pedido.
 *
 * Os resultados do DynamoDB são cacheados em memória (30s TTL) para evitar
 * que o MSS se torne um gargalo, como avisado no enunciado.
 */
public class ComplexityEstimator {

    private static final String TABLE_NAME = "cnv-metrics";
    private static final long CACHE_TTL_MS = 30_000;
    private static final int MAX_HISTORY_RECORDS = 50;

    private AmazonDynamoDB dynamoDB;
    private boolean available = false;

    /** Cache local: requestType → histórico recente. */
    private final ConcurrentHashMap<String, CachedHistory> cache = new ConcurrentHashMap<>();

    // ─────────────────────────────────────────────────────────────
    // Estruturas de dados internas
    // ─────────────────────────────────────────────────────────────

    private static class CachedHistory {
        final List<HistoricalRecord> records;
        final long fetchedAt;

        CachedHistory(List<HistoricalRecord> records) {
            this.records = records;
            this.fetchedAt = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - fetchedAt > CACHE_TTL_MS;
        }
    }

    private static class HistoricalRecord {
        final Map<String, String> parameters;
        final long basicBlockCount;

        HistoricalRecord(Map<String, String> parameters, long basicBlockCount) {
            this.parameters = parameters;
            this.basicBlockCount = basicBlockCount;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Resultado da estimativa
    // ─────────────────────────────────────────────────────────────

    public static class Estimate {
        private final long estimatedBasicBlocks;
        private final String source;

        public Estimate(long estimatedBasicBlocks, String source) {
            this.estimatedBasicBlocks = estimatedBasicBlocks;
            this.source = source;
        }

        /** Custo estimado em basic blocks. */
        public long getEstimatedBasicBlocks() { return estimatedBasicBlocks; }

        /** Origem da estimativa: "history" ou "heuristic". */
        public String getSource() { return source; }

        @Override
        public String toString() {
            return String.format("cost=%d (%s)", estimatedBasicBlocks, source);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Construtor
    // ─────────────────────────────────────────────────────────────

    public ComplexityEstimator() {
        try {
            this.dynamoDB = AmazonDynamoDBClientBuilder.defaultClient();
            dynamoDB.describeTable(TABLE_NAME);
            this.available = true;
            System.out.println("[ComplexityEstimator] DynamoDB disponível. A usar dados históricos.");
        } catch (Exception e) {
            System.err.println("[ComplexityEstimator] DynamoDB indisponível: " + e.getMessage());
            System.err.println("[ComplexityEstimator] A usar apenas heurísticas baseadas em parâmetros.");
        }
    }

    // ─────────────────────────────────────────────────────────────
    // API pública
    // ─────────────────────────────────────────────────────────────

    /**
     * Estima a complexidade de um pedido.
     *
     * @param requestType tipo do pedido: "fractals", "grayscott", "dna"
     * @param parameters  parâmetros do query string (ex: {w=400, h=300, iterations=100})
     * @return estimativa com custo previsto e fonte
     */
    public Estimate estimate(String requestType, Map<String, String> parameters) {
        // Tentar estimativa baseada no histórico DynamoDB.
        if (available) {
            long historyEstimate = estimateFromHistory(requestType, parameters);
            if (historyEstimate > 0) {
                return new Estimate(historyEstimate, "history");
            }
        }
        // Fallback: heurísticas baseadas nos parâmetros.
        return new Estimate(estimateFromHeuristics(requestType, parameters), "heuristic");
    }

    public boolean isDynamoDBAvailable() {
        return available;
    }

    // ─────────────────────────────────────────────────────────────
    // Estimativa baseada no histórico (ratio-based)
    // ─────────────────────────────────────────────────────────────

    /**
     * Modelo ratio-based:
     *   1. Para cada pedido histórico, calcula ratio = basicBlockCount / feature
     *   2. Calcula média dos ratios
     *   3. Estimativa = avgRatio × feature_do_novo_pedido
     *
     * Isto é equivalente a uma regressão linear simples: basicBlocks = k × feature
     */
    private long estimateFromHistory(String requestType, Map<String, String> parameters) {
        List<HistoricalRecord> history = getHistory(requestType);
        if (history.isEmpty()) return -1;

        double featureNew = extractFeature(requestType, parameters);
        if (featureNew <= 0) return -1;

        double sumRatios = 0;
        int count = 0;
        for (HistoricalRecord rec : history) {
            double featureHist = extractFeature(requestType, rec.parameters);
            if (featureHist > 0 && rec.basicBlockCount > 0) {
                sumRatios += (double) rec.basicBlockCount / featureHist;
                count++;
            }
        }

        if (count == 0) return -1;
        double avgRatio = sumRatios / count;
        return Math.max(1, (long) (avgRatio * featureNew));
    }

    // ─────────────────────────────────────────────────────────────
    // Extração de features por tipo de workload
    // ─────────────────────────────────────────────────────────────

    /**
     * Extrai uma feature numérica dos parâmetros que se correlaciona com a complexidade.
     * A feature é usada tanto para estimar como para calcular ratios do histórico.
     */
    private double extractFeature(String requestType, Map<String, String> parameters) {
        switch (requestType) {
            case "fractals": {
                // Cada pixel corre o loop de iteração da Julia set.
                double w = parseDouble(parameters, "w", 800);
                double h = parseDouble(parameters, "h", 600);
                double iter = parseDouble(parameters, "iterations", 100);
                return w * h * iter;
            }
            case "grayscott": {
                // Grid NxN onde cada célula é atualizada em cada iteração.
                double size = parseDouble(parameters, "size", 256);
                double maxIter = parseDouble(parameters, "maxIterations", 5000);
                return size * size * maxIter;
            }
            case "dna": {
                // Complexidade proporcional ao tamanho das sequências,
                // inversamente proporcional ao minLength.
                // seq1/seq2 vêm como "name:content" — o comprimento do parâmetro
                // é um proxy razoável para o comprimento da sequência.
                double minLen = parseDouble(parameters, "minLength", 1);
                if (minLen < 1) minLen = 1;
                String seq1 = parameters.getOrDefault("seq1", "");
                String seq2 = parameters.getOrDefault("seq2", "");
                double seqFactor = Math.max(1, (double) seq1.length() * seq2.length());
                boolean stopOnFirst = "true".equalsIgnoreCase(parameters.getOrDefault("stopOnFirst", "false"));
                // stopOnFirst reduz significativamente o trabalho (fator ~0.3)
                double stopFactor = stopOnFirst ? 0.3 : 1.0;
                return (seqFactor / minLen) * stopFactor;
            }
            default:
                return 1.0;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Heurísticas de fallback (sem histórico)
    // ─────────────────────────────────────────────────────────────

    /**
     * Estimativa puramente baseada nos parâmetros, sem dados históricos.
     * Os multiplicadores são calibráveis — serão afinados quando houver dados reais.
     */
    private long estimateFromHeuristics(String requestType, Map<String, String> parameters) {
        switch (requestType) {
            case "fractals": {
                long w = parseLong(parameters, "w", 800);
                long h = parseLong(parameters, "h", 600);
                long iter = parseLong(parameters, "iterations", 100);
                // ~10 BBs estimados por pixel-iteração (calibrável)
                return w * h * iter * 10;
            }
            case "grayscott": {
                long size = parseLong(parameters, "size", 256);
                long maxIter = parseLong(parameters, "maxIterations", 5000);
                // ~5 BBs estimados por célula-iteração (calibrável)
                return size * size * maxIter * 5;
            }
            case "dna": {
                long minLen = parseLong(parameters, "minLength", 1);
                if (minLen < 1) minLen = 1;
                String seq1 = parameters.getOrDefault("seq1", "");
                String seq2 = parameters.getOrDefault("seq2", "");
                long seqProduct = Math.max(1, (long) seq1.length() * seq2.length());
                boolean stopOnFirst = "true".equalsIgnoreCase(parameters.getOrDefault("stopOnFirst", "false"));
                long base = seqProduct / minLen;
                return Math.max(1000, stopOnFirst ? base / 3 : base);
            }
            default:
                return 10000;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Cache + consulta ao DynamoDB
    // ─────────────────────────────────────────────────────────────

    /**
     * Obtém o histórico de pedidos de um tipo, usando cache com TTL de 30s.
     */
    private List<HistoricalRecord> getHistory(String requestType) {
        CachedHistory cached = cache.get(requestType);
        if (cached != null && !cached.isExpired()) {
            return cached.records;
        }

        try {
            Map<String, AttributeValue> exprValues = new HashMap<>();
            exprValues.put(":rt", new AttributeValue().withS(requestType));

            QueryRequest queryReq = new QueryRequest()
                    .withTableName(TABLE_NAME)
                    .withKeyConditionExpression("requestType = :rt")
                    .withExpressionAttributeValues(exprValues)
                    .withScanIndexForward(false)
                    .withLimit(MAX_HISTORY_RECORDS);

            QueryResult result = dynamoDB.query(queryReq);

            List<HistoricalRecord> records = new ArrayList<>();
            for (Map<String, AttributeValue> item : result.getItems()) {
                Map<String, String> params = new HashMap<>();
                long bbc = 0;

                for (Map.Entry<String, AttributeValue> entry : item.entrySet()) {
                    if (entry.getKey().startsWith("param_") && entry.getValue().getS() != null) {
                        params.put(entry.getKey().substring(6), entry.getValue().getS());
                    }
                    if ("basicBlockCount".equals(entry.getKey()) && entry.getValue().getN() != null) {
                        bbc = Long.parseLong(entry.getValue().getN());
                    }
                }
                records.add(new HistoricalRecord(params, bbc));
            }

            CachedHistory newCache = new CachedHistory(records);
            cache.put(requestType, newCache);
            System.out.println("[ComplexityEstimator] Cache atualizado para '" + requestType
                    + "': " + records.size() + " registos");
            return records;

        } catch (Exception e) {
            System.err.println("[ComplexityEstimator] Erro ao consultar DynamoDB: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Utilitários de parsing
    // ─────────────────────────────────────────────────────────────

    private double parseDouble(Map<String, String> params, String key, double defaultVal) {
        try {
            String val = params.get(key);
            return val != null ? Double.parseDouble(val) : defaultVal;
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    private long parseLong(Map<String, String> params, String key, long defaultVal) {
        try {
            String val = params.get(key);
            return val != null ? Long.parseLong(val) : defaultVal;
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }
}
