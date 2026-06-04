# Test Plan — Nature@Cloud (2026-06-04)

> Cada nível deve ser executado por ordem. Quando um nível for concluído,
> muda o status para `DONE ✅` e adiciona uma descrição curta do resultado.

---

## Nível 0 — Sanidade Local (Build + JARs)

**Status:** DONE ✅

**Descrição:** `mvn clean package -DskipTests` + verificar 3 JARs + `LambdaInvoker.class`.

**Resultado:** BUILD SUCCESS. 3 JARs OK (webserver 15MB, javassist 10MB, loadbalancer 19MB). `LambdaInvoker.class` presente no JAR do LB.

---

## Nível 1 — Worker Local + Métrica Composta CPU+RAM

**Status:** DONE ✅

**Descrição:** Worker local com agente javassist. Verificar `allocatedBytes` nos logs.

**Resultado:** Worker responde HTTP 200. Métrica composta confirmada: `instructions=30496535, alloc=2713368B, time=100ms`. allocatedBytes tracking funcional.

---

## Nível 2 — LB Local + ComplexityEstimator

**Status:** DONE ✅

**Descrição:** LB local com 1 worker. Verificar estimativas compostas nos logs.

**Resultado:** LB responde HTTP 200. Estimativa composta: `cost=41320000 (heuristic, wCpu=1,00 wRam=1,0000) (0,02s)`. Pesos CPU+RAM aplicados. Heuristic fallback ativo (DynamoDB offline localmente — esperado).

---

## Nível 3 — Credenciais AWS

**Status:** DONE ✅

**Descrição:** `aws sts get-caller-identity` — confirmar acesso à conta AWS.

**Resultado:** AWS CLI v2.34.61 funcional via WSL. Conta `577267183760`, user `cnv-admin-luis` com permissões IAM/EC2/DynamoDB/Lambda. Região `eu-west-1`.

---

## Nível 4 — Pipeline de Scripts AWS (IAM → LB)

**Status:** DONE ✅

**Descrição:** `01-setup-iam.sh` → `02-setup-network.sh` → `03-create-ami.sh` → `04-launch-worker.sh` → `05-launch-lb.sh`.

**Resultado:** Pipeline completa (~7 min). IAM: 3 roles + Lambda ARN persistido. Network: 2 SGs + keypair. AMI: `ami-00e3402f5d0f8206e` (Amazon Linux 2023 + Java 11 + JARs + systemd). Worker: `i-02f9e743d78dc7a6e` @ `3.250.49.46:8000`. LB: `i-021445053fcd11e36` @ `54.229.139.236:8080`. Nota: todos os scripts precisaram de conversão CRLF→LF para correr no WSL.

---

## Nível 5 — Validação End-to-End na AWS

**Status:** DONE ✅

**Descrição:** 3 workloads via LB (fractals, grayscott, dna) + DynamoDB com métricas.

**Resultado:** 3/3 workloads HTTP 200. Fractals: 106KB em 1.06s. GrayScott: 678B em 0.83s. DNA: 2.5KB em 0.21s. DynamoDB: 41 items. Logs confirmam: `[ComplexityEstimator] DynamoDB disponível. A usar dados históricos.`, `[LambdaInvoker] AWS Lambda client inicializado.`, estimativas compostas com pesos `wCpu=1.00 wRam=1.0000` (fractals: 0.12s, grayscott: 0.64s). AutoScaler descobriu workers existentes e fez scale-down do extra — funcional.

---

## Nível 6 — Lambda Integration

**Status:** DONE ✅

**Descrição:** `06-deploy-lambdas.sh` + invoke direto + Lambda fast-path/fallback via LB.

**Resultado:** 3 Lambdas deployed (cnv-fractals, cnv-grayscott, cnv-dna). Invoke direto: `aws lambda invoke --function-name cnv-fractals` retorna PNG base64 válido. Lambda fast-path confirmado nos logs do LB: `[LoadBalancer] All workers busy (>80%) + request Lambda-eligible — routing directly to Lambda.` — o pedido leve foi corretamente desviado para Lambda enquanto o worker estava sobrecarregado com 10 pedidos pesados. LambdaInvoker funcional em produção.

---

## Nível 7 — AutoScaler (Scale Up/Down)

**Status:** DONE ✅

**Descrição:** Carga pesada → scale-up 1→2+ workers → esperar → scale-down.

**Resultado:** SCALE UP confirmado: `[AutoScaler] SCALE UP (avgWork=75.98s > 2.5s)` → lançou `i-00ee37e1b9549f938`. SCALE DOWN confirmado: `[AutoScaler] SCALE DOWN (avgWork=0.00s < 0.6s)` → terminou worker original `i-02f9e743d78dc7a6e`. Health checks: novo worker falhou inicialmente (systemd a arrancar) e recuperou. Thresholds wall-clock (2.5s up / 0.6s down) a funcionar corretamente.

---

## Cleanup

**Status:** DONE ✅

**Descrição:** `./99-cleanup.sh` + confirmar 0 instâncias running.

**Resultado:** 4 instâncias terminadas (worker i-00ee37e1b9549f938, LB i-021445053fcd11e36, + 2 órfãs de sessões anteriores). Confirmado 0 instâncias running. Infraestrutura (SG/KeyPair/IAM/DynamoDB/AMI/Lambdas) preservada para próxima sessão.
