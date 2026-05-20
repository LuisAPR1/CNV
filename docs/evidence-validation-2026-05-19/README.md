# Evidência da Sessão de Re-validação — 2026-05-19

Logs preservados da sessão de validação assistida, úteis como prova para o relatório intermédio e para o vídeo de demonstração.

## Ficheiros

| Ficheiro | Conteúdo |
|---|---|
| `scale-test.log` | Burst de 150s a `/fractals?w=2000&h=2000&iterations=2000`; mostra evolução do `wc -w` de workers: 2 → 3 (t=10s) → 4 (t=70s) → 5 (t=130s). |
| `resilience-test.log` | DynamoDB schema confirmado (item exemplo com 11 atributos); kill manual de 1 worker → LB devolve 3× HTTP 200 nos 50s seguintes; primeira observação de scale-down (lento). |
| `scaledown-watch.log` | Watcher passivo de 8 min sem carga; tail dos logs do LB via SSH; final state com 5 `[AutoScaler] SCALE DOWN ...` + `[AutoScaler] Instância i-XXX terminada` reais. |
| `logs-collected.log` | Dump categorizado do `/opt/cnv/lb.log` do LB: init, Added/Removed workers, ComplexityEstimator, AutoScaler, WorkerPool. Evidência das 467 amostras na DynamoDB e do `Cache atualizado para 'fractals': 50 registos`. |

## Linhas-chave (para citar no relatório)

```
[LoadBalancer] AWS mode: pool inicial vazio. AutoScaler vai provisionar...   ← Fix-3 (sem fantasma localhost:8000)
[WorkerPool] Added worker: 34.249.92.230:8000 [i-03d229cff645240f8] (active=0)
[WorkerPool] Health check FAILED (3/3): 108.129.243.41:8000 [i-0f3043e7350165694]
[WorkerPool] Removing unhealthy worker: 108.129.243.41:8000 [i-0f3043e7350165694]
[AutoScaler] SCALE DOWN (avgLoad=0.0 < 0.25)
[AutoScaler] A drenar e terminar worker: 108.130.108.37:8000 [i-04090a7ddb597e001] (active=0)
[AutoScaler] Instância i-04090a7ddb597e001 terminada.
[ComplexityEstimator] Cache atualizado para 'fractals': 50 registos
```

## Conta + região

- AWS account: `577267183760` (IAM user `cnv-admin-laura`)
- Região: `eu-west-1` (Irlanda)
- Custo total da sessão (3 EC2 t3.micro × ~3h + DynamoDB pay-per-request): **~€0.05**
