# Scripts de provisionamento AWS — Nature@Cloud

Scripts **bash** (`.sh`) que automatizam todo o ciclo de vida da infra AWS
do projecto. Portáveis: corre em Linux, macOS, ou Windows (Git Bash / WSL).

Os 5 scripts numerados seguem exactamente o que está no roadmap §9.2.1:
`setup-security-group` → `02-setup-network.sh`, `create-ami` →
`03-create-ami.sh`, `launch-worker` → `04`, `launch-lb` → `05`,
`cleanup` → `99`. O `01-setup-iam.sh` é adicional (necessário para os
`--iam-instance-profile` dos restantes scripts funcionarem).

---

## Pré-requisitos

1. **AWS CLI v2** → `aws --version`
2. **Credenciais configuradas** → `aws configure` (IAM user, nunca root)
3. **bash + curl + ssh + scp** (em Windows: Git Bash já tem)
4. **Java 11 + Maven** para fazer `mvn package` antes da AMI

Validar:
```bash
aws sts get-caller-identity
```

---

## Ordem de execução (primeira vez, ~10 minutos)

```bash
chmod +x scripts/*.sh   # Linux/Mac apenas

cd scripts

./01-setup-iam.sh        # 3 IAM Roles + 2 Instance Profiles
./02-setup-network.sh    # 2 Security Groups + SSH key pair

# Build dos JARs (necessário antes da AMI)
cd .. && mvn clean package -DskipTests && cd scripts

./03-create-ami.sh       # Cria AMI worker (~5 min: lança VM, instala Java, copia JAR, snapshot, termina VM)
```

A tabela DynamoDB **não tem script próprio** — é criada automaticamente
pelo `MetricsStorageService` no arranque da aplicação Java.

---

## Workflow de cada sessão de trabalho

```bash
cd scripts

./04-launch-worker.sh    # Lança 1 worker a partir da AMI (auto-start via systemd)
./05-launch-lb.sh        # Lança o LB e fá-lo apontar para esta AMI no AutoScaler

# ... testes com curl ...

./99-cleanup.sh          # Termina TODAS as EC2s do projecto (preserva infra)
```

No fim do projecto:
```bash
./99-cleanup.sh --deep   # Apaga TUDO (pede confirmação "YES")
```

---

## Ficheiros gerados (não vão para git)

- `cnv-keypair.pem` — chave SSH privada (`.gitignore` bloqueia)
- `.state/*.txt` — IDs dos recursos criados (usados por outros scripts e pelo cleanup)

---

## Configuração

Tudo em `aws-config.sh`. Para mudar de região:

```bash
export AWS_REGION=us-east-1
# Também tens de actualizar BASE_AMI_ID — descobrir o AMI Amazon Linux 2023
# na nova região com:
aws ec2 describe-images --owners amazon \
    --filters "Name=name,Values=al2023-ami-2023.*-x86_64" \
    --query "sort_by(Images,&CreationDate)[-1].ImageId" \
    --output text --region us-east-1
```

---

## Validação (Níveis 1–7)

| # | Comando | Prova |
|---|---|---|
| 1 | `aws sts get-caller-identity` | Credenciais OK |
| 2 | `aws ec2 describe-regions` | Permissões EC2 |
| 3 | (programa Java de teste) | SDK Java lê credenciais |
| 4 | `aws dynamodb list-tables` | DynamoDB acessível |
| 5 | `./04-launch-worker.sh` + `curl` | EC2 + SG + key pair |
| 6 | dentro EC2: `aws sts get-caller-identity --region eu-west-1` (Arn deve terminar em `assumed-role/CNV-Worker-Role/i-...`) | Instance Profile entrega credenciais (IMDSv2) |
| 7 | LB + worker + `aws dynamodb scan --table-name cnv-metrics` | End-to-end |
