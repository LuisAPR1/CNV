# Nature@Cloud

> **CNV 2025/26** — Cloud Computing and Virtualization @ IST
>
> A self-managing cloud platform that runs compute-intensive scientific workloads (fractals, reaction-diffusion simulations, DNA alignment) with automatic scaling, bytecode-level instrumentation, and metric-driven load balancing.

## Architecture

```
Client ──► Load Balancer (Java) ──► Worker Pool (1..N EC2 instances)
                │                        │
                │  AutoScaler            │  Javassist Agent
                │  ComplexityEstimator   │  (bytecode instrumentation)
                │                        │
                └──── DynamoDB ──────────┘
                     (cnv-metrics)
```

| Component | Description |
|---|---|
| `webserver` | HTTP server exposing the 3 workloads via REST API |
| `fractals` | Julia-set fractal image generation |
| `grayscott` | Gray-Scott reaction-diffusion pattern simulation |
| `dna` | DNA genome sequence alignment |
| `javassist` | Bytecode instrumentation agent (ICount, method calls, basic blocks) |
| `loadbalancer` | Programmatic LB + AutoScaler + ComplexityEstimator |

## Prerequisites

- **Java 11+** (`JAVA_HOME` set)
- **Maven 3.9+**
- **AWS CLI** configured with credentials
- **AWS account** with EC2, DynamoDB, and IAM permissions

## Quick Start (Local)

```bash
# Build all modules
mvn clean package -DskipTests

# Run the web server locally (serves all 3 workloads)
java -cp webserver/target/webserver-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
    pt.ulisboa.tecnico.cnv.webserver.WebServer
```

The server starts on `http://localhost:8000`.

### Example requests

```bash
# Fractals — generates a PNG image
curl "http://localhost:8000/fractals?w=400&h=400&iterations=100" --output fractal.png

# Gray-Scott — reaction-diffusion simulation
curl "http://localhost:8000/grayscott?size=128&maxIterations=500&f=0.030&k=0.062&stopOnExtinction=false&seedMode=center"

# DNA — genome alignment
curl "http://localhost:8000/dna?seq1=seq1:ATGCATGCATGC&seq2=seq2:ATGCATGCATGC&minLength=3&stopOnFirst=false"
```

### With bytecode instrumentation

```bash
java -javaagent:javassist/target/javassist-agent-1.0.0-SNAPSHOT-jar-with-dependencies.jar=ICount:pt.ulisboa.tecnico.cnv.fractals,pt.ulisboa.tecnico.cnv.grayscott,pt.ulisboa.tecnico.cnv.dna \
    -cp webserver/target/webserver-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
    pt.ulisboa.tecnico.cnv.webserver.WebServer
```

## AWS Deployment

All scripts are in `scripts/`. They must be run **in order** from within WSL/Linux.

```bash
cd scripts

# 0. (Optional) Full teardown of previous deployments
echo "YES" | ./99-cleanup.sh --deep

# 1. Create IAM roles and instance profiles
./01-setup-iam.sh

# 2. Create key pair, security groups, and network rules
./02-setup-network.sh

# 3. Build AMI with Java 11 + JARs + systemd auto-start (~5 min)
./03-create-ami.sh

# 4. Launch a worker instance from the AMI
./04-launch-worker.sh

# 5. Launch the Load Balancer (pass worker IDs as arguments)
./05-launch-lb.sh $(cat .state/worker-instance-ids.txt)
```

After deployment, the LB status page is available at `http://<LB_PUBLIC_IP>:8080/`.

### Cleanup

```bash
# Terminate project EC2 instances only
./99-cleanup.sh

# Full teardown: instances + security groups + key pair + IAM roles + AMI + DynamoDB
echo "YES" | ./99-cleanup.sh --deep
```

## How It Works

### Complexity Estimation (2-tier)

1. **Ratio-based (history):** Queries DynamoDB for past requests of the same type, computes `avg(instructionCount / feature)`, and multiplies by the new request's feature. Activated automatically once historical data exists.

2. **Heuristic (fallback):** Uses calibrated parameter-based formulas when no history is available:
   - **Fractals:** piecewise multiplier based on iteration count (saturation at 500)
   - **Gray-Scott:** `size² × maxIterations × 164`
   - **DNA:** `max(seq1, seq2) × 125`

