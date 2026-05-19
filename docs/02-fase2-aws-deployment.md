# Fase 2 — Deployment AWS

> Documento dedicado à **Fase 2 do roadmap (§9.2)** — implementação da
> infraestrutura e do código que permite o sistema correr na AWS.
>
> **Estado:** ✅ **Validado end-to-end em AWS real** (2025-05-18/19, laura).
> Workers, LB, AutoScaler, ComplexityEstimator, DynamoDB e scale-up real
> todos a funcionar. Bugs encontrados durante a validação foram corrigidos
> upstream nos scripts e código. Pronto para checkpoint.

---

## 1. Mapa do que está feito vs. roadmap

### §9.2.1 — Scripts de deployment (TODOS criados em `scripts/`)

| Roadmap original | Implementado | Notas |
|---|---|---|
| `setup-security-group.sh` | `02-setup-network.sh` | Inclui também o key pair (tinha de ser criado algures). Cria 2 SGs: `cnv-worker-sg` (8000 só do LB + 22 do nosso IP) e `cnv-lb-sg` (8080 público + 22 do nosso IP). |
| `create-ami.sh` | `03-create-ami.sh` | Lança builder EC2 → instala Java 11 → SCP dos JARs → instala systemd unit `cnv-worker.service` → snapshot AMI → termina builder. AMI ID guardado em `.state/worker-ami-id.txt`. |
| `launch-worker.sh` | `04-launch-worker.sh` | Lança 1 worker da AMI. O systemd auto-arranca o worker — fica a servir em ~30s sem intervenção. |
| `launch-lb.sh` | `05-launch-lb.sh` | Lança LB, instala Java via user-data, copia JAR via SCP, arranca o LB com todas as `-D` properties necessárias para o AutoScaler. |
| `cleanup.sh` | `99-cleanup.sh` | Default: termina EC2s. `--deep`: apaga SGs/IAM/KeyPair/AMI/snapshots/DynamoDB. |
| **(extra)** | `01-setup-iam.sh` | **Necessário** — sem as IAM Roles e Instance Profiles, os outros scripts e o AutoScaler não funcionam. Cria as 3 roles do §6.2 + inline policy `CNV-AllowPassWorkerRole` (permite `iam:PassRole` no LB para lançar workers). |
| **(extra)** | `aws-config.sh` | Variáveis partilhadas (region, nomes, AMI base). |

**Decisão sobre DynamoDB:** o roadmap não pedia script. O
`MetricsStorageService` já cria a tabela automaticamente no arranque da JVM
(`@/javassist/.../MetricsStorageService.java:126`). Mantive sem script
dedicado para evitar duplicação.

### §9.2.2 — AutoScaler integrado com EC2 API

Implementação completa em `@/loadbalancer/.../AutoScaler.java`. Resumo:

- Usa `AmazonEC2ClientBuilder.standard().withRegion(...)` (SDK v1, já no `pom.xml`).
- `runInstances()` com AMI worker, SG, key pair, instance profile, tag `Project=NatureAtCloud,Role=worker`.
- `describeInstances()` em loop (até 2 min) para obter IP público.
- `terminateInstances()` em scale-down após drenagem de 30 s.
- **Scheduler 5 s**, **scale-up threshold 1.0**, **scale-down threshold 0.25** (calibrados após testar com workers reais — valores originais 15s/3.0/0.5 perdiam bursts curtos).
- **Cooldown de 60 s** entre acções para evitar oscilação.
- Cap **MIN=1**, **MAX=5** workers.
- **Degrada para "local mode"** quando `cnv.ami.id` ou `cnv.worker.sg.id` não estão definidos: só faz logs (utilíssimo para desenvolvimento no PC sem custos AWS).

**Configuração via system properties** (passadas pelo `05-launch-lb.sh`):

