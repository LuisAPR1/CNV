# Handoff Fase 2 — Deployment AWS (Validado)

> **Para:** Colega que vai continuar o projeto
> **De:** Laura
> **Data:** 2025-05-19
> **Estado:** ✅ Fase 2 100% validada em AWS real

---

## 1. Resumo Executivo

A Fase 2 (Deployment AWS) está **completa e validada end-to-end**. O sistema corre na AWS (`eu-west-1`, conta `577267183760`) com:

- **Load Balancer** em EC2 dedicada (t3.micro), porta 8080
- **Workers** em EC2 (t3.micro), porta 8000, com systemd `cnv-worker.service`
- **AutoScaler** funcional — scale-up real observado (1→3 workers com 20 pedidos `2000×2000×2000`)
- **ComplexityEstimator** ratio-based com histórico DynamoDB (45+ records)
- **DynamoDB** `cnv-metrics` com escritas assíncronas
- **Health checks** automáticos a cada 15s (remove worker após 3 falhas)

---

## 2. Como Arrancar (do zero)

```bash
cd CNV/

# 1. Compilar tudo
mvn clean package -DskipTests

# 2. Setup (uma vez por conta AWS)
cd scripts
./01-setup-iam.sh        # Cria 3 roles + inline iam:PassRole
./02-setup-network.sh    # Cria 2 SGs + key pair
./03-create-ami.sh       # Cria AMI worker (Java + JARs + systemd)

# 3. Lançar runtime
./04-launch-worker.sh    # Lança 1 worker
./05-launch-lb.sh $(head -1 .state/worker-instance-ids.txt)  # Lança LB

# 4. Testar (resposta e data URL "data:image/png;base64,<b64>" - decodificar)
LB_IP=$(cat .state/lb-ip.txt)
curl -s "http://$LB_IP:8080/fractals?w=400&h=300&iterations=100" \
    | sed 's/^data:image\/png;base64,//' | base64 -d > test.png
```

---

## 3. Estrutura de Ficheiros Relevante

```
CNV/
├── scripts/
│   ├── aws-config.sh          # Variáveis partilhadas (region, nomes, AMI base via SSM)
│   ├── 01-setup-iam.sh        # 3 IAM Roles + inline iam:PassRole
│   ├── 02-setup-network.sh    # 2 SGs + key pair
│   ├── 03-create-ami.sh       # AMI worker pré-cozida
│   ├── 04-launch-worker.sh    # Lança worker da AMI
│   ├── 05-launch-lb.sh        # Lança LB + SCP JAR + nohup java
│   ├── 99-cleanup.sh          # Termina EC2s; --deep apaga tudo
│   └── .state/                # Ficheiros de estado (gitignored)
├── loadbalancer/src/.../loadbalancer/
│   ├── LoadBalancer.java      # Ponto de entrada, ForwardHandler
│   ├── WorkerPool.java        # Pool + health checks + least-loaded
│   ├── AutoScaler.java        # Scheduler 5s, threshold 1.0/0.25
│   ├── ComplexityEstimator.java  # Ratio-based + heuristic fallback
│   └── AwsConfig.java         # Config centralizada (-D properties)
├── webserver/src/.../webserver/
│   ├── WebServer.java         # Servidor HTTP multi-threaded
│   └── RootHandler.java       # Health check endpoint (/)
├── javassist/src/.../javassist/
│   ├── JavassistAgent.java    # Instrumentação load-time
│   ├── MetricRegistry.java    # Métricas thread-local
│   └── MetricsStorageService.java  # DynamoDB async writes
└── docs/
    ├── 01-project-status-and-roadmap_pt.md
    ├── 02-fase2-aws-deployment.md
    ├── 04-testing-checklist.md
    └── 05-handoff-fase2.md    (este ficheiro)
```

---

## 4. Configuração do AutoScaler

| Parâmetro | Valor | Nota |
|---|---|---|
| `CHECK_INTERVAL_SECONDS` | 5 | Era 15; reduzido para apanhar bursts |
| `SCALE_UP_THRESHOLD` | 1.0 | Era 3.0; reduzido para escalar mais cedo |
| `SCALE_DOWN_THRESHOLD` | 0.25 | Era 0.5 |
| `COOLDOWN_MS` | 60 000 | 60s entre ações |
| `MIN_WORKERS` | 1 | |
| `MAX_WORKERS` | 5 | Cuidado com quota EC2 (5 vCPUs default = ~2 instâncias) |