### AutoScaler

- **Scale-up:** When `avgEstWork > 5×10⁹`, launches a new worker from the AMI
- **Scale-down:** When `avgEstWork < 1.25×10⁹` for a sustained period, drains and terminates the least-loaded worker
- **Health checks:** Every 15s; workers failing 3 consecutive checks are removed

### Load Balancing

- Least-loaded worker selection based on `active + estWork`
- Request forwarding via `java.net.http.HttpClient`
- Worker discovery at startup via EC2 API (tag-based)

### Metrics (DynamoDB)

Each completed request stores:

| Field | Type | Description |
|---|---|---|
| `requestId` | String | Unique ID (timestamp + random) |
| `requestType` | String | `fractals`, `grayscott`, or `dna` |
| `instructionCount` | Number | Bytecode instructions executed |
| `methodCallCount` | Number | Method invocations |
| `elapsedTimeMs` | Number | Wall-clock execution time |
| `param_*` | String | Request parameters |

## Project Structure

```
.
├── pom.xml                  # Parent POM (reactor build)
├── javassist/               # Bytecode instrumentation agent
├── webserver/               # HTTP server + workload handlers
├── fractals/                # Julia-set fractal workload
├── grayscott/               # Gray-Scott simulation workload
├── dna/                     # DNA alignment workload
├── loadbalancer/            # Programmatic LB + AutoScaler
├── scripts/                 # AWS provisioning scripts
│   ├── aws-config.sh        # Shared configuration
│   ├── 01-setup-iam.sh      # IAM roles
│   ├── 02-setup-network.sh  # Key pair + security groups
│   ├── 03-create-ami.sh     # AMI build
│   ├── 04-launch-worker.sh  # Worker launch
│   ├── 05-launch-lb.sh      # LB launch
│   ├── 99-cleanup.sh        # Teardown
│   └── test/                # Benchmark & test scripts
└── docs/                    # Calibration evidence & reports
```

## Checkpoint Status

> **Note:** This project uses a **custom programmatic Load Balancer and AutoScaler**
> (Java-based) instead of AWS ELB + AutoScalingGroup. All scaling policies, load
> balancing algorithms, and metric storage are implemented in application code.

### AWS System Configuration

| Parameter | Value | Description |
|---|---|---|
| **Region** | `eu-west-1` (Ireland) | All resources in same region |
| **Instance type** | `t3.micro` | Worker and LB instances |
| **Base AMI** | Amazon Linux 2023 (latest) | Resolved via SSM at runtime |
| **Worker AMI** | Built by `03-create-ami.sh` | Java 11 + JARs + systemd auto-start |
| **Worker port** | `8000` | Web server listen port |
| **LB port** | `8080` | Public HTTP endpoint |

### AutoScaler Parameters (custom, not AWS ASG)

| Parameter | Value | Description |
|---|---|---|
| `ESTIMATED_WORK_THRESHOLD` | `5×10⁹` (5B) | Scale-up when `avgEstWork` exceeds this |
| `SCALE_DOWN_THRESHOLD` | `1.25×10⁹` (1.25B) | Scale-down when `avgEstWork` below this |
| `MAX_CAPACITY` | `5×10¹⁰` (50B) | Max estimated work per worker (packing) |
| Check interval | 5s | AutoScaler evaluation loop |
| Health check interval | 15s | Worker health probe |
| Health failures before removal | 3 | Consecutive failures to trigger removal |
| Scale-down cooldown | ~120s | Minimum time between scale-downs |

### Load Balancer Parameters (custom, not AWS ELB)

| Parameter | Description |
|---|---|
| Scheduling strategy | Least-loaded (min `active + estWork`) |
| Worker discovery | EC2 API tag-based at startup |
| Request forwarding | `java.net.http.HttpClient` |
| Retry on failure | Up to 2 retries to different workers |

### Metrics & Instrumentation

| Component | Status |
|---|---|
| Bytecode instrumentation | Javassist agent (ICount + method calls + basic blocks) |
| Metrics collected | `instructionCount`, `methodCallCount`, `elapsedTimeMs` |
| Storage | DynamoDB table `cnv-metrics` (beyond checkpoint: not just files) |
| Complexity estimation | 2-tier: ratio-based (history) + heuristic (fallback) |

## Group

CNV Group 35 — 2025/26
