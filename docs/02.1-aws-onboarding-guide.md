# Onboarding AWS — Nature@Cloud

> Documento para o **grupo todo**. Resume o que tem de existir na conta AWS
> para o projecto correr e quem fez/tem de fazer cada parte.
>
> Última actualização: 2025-05-17

---

## 0. Modelo de conta

- **1 conta AWS partilhada** pelo grupo (criada pela laura — delegada).
- **Vários IAM users** (1 por membro). Ninguém usa a conta root.
- **Região partilhada:** `eu-west-1` (definida em `scripts/aws-config.sh`).

---

## 1. Tarefas centralizadas (delegada — laura)

| Tarefa | Estado | Quem | Notas |
|---|---|---|---|
| Criar conta AWS | ✅ Feito | laura | — |
| MFA na root + lock-away das credenciais root | ✅ Feito | laura | — |
| Criar IAM user `laura` com `AdministratorAccess` | ✅ Feito | laura | — |
| Configurar Budget de alerta a 5 USD/mês | ✅ Feito | laura | — |
| Activar Free Tier usage alerts | ✅ Feito | laura | — |
| Criar 3 IAM Roles + 2 Instance Profiles (`01-setup-iam.sh`) | ✅ Feito | laura | `CNV-LoadBalancer-Role`, `CNV-Worker-Role`, `CNV-Lambda-ExecutionRole` |
| Criar IAM user `<colega1>` + Access Key | ⬜ A fazer | laura | Atribuir `AdministratorAccess` em desenvolvimento |
| Criar IAM user `<colega2>` + Access Key | ⬜ A fazer | laura | — |
| Pedir aumento de quota EC2 (vCPUs running) | ⚠️ Recomendado | laura | Default `5 vCPUs` → ~2 t3.micro. Pedir 20+ vCPUs em **EC2 → Limits → Running On-Demand Standard instances**. Demora ~24 h. **Fazer já se queremos demonstrar scale-up no checkpoint.** |
| Activar CloudTrail | ⬜ Opcional | laura | 1 trail grátis no Free Tier; útil para audit se algo correr mal. |

### Como criar IAM users para os colegas (laura)

```
Console AWS → IAM → Users → Create user
  Name: <username>
  ✅ Provide access to AWS Management Console
  Console password: Auto-generated (entregar por canal seguro)
  ✅ Users must create new password at next sign-in
  Permissions: Attach policies directly → AdministratorAccess
  → Create user
  → Security credentials → Create access key → "CLI"
       Guardar Access Key ID + Secret e enviar ao colega
       junto com o "Console sign-in URL" da conta.
```

---

## 2. Tarefas que cada membro tem de fazer (uma vez)

| Tarefa | laura | colega 1 | colega 2 |
|---|---|---|---|
| Receber credenciais IAM da delegada | ✅ | ⬜ | ⬜ |
| Login no Console + mudar password | ✅ | ⬜ | ⬜ |
| (Opcional) Activar MFA no IAM user | ⬜ | ⬜ | ⬜ |
| Instalar AWS CLI v2 | ✅ | ⬜ | ⬜ |
| `aws configure` (region `eu-west-1`, output `json`) | ✅ | ⬜ | ⬜ |
| `aws sts get-caller-identity` devolve o ARN do user | ✅ | ⬜ | ⬜ |
| Clonar repo + `mvn clean package -DskipTests` | ✅ | ⬜ | ⬜ |
| Correr `./02-setup-network.sh` (gera **o teu** `cnv-keypair.pem` local) | ✅ | ⬜ | ⬜ |

> **Nota:** os IAM Roles, SGs, e a tabela DynamoDB são partilhados pela
> conta — só são criados uma vez. Cada membro só precisa do seu próprio
> `.pem` para SSH.

### Instalação do AWS CLI v2

- **Windows:** descarregar de https://awscli.amazonaws.com/AWSCLIV2.msi e correr.
- **macOS:** `brew install awscli`
- **Linux:** ver https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html

Validar:
```bash
aws --version          # >= 2.x
aws sts get-caller-identity
```

---

## 3. Tarefas que NÃO precisam de ser feitas por todos

Estas são **únicas por conta** — depois de a laura as fazer, ficam para sempre:

- IAM Roles e Instance Profiles (`01-setup-iam.sh`)
- Tabela DynamoDB (criada automaticamente pelo `MetricsStorageService`; ou pelo primeiro membro a correr o LB)
- Security Groups (`02-setup-network.sh` — laura já correu, mas é idempotente; outros membros podem correr de novo, não cria duplicados)
- AMI worker (`03-create-ami.sh` — qualquer membro pode correr; partilhada por toda a conta)

---

## 4. Resumo do que está pronto agora (2025-05-17)

✅ **Pronto na AWS:**
- Conta + budget + MFA root + 1 IAM user (laura)
- 3 IAM Roles + 2 Instance Profiles

⏳ **Pronto no repo (código), por testar em AWS real:**
- 6 scripts em `scripts/*.sh` (IAM, network, AMI bake, launch worker, launch LB, cleanup)
- `AutoScaler` com EC2 SDK real
- `WorkerPool` com health checks periódicos
- `MetricsStorageService` (do colega) com auto-criação de tabela DynamoDB

⬜ **Por fazer na AWS:**
- IAM users para os 2 colegas
- (Opcional) pedido de aumento de quota EC2
- Validação end-to-end (ver `04-testing-checklist.md`)
