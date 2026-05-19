package pt.ulisboa.tecnico.cnv.javassist;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.model.AttributeDefinition;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.BillingMode;
import com.amazonaws.services.dynamodbv2.model.CreateTableRequest;
import com.amazonaws.services.dynamodbv2.model.KeySchemaElement;
import com.amazonaws.services.dynamodbv2.model.KeyType;
import com.amazonaws.services.dynamodbv2.model.PutItemRequest;
import com.amazonaws.services.dynamodbv2.model.ResourceInUseException;
import com.amazonaws.services.dynamodbv2.model.ResourceNotFoundException;
import com.amazonaws.services.dynamodbv2.model.ScalarAttributeType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Serviço de armazenamento de métricas no DynamoDB (MSS).
 *
 * Singleton com inicialização lazy. Se as credenciais AWS não estiverem
 * configuradas, o serviço degrada graciosamente — as métricas continuam
 * a ser guardadas localmente no MetricRegistry, apenas sem persistência
 * no DynamoDB.
 *
 * A escrita é assíncrona para não bloquear o processamento dos pedidos.
 */
public class MetricsStorageService {

    private static final String TABLE_NAME = "cnv-metrics";
    private static final String PARTITION_KEY = "requestType";
    private static final String SORT_KEY = "requestId";

    private static MetricsStorageService instance;

    private AmazonDynamoDB dynamoDB;
    private boolean available = false;

    /** Thread dedicada para escritas assíncronas no DynamoDB. */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MetricsStorage");
        t.setDaemon(true);
        return t;
    });

    private MetricsStorageService() {
        try {
            this.dynamoDB = AmazonDynamoDBClientBuilder.defaultClient();
            ensureTableExists();
            this.available = true;
            System.out.println("[MetricsStorage] DynamoDB disponível. Tabela: " + TABLE_NAME);
        } catch (Exception e) {
            System.err.println("[MetricsStorage] DynamoDB indisponível: " + e.getMessage());
            System.err.println("[MetricsStorage] Métricas serão guardadas apenas localmente.");
        }
    }

    /**
     * Obtém a instância singleton do serviço.
     * A primeira chamada tenta ligar-se ao DynamoDB.
     */
    public static synchronized MetricsStorageService getInstance() {
        if (instance == null) {
            instance = new MetricsStorageService();
        }
        return instance;
    }

    /**
     * Indica se o DynamoDB está acessível e pronto para escrita.
     */
    public boolean isAvailable() {
        return available;
    }

    /**
     * Armazena as métricas de um pedido concluído no DynamoDB de forma assíncrona.
     * Se o DynamoDB não estiver disponível, a chamada é ignorada silenciosamente.
     */
    public void storeAsync(MetricRegistry.CompletedRequest request) {
        if (!available) return;
        executor.submit(() -> store(request));
    }

    /**
     * Escrita síncrona de um CompletedRequest na tabela DynamoDB.
     */
    private void store(MetricRegistry.CompletedRequest request) {
        try {
            Map<String, AttributeValue> item = new HashMap<>();

            // Chaves primárias
            item.put(PARTITION_KEY, new AttributeValue().withS(request.getRequestType()));
            item.put(SORT_KEY, new AttributeValue().withS(
                    request.getTimestamp() + "_" + UUID.randomUUID().toString().substring(0, 8)));

            // Métricas (guardadas como Number para permitir queries/aggregations)
            item.put("methodCallCount", new AttributeValue().withN(
                    String.valueOf(request.getMethodCallCount())));
            item.put("basicBlockCount", new AttributeValue().withN(
                    String.valueOf(request.getBasicBlockCount())));
            item.put("elapsedTimeMs", new AttributeValue().withN(
                    String.valueOf(request.getElapsedTimeMs())));
            item.put("timestamp", new AttributeValue().withN(
                    String.valueOf(request.getTimestamp())));

            // Parâmetros do pedido (prefixados com param_ para evitar colisões)
            for (Map.Entry<String, String> entry : request.getParameters().entrySet()) {
                item.put("param_" + entry.getKey(), new AttributeValue().withS(entry.getValue()));
            }

            dynamoDB.putItem(new PutItemRequest().withTableName(TABLE_NAME).withItem(item));

        } catch (Exception e) {
            System.err.println("[MetricsStorage] Erro ao guardar métricas: " + e.getMessage());
        }
    }

    /**
     * Cria a tabela DynamoDB se não existir.
     * Usa PAY_PER_REQUEST (on-demand) para evitar configuração de throughput.
     */
    private void ensureTableExists() {
        try {
            dynamoDB.describeTable(TABLE_NAME);
            // Tabela já existe.
        } catch (ResourceNotFoundException e) {
            System.out.println("[MetricsStorage] A criar tabela DynamoDB: " + TABLE_NAME);

            CreateTableRequest req = new CreateTableRequest()
                    .withTableName(TABLE_NAME)
                    .withKeySchema(
                            new KeySchemaElement(PARTITION_KEY, KeyType.HASH),
                            new KeySchemaElement(SORT_KEY, KeyType.RANGE))
                    .withAttributeDefinitions(
                            new AttributeDefinition(PARTITION_KEY, ScalarAttributeType.S),
                            new AttributeDefinition(SORT_KEY, ScalarAttributeType.S))
                    .withBillingMode(BillingMode.PAY_PER_REQUEST);

            try {
                dynamoDB.createTable(req);
            } catch (ResourceInUseException raceWinner) {
                // Outro worker em paralelo já lançou createTable — não é erro.
                System.out.println("[MetricsStorage] Tabela a ser criada por outro processo — a aguardar.");
            }

            // Esperar até a tabela ficar ACTIVE (máximo ~30s).
            waitForTableActive();
        }
    }

    /**
     * Espera até a tabela ficar no estado ACTIVE (polling simples).
     */
    private void waitForTableActive() {
        try {
            for (int i = 0; i < 30; i++) {
                String status = dynamoDB.describeTable(TABLE_NAME)
                        .getTable().getTableStatus();
                if ("ACTIVE".equals(status)) {
                    System.out.println("[MetricsStorage] Tabela " + TABLE_NAME + " está ACTIVE.");
                    return;
                }
                Thread.sleep(1000);
            }
            System.err.println("[MetricsStorage] Timeout à espera que a tabela ficasse ACTIVE.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