```
-Daws.region=eu-west-1
-Dcnv.ami.id=<ID da AMI worker, vem do 03-create-ami.sh>
-Dcnv.worker.sg.id=<SG do worker, vem do 02-setup-network.sh>
-Dcnv.keypair.name=cnv-keypair
-Dcnv.worker.instance.profile=CNV-Worker-Role
-Dcnv.instance.type=t3.micro
-Dcnv.worker.port=8000
```

Centralizadas em `@/loadbalancer/.../AwsConfig.java`.

### §9.2.3 — WorkerPool para workers dinâmicos

| Subtarefa | Estado |
|---|---|
| Registar/desregistar workers dinamicamente | ✅ `addWorker(host, port, instanceId)` + `removeWorker()` |
| Registar IDs de instância juntamente com host:porta | ✅ campo `Worker.instanceId` |
| Health checking periódico (remover não saudáveis) | ✅ `WorkerPool.startHealthChecks()` — scheduler de 15 s, remove worker após 3 falhas consecutivas, restaura contador em recuperação. Arrancado automaticamente em `LoadBalancer.start()`. |

---

## 2. Outros artefactos criados

| Ficheiro | Propósito |
|---|---|
| `@/loadbalancer/.../AwsConfig.java` | Config Java centralizada lida de system properties |
| `@/.gitignore` | Bloqueia `.pem`, `.aws/`, `credentials*`, `.state/`, etc. |
| `@/scripts/README.md` | Documentação de pré-requisitos, ordem de execução e validação |

Total: **8 ficheiros novos em `scripts/` + 1 classe Java + `.gitignore` actualizado**.

---

## 3. Fluxo end-to-end (como tudo se encaixa)

```
DEV (laptop):
  mvn clean package -DskipTests
        │
        └─► JARs em webserver/target/, javassist/target/, loadbalancer/target/
              │
SETUP (uma vez):
  01-setup-iam.sh ──► 3 IAM Roles + 2 Instance Profiles
  02-setup-network.sh ──► 2 SGs + cnv-keypair.pem
  03-create-ami.sh ──► AMI worker (Java + JARs + systemd)  [usa o keypair + worker SG]
        │
RUNTIME (cada sessão):
  04-launch-worker.sh ──► 1 worker EC2 [usa AMI + worker SG + Worker-Role]
        │                       │
        │                       └─► systemd inicia java -javaagent + WebServer:8000
        │                              │
        │                              └─► MetricsStorageService grava no DynamoDB
        │                                  (cria tabela automaticamente se não existir)
        │
  05-launch-lb.sh ──► 1 LB EC2 [usa LB SG + LoadBalancer-Role]
        │                  │
        │                  ├─► java LoadBalancer 8080 <worker_ip>:8000
        │                  ├─► ComplexityEstimator consulta DynamoDB
        │                  └─► AutoScaler usa runInstances/terminate (com Worker-Role)
        │
TESTE:
  curl http://<lb_ip>:8080/fractals?w=400&h=300&iterations=100 ──► imagem PNG
        │
CLEANUP:
  99-cleanup.sh        ──► termina EC2s
  99-cleanup.sh --deep ──► apaga SGs/IAM/KeyPair/AMI/snapshots/DynamoDB
```

---

## 4. Estado actual do código

| Verificação | Resultado |
|---|---|
| `mvn -pl loadbalancer -am package -DskipTests` | ✅ Compila e empacota |
| Testes unitários | ❌ Não existem (não estão no escopo do checkpoint) |
| Scripts testados em AWS real | ✅ **Validado** — conta `577267183760`, região `eu-west-1` |
| AutoScaler em modo AWS testado | ✅ **Validado** — scale-up duplo real (1→3 workers) com 20 pedidos `w=2000,h=2000,iter=2000` |
| ComplexityEstimator com histórico DynamoDB | ✅ **Validado** — `cost=738 (history)` após 24+ records |
| Health checks WorkerPool | ✅ **Validado** — `[WorkerPool] Health checks started` |
| Instance Profile entrega credenciais | ✅ **Validado** via STS + IMDSv2 |
| Escritas DynamoDB assíncronas | ✅ **Validado** — 24+ records em `cnv-metrics` |

---