---

## 5. Bugs Corrigidos Durante a Validação

| Bug | Sintoma | Fix | Ficheiro |
|---|---|---|---|
| `RootHandler` health check corrompido | `curl /` dava timeout 10s | `sendResponseHeaders(200, -1)` em vez de `(200, 0)` | `webserver/.../RootHandler.java:31` |
| `iam:PassRole` ausente | `[AutoScaler] Falha no SCALE UP: not authorized` | Inline policy `CNV-AllowPassWorkerRole` | `scripts/01-setup-iam.sh` (linhas 71-92) |
| Race condition DynamoDB `createTable` | 2 workers paralelos → `ResourceInUseException` | `catch (ResourceInUseException)` | `javassist/.../MetricsStorageService.java:144-149` |
| Worker duplicado no `04-launch-worker.sh` | 2ª execução lançava 2º worker sem querer | Verificação `head -1` + `wc -l` | `scripts/04-launch-worker.sh` |
| AutoScaler não apanhava bursts | Pedidos <5s completavam antes do tick | Interval 15→5s, threshold 3.0→1.0 | `loadbalancer/.../AutoScaler.java:39-40` |
| IMDSv1 não funciona em AL2023 | `curl 169.254.169.254` vazio | Usar `aws sts get-caller-identity` (IMDSv2) | N/A (documentado) |

---

## 6. O Que Falta (Próximos Passos)

| # | Tarefa | Prioridade | Notas |
|---|---|---|---|
| 1 | **Relatório intermédio** (1 pág) | ALTA | Já temos todos os dados |
| 2 | **Vídeo demo** checkpoint | ALTA | Mostrar scale-up real |
| 3 | **Routing por complexidade** (Fase 4.1) | MÉDIA | `Estimate` já calculado; falta usar na escolha do worker |
| 4 | **Instrumentação real de BBs** | MÉDIA | Heurística atual torna `basicBlockCount` flat para fractais |
| 5 | **Lambda workers** (Fase 3) | BAIXA | 3 handlers → deploy como Lambda |
| 6 | **Lambda vs EC2 routing** | BAIXA | Decisão custo/latência por pedido |

---

## 7. Comandos Úteis

```bash
# Ver estado das EC2
aws ec2 describe-instances \
    --filters "Name=tag:Project,Values=NatureAtCloud" "Name=instance-state-name,Values=running" \
    --region eu-west-1 \
    --query "Reservations[].Instances[].[InstanceId,Tags[?Key=='Role']|[0].Value,PublicIpAddress]" \
    --output table

# Logs do LB em tempo real
ssh -i scripts/cnv-keypair.pem ec2-user@<LB_IP> "tail -f /opt/cnv/lb.log"

# Logs do worker
ssh -i scripts/cnv-keypair.pem ec2-user@<WORKER_IP> "sudo journalctl -u cnv-worker.service -f"

# Ver métricas no DynamoDB
aws dynamodb scan --table-name cnv-metrics --region eu-west-1 --max-items 5

# Cleanup (PARA TUDO)
cd scripts && ./99-cleanup.sh --deep
```

---

## 8. Notas para o Colega

- **NÃO apagues o `04-testing-checklist.md`** — serve para revalidar quando criares a tua própria conta AWS.
- A AMI worker atual (`ami-0a1f2b3c4d5e6f7g8`) está na conta `577267183760`. Se criares conta nova, tens de gerar nova AMI com `./03-create-ami.sh`.
- O `.gitignore` já bloqueia `.pem`, `.state/`, `credentials*` — não vai haver leaks acidentais.
- A quota EC2 default (5 vCPUs) limita a ~2 instâncias t3.micro. Para a demo final com 5 workers, pede aumento de quota com antecedência.
- O `ComplexityEstimator` usa rácio do histórico DynamoDB. Se a tabela estiver vazia (conta nova), faz fallback para heurística `w * h * iterations / 1_000_000`. Funciona, mas é menos preciso.
