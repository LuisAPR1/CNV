# Testing Checklist — Validação end-to-end

> Guião passo-a-passo para validar que **tudo funciona em AWS real**.
> Cada passo tem o comando exacto e o que tens de ver no output para
> considerar OK.
>
> Estimativa de tempo: ~30 min na primeira vez (incluindo `mvn package`
> e `03-create-ami.sh` que demora ~5 min). ~5 min em corridas seguintes.

---

## Pré-requisitos antes de começares

Tem de estar tudo isto verdadeiro:

- [ ] AWS CLI v2 instalado (`aws --version` → 2.x)
- [ ] `aws configure` feito com IAM user (não root)
- [ ] Estás no Git Bash (Windows) / terminal (Mac/Linux)
- [ ] Estás na pasta raiz do projecto

---

## Nível 0 — Sanidade local (1 min)

### 0.1 Build dos JARs

```bash
mvn clean package -DskipTests
```

**Esperado:** `BUILD SUCCESS` no fim. Sem erros.

```bash
ls webserver/target/*-jar-with-dependencies.jar
ls javassist/target/*-jar-with-dependencies.jar
ls loadbalancer/target/*-jar-with-dependencies.jar
```

**Esperado:** os 3 ficheiros existem.

---

## Nível 1 — Credenciais AWS (10 s)

```bash
aws sts get-caller-identity
```

**Esperado:**
```json
{
    "UserId": "AIDAxxxxxxxxxx",
    "Account": "123456789012",
    "Arn": "arn:aws:iam::123456789012:user/laura"
}
```

**❌ Se falhar:** `aws configure` outra vez. Verifica que copiaste a
Secret Access Key correctamente.

---

## Nível 2 — Permissões EC2 (10 s)

```bash
aws ec2 describe-regions --region eu-west-1
```

**Esperado:** lista de ~20 regiões em JSON. Sem `AccessDenied`.

---

## Nível 3 — IAM Roles (já criadas pela laura)

```bash
aws iam get-role --role-name CNV-Worker-Role --query "Role.Arn" --output text
aws iam get-role --role-name CNV-LoadBalancer-Role --query "Role.Arn" --output text
aws iam get-role --role-name CNV-Lambda-ExecutionRole --query "Role.Arn" --output text
aws iam get-instance-profile --instance-profile-name CNV-Worker-Role --query "InstanceProfile.Arn" --output text
aws iam get-instance-profile --instance-profile-name CNV-LoadBalancer-Role --query "InstanceProfile.Arn" --output text
```

**Esperado:** 5 ARNs impressos, sem erros.

**❌ Se algum falhar:** corre `./scripts/01-setup-iam.sh`.

---

## Nível 4 — Network setup (~30 s)

```bash
cd scripts
./02-setup-network.sh
```

**Esperado** (entre outras linhas):
```
[OK]      Key pair criada. Ficheiro: .../scripts/cnv-keypair.pem
[OK]      SG 'cnv-worker-sg' criado: sg-0xxxxxxxxxx
[OK]      SG 'cnv-lb-sg' criado: sg-0xxxxxxxxxx
[OK]        ingress tcp/22 from <teu_ip>/32
[OK]        ingress tcp/8000 from SG sg-0xxxxxxxxxx
[OK]        ingress tcp/8080 from 0.0.0.0/0
[OK]      Network concluído.
```

**Verificar:**
```bash
ls -la cnv-keypair.pem    # tem de existir, ~1.7 KB
cat .state/worker-sg-id.txt    # tem de ter um sg-xxxx
cat .state/lb-sg-id.txt        # tem de ter um sg-xxxx
```

---

## Nível 5 — Bake da AMI worker (~5 min)

```bash
./03-create-ami.sh
```

**O que acontece (segue o output):**
1. Lança builder EC2 (~30 s) — vês `Builder lançado: i-0xxxxx`
2. Espera SSH (~30 s) — vês `[OK] SSH responde.`
3. Instala Java + copia JARs + instala systemd (~30 s)
4. Cria AMI + espera ACTIVE (**2-4 min — paciência**)
5. Termina builder (~30 s)

**Esperado no fim:**
```
[OK]      AMI worker pronta!
   AMI ID  : ami-0xxxxxxxxxx
   Ficheiro: .../scripts/.state/worker-ami-id.txt
```

**Validar:**
```bash
cat .state/worker-ami-id.txt    # tem de ter um ami-xxxxxxxx
aws ec2 describe-images --image-ids $(cat .state/worker-ami-id.txt) \
    --region eu-west-1 --query "Images[0].State" --output text
# Esperado: available
```

**❌ Falhas mais prováveis:**
- Timeout SSH: aumentar o loop em `03-create-ami.sh:53` de 30 para 60.
- `dnf` falha: a região não tem Amazon Linux 2023 — verificar `BASE_AMI_ID`.
- Heredoc errado no remoto: ver `lb-bootstrap.log` na builder antes dela ser terminada (precisas de SSH manual rápido).

