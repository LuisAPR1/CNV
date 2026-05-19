# Nature@Cloud - Estado do Projeto & Roteiro (ARQUIVO — Fase 1-2)

> **ARQUIVADO em 2025-05-19** — esta é a versão histórica do roadmap usada durante as Fases 1 e 2 (instrumentação + deploy AWS). Foi substituída por uma nova versão consolidada em `docs/01-project-status-and-roadmap_pt.md` após auditoria pós-validação da Fase 2.

---

> **Última atualização:** 2025-05-17 (sessão noite — integração AWS)
> **Autores:** Luis Alexandre + a81430 + laura
> **Curso:** Computação e Virtualização na Nuvem (CNV) - IST 2025-26

## Resumo desta sessão (laura)

- Commits 1.1–1.3 do colega merged: `MetricRegistry` estruturado, `MetricsStorageService` (DynamoDB async), `ComplexityEstimator` no LB.
- **AWS account model decidido:** conta própria do grupo, **NÃO Learner Lab**. Cada membro vai ter IAM user próprio.
- **Infra-as-code criado:** pasta `scripts/` com 6 scripts bash (`.sh`, portáveis Linux/Mac/Git Bash) — IAM, network, AMI worker, launch worker, launch LB, cleanup. Seguem a estrutura do §9.2.1 deste documento.
- **`.gitignore` reforçado** para impedir leak de chaves `.pem` e credenciais.
- **`AutoScaler` reimplementado** com chamadas reais ao EC2 SDK (`runInstances`, `terminateInstances`, `describeInstances`), drenagem em scale-down, cooldown de 60s, cap de 5 workers. Degrada graciosamente para modo local-only quando `cnv.ami.id`/`cnv.worker.sg.id` não estão definidos.
- **`AwsConfig`** centraliza configuração lida de system properties.
- **`WorkerPool`** ganha: (a) `Worker.instanceId` opcional, (b) **health checks periódicos** a cada 15 s com remoção após 3 falhas consecutivas — fecha a Fase 2.3.
- **Tabela DynamoDB:** criação automática pelo `MetricsStorageService` (sem script dedicado).

Documentos novos:
- `docs/02-fase2-aws-deployment.md` — detalhe da Fase 2 (este ficheiro).
- `docs/03-aws-onboarding-guide.md` — checklist de tarefas AWS por membro do grupo.
- `docs/04-testing-checklist.md` — guião de validação end-to-end (níveis 0–11).

**Status global:** Fase 2 do roadmap (AWS Deployment) **fechada no código**. Falta validação em AWS real seguindo o `04-testing-checklist.md`.

---

> **NOTA DE ARQUIVAMENTO:** O resto deste documento (índice, análise de commits, plano de implementação detalhado das fases 1-2, apêndices de setup) foi preservado integralmente no git history. Para consultar a versão completa antes do arquivamento, ver:
>
> ```
> git show 98b58d7:docs/01-project-status-and-roadmap_pt.md
> ```
>
> A nova versão (em `docs/01-project-status-and-roadmap_pt.md`) consolida o estado pós-validação, integra a auditoria de 19 Mai, e adiciona roadmap detalhado das Fases 3 e 4. Os pontos da Fase 1 (FIX_01..03) estão sumarizados no Apêndice C da nova versão.
