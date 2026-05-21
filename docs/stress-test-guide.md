# Stress Test Guide — Nature@Cloud

> Guia prático para validar scale-up, scale-down e DynamoDB.
> Testado com sucesso em 2026-05-21 (1→3 workers).

## Pré-requisitos

- Sistema deployed: `01-setup-iam.sh` → `02-setup-network.sh` → `03-create-ami.sh` → `04-launch-worker.sh` → `05-launch-lb.sh`
- 2 terminais WSL abertos em `~/Desktop/CNV/scripts/`

---

## Terminal 1 — Monitor de EC2

```bash
watch -n 10 'aws ec2 describe-instances --region eu-west-1 \
  --filters "Name=tag:Project,Values=NatureAtCloud" \
  --query "Reservations[].Instances[].[InstanceId,State.Name,Tags[?Key==\`"Role\`"].Value|[0]]" \
  --output table'
```

---

## Terminal 2 — Stress Test

### 1. Verificar DynamoDB antes

```bash
aws dynamodb scan --table-name cnv-metrics --region eu-west-1 --select COUNT
```

### 2. Disparar carga pesada (2 minutos)

```bash
LB_IP=$(cat .state/lb-ip.txt)

END=$(($(date +%s) + 120))
while [ $(date +%s) -lt $END ]; do
    for i in $(seq 1 5); do
        curl -s -o /dev/null "http://$LB_IP:8080/fractals?w=2000&h=2000&iterations=2000" &
    done
    sleep 2
done
wait
echo "Carga terminada!"
```

### 3. Confirmar scale-up

No Terminal 1 deves ver workers `pending` → `running` a aparecer (~60s após início).

### 4. Aguardar scale-down (~3 min sem carga)

No Terminal 1, workers devem desaparecer até restar 1.

### 5. Verificar logs do LB

```bash
LB_IP=$(cat .state/lb-ip.txt)
ssh -i cnv-keypair.pem -o StrictHostKeyChecking=no ec2-user@$LB_IP \
  "grep -E 'SCALE UP|SCALE DOWN|Instância.*terminada|discoverExistingWorkers' /opt/cnv/lb.log"
```

**Esperado:**
```
[AutoScaler] SCALE UP (avgLoad=X.X > 1.0)
[AutoScaler] Instância lançada: i-XXXX — a aguardar IP...
[AutoScaler] SCALE DOWN (avgLoad=0.0 < 0.25)
[AutoScaler] Instância i-XXXX terminada.
```

### 6. Verificar DynamoDB depois

```bash
aws dynamodb scan --table-name cnv-metrics --region eu-west-1 --select COUNT
```

O `Count` deve ter aumentado significativamente.

---

## Evidências para o relatório

- Screenshot do `watch` com 3+ workers
- Output do `grep` com SCALE UP / SCALE DOWN
- Output do DynamoDB scan (Count antes vs depois)
