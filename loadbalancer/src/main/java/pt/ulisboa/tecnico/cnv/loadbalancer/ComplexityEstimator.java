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
 * Estima a complexidade (contagem prevista de instruções bytecode) de um pedido
 * antes de este ser encaminhado para um worker.
 *
 * <p>A unidade da estimativa é <b>instruções bytecode executadas</b> dentro do
 * código instrumentado dos workloads (ICount), correspondente ao campo
 * {@code instructionCount} na tabela DynamoDB. Esta é a métrica primária
 * recolhida pelo {@code JavassistAgent}.
 *
 * <p>Estratégia em 2 camadas:
 * <ol>
 *   <li><b>Histórico do DynamoDB (MSS)</b>: ratio-based estimation
 *       (regressão linear simples) com pedidos passados do mesmo tipo.</li>
 *   <li><b>Heurísticas de fallback</b>: fórmulas baseadas nos parâmetros
 *       quando não há histórico ou o DynamoDB está indisponível.</li>
 * </ol>
 *
 * <p>Os resultados do DynamoDB são cacheados em memória (30s TTL) para evitar
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
        final long instructionCount;

        HistoricalRecord(Map<String, String> parameters, long instructionCount) {
            this.parameters = parameters;
            this.instructionCount = instructionCount;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Resultado da estimativa
    // ─────────────────────────────────────────────────────────────

    public static class Estimate {
        private final long estimatedCost;
        private final String source;

        public Estimate(long estimatedCost, String source) {
            this.estimatedCost = estimatedCost;
            this.source = source;
        }

        /** Custo estimado, em instruções bytecode (ICount). */
        public long getEstimatedCost() { return estimatedCost; }

        /** Origem da estimativa: "history" ou "heuristic". */
        public String getSource() { return source; }

        @Override
        public String toString() {
            return String.format("cost=%d (%s)", estimatedCost, source);
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
     *   1. Para cada pedido histórico, calcula ratio = instructionCount / feature
     *   2. Calcula média dos ratios
     *   3. Estimativa = avgRatio × feature_do_novo_pedido
     *
     * Isto é equivalente a uma regressão linear simples: cost = k × feature
     *
     * Registos do histórico com {@code instructionCount == 0} são saltados
     * (e.g. dados legacy do esquema antigo "basicBlockCount", ou pedidos onde
     * o agente Javassist não correu).
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
            if (featureHist > 0 && rec.instructionCount > 0) {
                sumRatios += (double) rec.instructionCount / featureHist;
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
     * Cap para o parâmetro {@code iterations} dos fractals.
     * <p>Medições empíricas (2026-05-21, matriz extensiva, ver
     * {@code docs/01.6_calibration_evidence.md}) demonstram que o Julia-set
     * c=(-0.7, 0.6) <b>satura aos ~500 iterações</b>: ICount com iter=500,
     * 1000 ou 2000 é <em>idêntico</em> (140.7M para w=h=400). Todos os pixels
     * que vão escapar já escaparam, e os do "interior" do set continuam até
     * iter, mas são uma fração pequena do trabalho total.
     */
    private static final long FRACTAL_ITER_SATURATION = 500L;

    /**
     * Extrai uma feature numérica dos parâmetros que se correlaciona com a complexidade.
     * A feature é usada tanto para estimar como para calcular ratios do histórico.
     *
     * <p>Calibrado com 33 medições (15 + 18) em 2026-05-21.
     * Ver {@code docs/01.6_calibration_evidence.md} para a análise completa.
     */
    private double extractFeature(String requestType, Map<String, String> parameters) {
        switch (requestType) {
            case "fractals": {
                // Cada pixel corre o loop de iteração da Julia set.
                // CAP iter aos 500 — acima disto, ICount não muda (Julia-set
                // satura, pixels que vão escapar já escaparam).
                double w = parseDouble(parameters, "w", 800);
                double h = parseDouble(parameters, "h", 600);
                double iter = parseDouble(parameters, "iterations", 100);
                double effIter = Math.min(iter, (double) FRACTAL_ITER_SATURATION);
                return w * h * effIter;
            }
            case "grayscott": {
                // Grid NxN onde cada célula é atualizada em cada iteração.
                // Validado empiricamente: ratio 164 instr/cell-iter constante
                // para qualquer dos 3 seedModes válidos (center/ring/stripe
                // tiveram ICount com diferença <0.01% no mesmo size/maxIter).
                // stopOnExtinction também não teve efeito mensurável (mesmo na
                // "death zone" f=0.022/k=0.051 a extinção não disparou).
                double size = parseDouble(parameters, "size", 256);
                double maxIter = parseDouble(parameters, "maxIterations", 5000);
                return size * size * maxIter;
            }
            case "dna": {
                // Trabalho ~linear no comprimento da sequência MAIOR (não no
                // produto): 4 medições entre 16 e 500 chars deram
                // instr/max(seq1,seq2) entre 123 e 149 (variância <20%).
                // minLength e stopOnFirst não tiveram efeito mensurável.
                String seq1 = parameters.getOrDefault("seq1", "");
                String seq2 = parameters.getOrDefault("seq2", "");
                return Math.max(1, (double) Math.max(seq1.length(), seq2.length()));
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
     * Os multiplicadores convertem a feature ("unidades de trabalho lógico")
     * em <b>instruções bytecode</b> esperadas.
     *
     * <p>Calibração 2026-05-21 baseada em 33 medições empíricas observadas
     * localmente (ver {@code docs/01.6_calibration_evidence.md}):
     * <ul>
     *   <li><b>fractals</b>: ratio depende fortemente de iter (Julia-set tem
     *       3 regimes: ramp-up iter&lt;100, transição 100-300, plateau iter≥300).
     *       Heurística usa multiplicador piecewise para cobrir os 3.</li>
     *   <li><b>grayscott</b>: ratio = 164 instr/(célula·iter) com variância
     *       &lt;1% (confirmado em 11 medições cobrindo s64 a s384,
     *       3 seedModes, e f/k diferentes).</li>
     *   <li><b>dna</b>: linear em max(seq1.length, seq2.length) com ratio
     *       ~125 instr/char (variância &lt;20% em 4 medições entre 16 e 500 chars).</li>
     * </ul>
     */
    private long estimateFromHeuristics(String requestType, Map<String, String> parameters) {
        switch (requestType) {
            case "fractals": {
                long w = parseLong(parameters, "w", 800);
                long h = parseLong(parameters, "h", 600);
                long iter = parseLong(parameters, "iterations", 100);
                // CAP iter aos 500 (Julia-set satura, ver constante FRACTAL_ITER_SATURATION).
                long effIter = Math.min(iter, FRACTAL_ITER_SATURATION);
                // Multiplicador piecewise (3 regimes empíricos):
                //   - iter ≤ 100: ratio observado ~11 (ramp-up: pixels do bordo ainda crescem com iter)
                //   - 100 < iter ≤ 300: ratio observado ~5 (transição: maioria dos pixels já escapou)
                //   - iter > 300: ratio observado ~2 (plateau: trabalho saturado)
                long multiplier;
                if (iter <= 100) {
                    multiplier = 10;
                } else if (iter <= 300) {
                    multiplier = 5;
                } else {
                    multiplier = 2;
                }
                return w * h * effIter * multiplier;
            }
            case "grayscott": {
                long size = parseLong(parameters, "size", 256);
                long maxIter = parseLong(parameters, "maxIterations", 5000);
                // Ratio empírico extremamente estável: 164 instr/(célula·iter)
                // independente de seedMode e f/k.
                return size * size * maxIter * 164;
            }
            case "dna": {
                String seq1 = parameters.getOrDefault("seq1", "");
                String seq2 = parameters.getOrDefault("seq2", "");
                long maxSeq = Math.max(1, (long) Math.max(seq1.length(), seq2.length()));
                // Ratio empírico: ~125 instr/char da sequência maior.
                return Math.max(1000, maxSeq * 125);
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
                long ic = 0;

                for (Map.Entry<String, AttributeValue> entry : item.entrySet()) {
                    if (entry.getKey().startsWith("param_") && entry.getValue().getS() != null) {
                        params.put(entry.getKey().substring(6), entry.getValue().getS());
                    }
                    // Campo primário: instructionCount (ICount).
                    // Registos do esquema antigo (apenas com basicBlockCount) ficam com ic=0
                    // e são deliberadamente filtrados pelo estimador ratio-based.
                    if ("instructionCount".equals(entry.getKey()) && entry.getValue().getN() != null) {
                        ic = Long.parseLong(entry.getValue().getN());
                    }
                }
                records.add(new HistoricalRecord(params, ic));
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