## 5. Riscos conhecidos / a verificar quando testar

1. **Heredoc do systemd unit em `03-create-ami.sh`** — linhas 92-117. Funcionou na validação; se algum dia falhar, simplificar copiando o ficheiro para `/tmp` localmente e fazendo um único `scp`.
2. **Propagação IAM** — `01-setup-iam.sh` recomenda esperar ~10 s antes de lançar EC2. Se o `04-launch-worker.sh` rebentar com `InvalidIamInstanceProfile`, é só correr de novo.
3. **AMI base resolvida via SSM Parameter Store** — `ensure_base_ami()` em `aws-config.sh` consulta `/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64` em runtime e faz cache em `.state/base-ami-id.txt`. Para forçar uma AMI específica, `export BASE_AMI_ID=ami-xxx` antes de correr os scripts.
4. **Service Quota EC2** — para contas novas a quota default "Running On-Demand Standard instances" é frequentemente **5 vCPUs**. Como `t3.micro` = 2 vCPUs, isso dá só **~2 instâncias simultâneas**. Com `MAX_WORKERS=5` + LB no AutoScaler, vamos bater na quota se forçarmos a entregar até 5. **Acções:** (a) reduzir `MAX_WORKERS` para 2 enquanto se desenvolve, ou (b) pedir aumento de quota em EC2 → Limits (~24h de aprovação) **antes** da demo da entrega final.
5. **IMDSv2 obrigatório em AL2023** — a AMI base força IMDSv2. Comandos `curl http://169.254.169.254/...` sem token vêm vazios. Para testar credenciais dentro da EC2, usar `aws sts get-caller-identity` (CLI auto-detecta IMDSv2) ou pedir token PUT/GET (ver `04-testing-checklist.md` §Nível 7).
6. **`iam:PassRole` não está em `AmazonEC2FullAccess`** — quando o AutoScaler tenta lançar EC2 worker com `--iam-instance-profile`, AWS exige `iam:PassRole` para a role passada. Resolvido pelo `01-setup-iam.sh` que adiciona inline policy `CNV-AllowPassWorkerRole` ao `CNV-LoadBalancer-Role`. Sintoma: `[AutoScaler] Falha no SCALE UP: ... is not authorized to perform: iam:PassRole`.
7. **`RootHandler.sendResponseHeaders(200, 0)` corrompe health checks** — corrigido para `(200, -1)` em `@/webserver/.../RootHandler.java:31`. Sem este fix o curl ao `/` do worker dava timeout de 10s e o `WorkerPool.isHealthy()` removeria o worker do pool depois de 3 falhas consecutivas (45 s).
8. **Race condition na criação da tabela DynamoDB** — dois workers a arrancar em paralelo (scale-up) podiam ambos chamar `createTable()`. Corrigido em `@/javassist/.../MetricsStorageService.java:144-149` com `catch (ResourceInUseException)`.

---

## 6. O que vem a seguir

Ver `docs/01-...md` §9 para o roadmap completo. Em ordem de prioridade:

1. **Relatório intermédio 1 página + vídeo demo** — com a Fase 2 validada, podemos já escrever e gravar.
2. **Fase 4.1** — Routing baseado em complexidade no `LoadBalancer.ForwardHandler`. **Já temos `Estimate` calculado** — falta usá-lo na escolha do worker (preferir worker com menos `trabalho_restante_estimado`, não só menor `activeRequests`).
3. **Instrumentação real de BBs no `JavassistAgent`** — a heurística actual torna `basicBlockCount` flat para fractáis com loops intra-método (e.g. `JuliaFractal.generate`). Sem isto, o `ComplexityEstimator` ratio-based não discrimina pedidos pequenos de grandes; safe fallback heurístico salva o dia para já.
4. **Fase 3 (Lambda)** — só depois do checkpoint. Prioridade média.

> _Fase 2.3 (health checking periódico), Fase 4.3 (cache MSS), e Fase 2.2 (auto-scaling EC2) já estão implementadas e validadas — ver §1 desta tabela._