---

## Nível 6 — Lançar 1 worker e testá-lo directamente (~1 min)

```bash
./04-launch-worker.sh
```

**Esperado:**
```
[OK]     Worker UP
   Instance ID : i-0xxxxx
   Public IP   : 54.x.x.x
   Endpoint    : http://54.x.x.x:8000
```

**Aguardar ~30 s** para o systemd arrancar o worker e testar:
```bash
WORKER_IP=54.x.x.x   # substitui pelo IP do output

# Endpoint de status (devolve HTTP 200 sem body)
curl -i --max-time 5 "http://$WORKER_IP:8000/"
# Esperado: "HTTP/1.1 200 OK" + sem body

# Endpoint real — fractal pequeno (~5s no t3.micro)
curl --max-time 60 "http://$WORKER_IP:8000/fractals?w=200&h=200&iterations=100" \
     -o fractal.b64 -w "HTTP %{http_code} | %{size_download} bytes\n"
# Esperado: HTTP 200 | ~27000 bytes

# Nota: o FractalsHandler devolve "data:image/png;base64,<...>", não bytes PNG.
# Para visualizar a imagem:
sed 's/^data:image\/png;base64,//' fractal.b64 | base64 -d > fractal.png
file fractal.png   # deve dizer: PNG image data, 200 x 200
```

**❌ Se o curl der `Connection timed out`:**
O Security Group está a bloquear o teu IP. O `02-setup-network.sh` autoriza só o IP que tinhas na altura — se mudou (Wi-Fi rodou, mudaste de rede), re-corre:
```bash
./02-setup-network.sh   # idempotente: detecta o novo IP e adiciona regra
```

**Se quiseres ver os logs do worker (debug):**
```bash
ssh -i cnv-keypair.pem -o StrictHostKeyChecking=no ec2-user@$WORKER_IP \
    "sudo systemctl status cnv-worker.service --no-pager; sudo tail -50 /var/log/cnv-worker.log"
```

---

## Nível 7 — Validar Instance Profile dentro da EC2 (~30 s)

A AMI base (Amazon Linux 2023) tem **IMDSv2 obrigatório** — chamadas sem token devolvem string vazia. Usa o AWS CLI da própria EC2 que trata disso automaticamente:

```bash
# Teste rápido via AWS STS (mais limpo)
ssh -i cnv-keypair.pem -o StrictHostKeyChecking=no ec2-user@$WORKER_IP \
    "aws sts get-caller-identity --region eu-west-1"
# Esperado: JSON com "Arn": "arn:aws:sts::ACCOUNT:assumed-role/CNV-Worker-Role/i-..."
```

**Esperado:** O `Arn` deve terminar em `assumed-role/CNV-Worker-Role/i-xxx`.
Isto prova que o **Instance Profile está a entregar credenciais
temporárias** — o `MetricsStorageService` lá dentro pode escrever no
DynamoDB sem qualquer ficheiro de credenciais configurado.

**Se quiseres ver o IMDSv2 directo:**
```bash
ssh -i cnv-keypair.pem -o StrictHostKeyChecking=no ec2-user@$WORKER_IP \
    'TOKEN=$(curl -s -X PUT "http://169.254.169.254/latest/api/token" -H "X-aws-ec2-metadata-token-ttl-seconds: 21600"); curl -s -H "X-aws-ec2-metadata-token: $TOKEN" "http://169.254.169.254/latest/meta-data/iam/security-credentials/"'
# Esperado: "CNV-Worker-Role"
```

---

## Nível 8 — Verificar que o worker escreveu no DynamoDB (~10 s)

```bash
aws dynamodb scan --table-name cnv-metrics --region eu-west-1 \
    --select COUNT --query "Count"
```

**Esperado:** número >= 1 (cada pedido fractals do passo 6 grava 1 linha).

Para ver o conteúdo (limitar a 5):
```bash
aws dynamodb scan --table-name cnv-metrics --region eu-west-1 --max-items 5
```

**Esperado:** items com campos `requestType`, `requestId`,
`basicBlockCount`, `methodCallCount`, `params`, etc.

---

## Nível 9 — Lançar o LB e fazer um pedido end-to-end (~2 min)

Apanha o instance ID do worker do passo 6 (está em `.state/worker-instance-ids.txt`):
```bash
WORKER_ID=$(cat .state/worker-instance-ids.txt | head -1)
./05-launch-lb.sh $WORKER_ID
```

**Esperado:**
```
[OK]     Load Balancer UP
   Instance ID : i-0xxxxx
   Public IP   : 18.x.x.x
   Endpoint    : http://18.x.x.x:8080
```

Aguarda ~30 s para o LB arrancar e testa:
```bash
LB_IP=18.x.x.x
# Nota: a resposta e uma data URL ("data:image/png;base64,<...>"), NAO bytes PNG.
# Decodifica antes de gravar:
curl -s "http://$LB_IP:8080/fractals?w=400&h=400&iterations=200" \
    | sed 's/^data:image\/png;base64,//' | base64 -d > fractal2.png

file fractal2.png
# Esperado: fractal2.png: PNG image data, 400 x 400, ...
```

**Para ver os logs do LB (incluindo decisões do AutoScaler e ComplexityEstimator):**
```bash
ssh -i cnv-keypair.pem -o StrictHostKeyChecking=no ec2-user@$LB_IP \
    "tail -100 /opt/cnv/lb.log"
```

**Esperado nos logs:**
- `[LoadBalancer] Listening on port 8080`
- `[AutoScaler] AWS scaling ACTIVO. AwsConfig{...scalingEnabled=true}`
- `[WorkerPool] Health checks started`
- `[ComplexityEstimator] ...` (estimativa para o teu pedido)
- Periodicamente: `[AutoScaler] Workers=1, TotalActive=0, AvgLoad=0,00`
- Periodicamente: `[WorkerPool] Health check FAILED` ou silêncio (= tudo bem)

---

## Nível 10 — Provocar um SCALE UP (opcional, ~3 min)

Para ver o AutoScaler a lançar uma EC2 nova, manda **muitos pedidos
concorrentes**:

```bash
# 20 pedidos simultaneos (stress test - descartamos o output):
for i in {1..20}; do
    curl -s -o /dev/null "http://$LB_IP:8080/fractals?w=800&h=800&iterations=500" &
done
wait
```

Nos logs do LB, **deves ver** (~1 min depois):
```
[AutoScaler] Workers=1, TotalActive=15, AvgLoad=15,00
[AutoScaler] SCALE UP (avgLoad=15.0 > 3.0)
[AutoScaler] Instância lançada: i-0yyyyy — a aguardar IP...
[AutoScaler] Worker i-0yyyyy @ 54.y.y.y adicionado ao pool.
```

**Para confirmar na AWS:**
```bash
aws ec2 describe-instances \
    --filters "Name=tag:Project,Values=NatureAtCloud" "Name=instance-state-name,Values=running" \
    --region eu-west-1 \
    --query "Reservations[].Instances[].[InstanceId,Tags[?Key=='Role']|[0].Value,PublicIpAddress]" \
    --output table
```

**Esperado:** 1 LB + 2 workers em vez de 1.

---

## Nível 11 — Health check em acção (opcional, ~2 min)

Mata o worker original manualmente para ver o health check funcionar:
```bash
aws ec2 terminate-instances --instance-ids $WORKER_ID --region eu-west-1
```

Espera ~45-60 segundos e olha para os logs do LB:
```bash
ssh -i cnv-keypair.pem -o StrictHostKeyChecking=no ec2-user@$LB_IP \
    "tail -30 /opt/cnv/lb.log"
```

**Esperado:**
```
[WorkerPool] Health check FAILED (1/3): 54.x.x.x:8000 [i-0xxxxx] (active=0)
[WorkerPool] Health check FAILED (2/3): 54.x.x.x:8000 [i-0xxxxx] (active=0)
[WorkerPool] Health check FAILED (3/3): 54.x.x.x:8000 [i-0xxxxx] (active=0)
[WorkerPool] Removing unhealthy worker: 54.x.x.x:8000 [i-0xxxxx] (active=0)
[WorkerPool] Removed worker: 54.x.x.x:8000 [i-0xxxxx] (active=0)
```

E provavelmente em seguida:
```
[AutoScaler] Abaixo do mínimo (1) — SCALE UP forçado.
[AutoScaler] Instância lançada: i-0zzzzz ...
```

---

## Cleanup obrigatório no fim

```bash
./99-cleanup.sh
```

**Esperado:** todas as EC2s tagged `Project=NatureAtCloud` terminadas.
SGs / IAM / AMI / DynamoDB **preservados** (idempotente para próxima sessão).

```bash
aws ec2 describe-instances \
    --filters "Name=tag:Project,Values=NatureAtCloud" "Name=instance-state-name,Values=running" \
    --region eu-west-1 \
    --query "Reservations[].Instances[].InstanceId" --output text
```
**Esperado:** vazio.

> ⚠️ **Importante:** sem este passo no fim do dia, o Free Tier evapora-se
> e podes apanhar uma surpresa na fatura.

---

## Critério de "tudo a 100%"

Para marcar a Fase 2 como **DONE**:
- [ ] Níveis 0–9 todos passam sem erro.
- [ ] Nível 10 (SCALE UP) opcional mas recomendado para demonstração.
- [ ] Nível 11 (health check) opcional.
- [ ] Cleanup confirma 0 instâncias a correr.

Se algum nível falhar, regista o output exacto e o que correu mal — útil
para debug e para o relatório.
