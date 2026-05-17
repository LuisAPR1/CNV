# Nature@Cloud - Project Status & Roadmap

> **Last updated:** 2025-05-17  
> **Authors:** Luis Alexandre + a81430  
> **Course:** Cloud Computing and Virtualization (CNV) - IST 2025-26

---

## Table of Contents

1. [Project Overview](#1-project-overview)
2. [Required Architecture (from Assignment)](#2-required-architecture)
3. [Commit History Analysis](#3-commit-history-analysis)
4. [Current State - What We Have](#4-current-state---what-we-have)
5. [Critical Review & Issues](#5-critical-review--issues)
6. [What Is Missing](#6-what-is-missing)
7. [Checkpoint Requirements Checklist](#7-checkpoint-requirements-checklist)
8. [Final Submission Requirements Checklist](#8-final-submission-requirements-checklist)
9. [Suggested Implementation Plan](#9-suggested-implementation-plan)

---

## 1. Project Overview

The goal is to build **Nature@Cloud**, an elastic cloud service on AWS that executes 3 computationally-intensive workloads:

| Endpoint | Workload | Parameters |
|---|---|---|
| `/fractals` | Julia-set fractal generation | `w`, `h`, `iterations` |
| `/grayscott` | Gray-Scott reaction-diffusion | `size`, `maxIterations`, `f`, `k`, `stopOnExtinction`, `seedMode` |
| `/dna` | DNA FASTA sequence matching | `seq1`, `seq2`, `minLength`, `stopOnFirst` |

The system must **scale elastically** using both **EC2 VM workers** and **Lambda (FaaS) workers**, balanced by a **Load Balancer** that uses **complexity estimation** (from Javassist instrumentation metrics stored in **DynamoDB**) to decide where to route each request.

---

## 2. Required Architecture

As defined in `Project.txt`, the system has **4 main components**:

```
                       +-----------------+
                       |  Load Balancer  |   <-- single entry point (EC2 VM)
                       |  + Auto Scaler  |
                       +--------+--------+
                                |
          +---------------------+---------------------+
          |                     |                     |
   +------+------+     +-------+------+     +--------+-------+
   | VM Worker 1 |     | VM Worker N  |     | Lambda Worker  |
   | (EC2+agent) |     | (EC2+agent)  |     | (FaaS)         |
   +------+------+     +-------+------+     +--------+-------+
          |                     |                     |
          +---------------------+---------------------+
                                |
                     +----------+----------+
                     |    DynamoDB (MSS)   |
                     +---------------------+
```

### Component Responsibilities

- **Workers (EC2):** Run the webserver with Javassist instrumentation; collect metrics per request
- **Workers (Lambda):** Implement `RequestHandler` for each workload; instrumentation optional
- **Load Balancer (LB):** Entry point; receives all requests; routes to VM or Lambda based on complexity estimation; uses MSS data to make decisions; hides worker failures from users
- **Auto Scaler (AS):** Monitors worker load; scales EC2 instances up/down; runs co-located with LB
- **MSS (DynamoDB):** Stores request complexity metrics (basic blocks, method calls, etc.) for the LB to use in estimation

---

## 3. Commit History Analysis

### Commit 1: `38d465c` - Initial commit (professor's materials)

- **Date:** 2025-05-13
- **Author:** Luis Alexandre
- **Content:** The base project skeleton provided by the professor
  - `fractals/` module: `FractalsHandler` + `JuliaFractal`
  - `grayscott/` module: `GrayScottHandler` + `GrayScott`
  - `dna/` module: `DnaHandler` + `Dna` + `DnaHtmlRenderer`
  - `webserver/` module: `WebServer` + `RootHandler`
  - Root `pom.xml` (without javassist/loadbalancer modules)
  - All handlers already implement both `HttpHandler` (for EC2) and `RequestHandler` (for Lambda)

**Assessment:** This is the provided boilerplate. The workloads, handlers, and Lambda interfaces are professor-given code.

---

### Commit 2: `0a46066` - Javassist instrumentation (+294 lines)

- **Date:** 2025-05-13
- **Author:** Luis Alexandre
- **Files changed (6):**
  - `javassist/pom.xml` (new) - Maven module for the Java agent
  - `JavassistAgent.java` (new, 139 lines) - Class file transformer
  - `MetricRegistry.java` (new, 92 lines) - ThreadLocal metric collection
  - `MANIFEST.MF` (new) - Premain-Class declaration
  - `pom.xml` - Added `javassist` module
  - `webserver/pom.xml` - Added javassist dependency + uncommented MANIFEST reference

**What it does:**
- Instruments all classes in `pt.ulisboa.tecnico.cnv.{fractals,grayscott,dna}` at class-load time
- Wraps `handle(HttpExchange)` in each handler with `startRequest()`/`stopRequest()` calls
- Inserts `incrementMethodCalls()` at the beginning of every method
- Estimates basic blocks using heuristic: `bytecodeLength / 15`
- Stores metrics per-thread using `ThreadLocal<RequestMetrics>`
- Logs completed metrics to stdout and stores in a `ConcurrentHashMap`

**Assessment:**
- **Good:** Agent loads correctly, metrics are thread-safe, covers all 3 workload packages
- **Issue:** Basic block counting is a rough heuristic (bytecodeLength/15), not actual basic block analysis. This may be acceptable for a first pass but is not true basic block instrumentation.
- **Issue:** `completedMetrics` stores as `Map<String, String>` (requestId -> toString). This means structured metric data (method count, BB count, time) is lost - you only keep a formatted string. When DynamoDB integration comes, you'll need structured data.
- **Issue:** `completedMetrics` is never cleared and will grow unbounded in memory.
- **Issue:** Metrics are only collected locally in-memory; there is no mechanism to send them to the LB or to DynamoDB yet.

---

### Commit 3: `68d503c` - Load Balancer with retry (+441 lines)

- **Date:** 2025-05-14
- **Author:** a81430
- **Files changed (6):**
  - `loadbalancer/pom.xml` (new) - Maven module with `aws-java-sdk-ec2` dependency
  - `LoadBalancer.java` (new, 175 lines) - HTTP reverse proxy with retry
  - `WorkerPool.java` (new, 138 lines) - Worker management with round-robin and least-loaded selection
  - `AutoScaler.java` (new, 75 lines) - Skeleton auto scaler (logs only, no AWS calls)
  - `pom.xml` - Added `loadbalancer` module
  - `WebServer.java` - Made port configurable via CLI argument

**What it does:**
- Runs an HTTP server on port 8080 (default)
- Forwards `/fractals`, `/dna`, `/grayscott` to workers; returns a status page for other paths
- Uses **least-loaded** selection (by active request count) with retry on failure (tries up to 3 different workers)
- Tracks active request count per worker (increment/decrement around forwarding)
- Workers specified as CLI args: `java LoadBalancer 8080 host1:8001 host2:8002`
- AutoScaler runs in background every 10s, logs avg load, flags scale-up/down but does NOT actually scale

**Assessment:**
- **Good:** Clean separation of LB, WorkerPool, and AutoScaler; retry logic with exclusion of failed workers; least-loaded selection
- **Good:** Health-check method exists on Worker (though not yet used by AutoScaler)
- **Issue:** LB has **no complexity estimation** at all - routing is purely based on active request count, ignoring request parameters and estimated cost
- **Issue:** AutoScaler is purely a skeleton - only logs, never actually launches/terminates EC2 instances
- **Issue:** No integration with AWS SDK yet (dependency added but unused)
- **Issue:** No Lambda invocation support
- **Issue:** No DynamoDB integration
- **Issue:** LB does not read metrics from workers or from MSS
- **Issue:** LB creates a new `HttpClient` inside health checks (should reuse the static one from LoadBalancer)

---

## 4. Current State - What We Have

### Working locally

| Component | Status | Notes |
|---|---|---|
| **WebServer (workers)** | **Working** | Runs on configurable port, serves all 3 endpoints, multi-threaded (CachedThreadPool) |
| **Javassist Agent** | **Working** | Instruments at load time; logs method calls + estimated BBs + time per request to stdout |
| **MetricRegistry** | **Working** | Thread-local metrics, concurrent storage of completed metrics |
| **Load Balancer** | **Partially working** | Forwards requests, least-loaded routing, retry with exclusion |
| **AutoScaler** | **Skeleton only** | Logs avg load every 10s, thresholds defined but no action taken |
| **Lambda workers** | **Not started** | Handler interfaces exist (professor-provided) but no Lambda deployment |
| **DynamoDB (MSS)** | **Not started** | No code exists |
| **AWS deployment** | **Not started** | No scripts, no AMI, no security groups, no deployment automation |
| **Complexity estimation** | **Not started** | LB does not use request parameters to estimate cost |

### Project structure

```
CNV/
├── pom.xml                  (root POM, 6 modules)
├── Project.txt              (assignment statement)
├── README.md                (basic instructions)
├── docs/                    (this folder - for tracking progress)
├── scripts/                 (empty - should contain AWS automation)
├── javassist/               (Javassist agent module)
│   ├── pom.xml
│   └── src/.../javassist/
│       ├── JavassistAgent.java
│       └── MetricRegistry.java
├── webserver/               (worker web server module)
│   ├── pom.xml
│   └── src/.../webserver/
│       ├── WebServer.java
│       └── RootHandler.java
├── fractals/                (professor-provided workload)
├── grayscott/               (professor-provided workload)
├── dna/                     (professor-provided workload)
└── loadbalancer/            (LB + AS module)
    ├── pom.xml
    └── src/.../loadbalancer/
        ├── LoadBalancer.java
        ├── WorkerPool.java
        └── AutoScaler.java
```

---

## 5. Critical Review & Issues

### 5.1 Architecture Decisions - Are They Correct?

**Separation of LB and WebServer as separate modules:** **Good.** The assignment says the LB is a separate VM. Having them as separate Maven modules with separate JARs is the right approach.

**LB and AS co-located:** **Correct.** The assignment explicitly says "for simplicity, you can deploy both the AS and the LB code in the same VM."

**Javassist as a javaagent:** **Correct.** This is the standard approach - the agent instruments classes at load time on the worker VMs.

### 5.2 Issues That Need Fixing

#### A. Basic Block Counting is Inaccurate

The current approach estimates basic blocks as `bytecodeLength / 15`. This is a very rough heuristic. True basic blocks are delimited by branch instructions (if/else, loops, switch, try/catch). Javassist provides `MethodInfo.getCodeAttribute()` which gives access to the bytecode. You should analyze the bytecode to detect actual branch instructions (`goto`, `if*`, `tableswitch`, `lookupswitch`, exception handlers) and count the basic blocks between them.

**However**, the assignment also says: _"students should consider the usefulness/overhead trade-offs of each and all utilized metrics"_. So a heuristic can be acceptable **if you justify it in the report**. The key is that the metric should **correlate well** with actual request complexity. You should validate this by running requests with different parameters and checking if the metric values differ proportionally.

#### B. MetricRegistry Stores Strings Instead of Structured Data

`completedMetrics` is `ConcurrentHashMap<String, String>` mapping requestId to a `toString()` result. This makes it impossible to extract individual metric values programmatically. You need structured data for DynamoDB storage and for the LB to use.

**Fix:** Store `RequestMetrics` objects (or a DTO) with individual fields: `methodCallCount`, `basicBlockCount`, `elapsedTimeMs`, and the parsed request parameters.

#### C. No Mechanism to Send Metrics from Workers to LB/DynamoDB

Workers collect metrics locally but never expose them. The LB has no way to access them. You need one of:
1. Workers push metrics to DynamoDB after each request (recommended)
2. Workers expose a `/metrics` endpoint that the LB polls
3. Workers send metrics directly to the LB

Option 1 (workers push to DynamoDB) is the most architecturally clean and what the assignment implies.

#### D. LB Has No Complexity Estimation

The LB currently ignores request parameters. The assignment requires the LB to **estimate request complexity before forwarding** using:
- Request parameters (e.g., higher `iterations` = more work for fractals)
- Historical data from MSS (DynamoDB)

This is the **core intellectual challenge** of the project.

#### E. No AWS Infrastructure

No EC2, Lambda, DynamoDB, security groups, AMI creation, or deployment scripts exist.

#### F. No Lambda Integration

The LB needs the ability to invoke Lambda functions as an alternative to forwarding to EC2 workers. The assignment says to balance between EC2 (cheaper per request, slow startup) and Lambda (more expensive, fast start).

---

## 6. What Is Missing

### For Checkpoint Submission

| # | Requirement | Status |
|---|---|---|
| 1 | Multi-threaded VM workers | **DONE** |
| 2 | Javassist instrumentation gathering metrics | **DONE** (with caveats on BB accuracy) |
| 3 | Deploy on AWS EC2 (t3.micro) | **NOT DONE** |
| 4 | AWS-configured LB operating | **NOT DONE** (local LB exists but no AWS integration) |
| 5 | AWS-configured AS operating | **NOT DONE** (skeleton only, no EC2 API calls) |
| 6 | Initial LB/AS logic or pseudocode | **PARTIALLY DONE** (code exists but no complexity estimation) |
| 7 | DynamoDB MSS for metrics | **NOT DONE** |
| 8 | 1-page intermediate report | **NOT DONE** |
| 9 | Demo video | **NOT DONE** |

### For Final Submission (in addition to checkpoint)

| # | Requirement | Status |
|---|---|---|
| 1 | Instrumentation tool balancing overhead vs. precision | **NOT DONE** |
| 2 | Auto-scaling algorithm (cost + performance) | **NOT DONE** |
| 3 | Load-balancing algorithm using complexity estimates from MSS | **NOT DONE** |
| 4 | Lambda (FaaS) worker support | **NOT DONE** |
| 5 | EC2 + Lambda balancing (cost vs. latency) | **NOT DONE** |
| 6 | Full deployment automation | **NOT DONE** |
| 7 | Final report (up to 6 pages) | **NOT DONE** |
| 8 | Demo video | **NOT DONE** |

---

## 7. Checkpoint Requirements Checklist

- [x] Multi-threaded VM workers (`Executors.newCachedThreadPool()`)
- [x] Javassist agent loads and instruments workload classes
- [x] Metrics collected: method calls, estimated basic blocks, elapsed time
- [ ] Workers push metrics to DynamoDB after each request
- [ ] LB runs on AWS EC2 (t3.micro)
- [ ] Worker(s) run on AWS EC2 (t3.micro)
- [ ] AutoScaler can launch/terminate EC2 instances
- [ ] LB has initial complexity estimation logic (at least pseudocode)
- [ ] Deployment scripts/automation in `scripts/`
- [ ] 1-page intermediate report
- [ ] Demo video

---

## 8. Final Submission Requirements Checklist

Everything from Checkpoint, plus:

- [ ] Refined instrumentation (balance overhead vs. precision)
- [ ] DynamoDB MSS fully integrated (store + query metrics)
- [ ] LB estimates complexity from request parameters + MSS history
- [ ] LB routes to EC2 or Lambda based on complexity/load/cost
- [ ] Lambda deployment for all 3 workloads
- [ ] AS scales EC2 up/down based on real metrics
- [ ] Handles worker failures transparently (retry already exists)
- [ ] Deployment fully automated (create/destroy cloud resources)
- [ ] Final report (up to 6 double-column pages)
- [ ] Demo video against provided tests

---

## 9. Suggested Implementation Plan

### Phase 1: Fix Foundation & DynamoDB (Priority: HIGH)

**Goal:** Fix structural issues and add MSS so metrics flow end-to-end.

#### 1.1 Refactor MetricRegistry to store structured data

- Change `completedMetrics` from `Map<String, String>` to store `RequestMetrics` objects with parsed request parameters (workload type, individual params like `w`, `h`, `iterations`, etc.)
- Add a `RequestMetrics.toMap()` method returning `Map<String, AttributeValue>` for DynamoDB

#### 1.2 Add DynamoDB integration

- Add AWS SDK DynamoDB dependency to `javassist` module (or create a shared `common` module)
- Create `MetricsStorageService` class that:
  - Creates the DynamoDB table on startup (if not exists)
  - Writes a record per completed request: `{ requestType, params, methodCalls, basicBlocks, elapsedTimeMs, timestamp }`
- Call `MetricsStorageService.store()` from `MetricRegistry.stopRequest()`

#### 1.3 Add complexity estimator to LB

- Create `ComplexityEstimator` class in the `loadbalancer` module
- Queries DynamoDB for historical metrics of similar requests
- Estimates cost based on: request parameters → predicted basic block count
- Simple first approach: linear regression or lookup table from past requests
- If no history exists, use parameter-based heuristics (e.g., `w * h * iterations` for fractals)

### Phase 2: AWS Deployment (Priority: HIGH)

**Goal:** Get the system running on AWS.

#### 2.1 Create deployment scripts

In `scripts/`, create:
- `setup-security-group.sh` - Create SG allowing ports 8000, 8080, 22
- `create-ami.sh` - Launch a base EC2, install Java, copy JAR, create AMI
- `launch-worker.sh` - Launch a worker instance from AMI
- `launch-lb.sh` - Launch the LB/AS instance
- `cleanup.sh` - Terminate all instances, delete resources

#### 2.2 Integrate AutoScaler with EC2 API

- Use `aws-java-sdk-ec2` (already in pom.xml) to:
  - Launch new instances from AMI (`runInstances`)
  - Terminate idle instances (`terminateInstances`)
  - Describe instances to get IPs (`describeInstances`)
- When scaling up: launch instance, wait for running state, add to WorkerPool
- When scaling down: remove from WorkerPool, wait for drain, terminate

#### 2.3 Update WorkerPool for dynamic workers

- Add methods to register/deregister workers dynamically (partially exists)
- Add health checking on a timer (remove unhealthy workers)
- Track instance IDs alongside host:port

### Phase 3: Lambda Integration (Priority: MEDIUM)

**Goal:** Add FaaS workers as an alternative execution path.

#### 3.1 Deploy Lambda functions

- Create deployment package for each workload (the handlers already implement `RequestHandler`)
- Create a `deploy-lambdas.sh` script using AWS CLI
- One Lambda function per workload type, or a single function with routing

#### 3.2 Add Lambda invocation to LB

- Add `aws-java-sdk-lambda` dependency to `loadbalancer` module
- Create `LambdaInvoker` class that can invoke each workload's Lambda
- In `ForwardHandler`, decide: if complexity is low or workers are overloaded → use Lambda; otherwise → use EC2

### Phase 4: Smart Load Balancing & Auto-Scaling (Priority: HIGH for final)

**Goal:** Implement the core algorithms that differentiate a good project.

#### 4.1 Complexity-aware routing

- Before forwarding, estimate cost using `ComplexityEstimator`
- Route expensive requests to less-loaded workers
- Route to Lambda if: all workers are heavily loaded AND the estimated cost is below a threshold (Lambdas have a timeout limit)
- Track estimated remaining work per worker: `sum of estimated costs of active requests`

#### 4.2 Cost-aware auto-scaling

- Define a cost model: EC2 cost per hour vs. Lambda cost per invocation + duration
- Scale up when: `avg_estimated_remaining_work > threshold` AND adding a worker is cheaper than using Lambda for the pending requests
- Scale down when: workers are idle AND no requests are queued
- Implement a cool-down period to avoid oscillation
- Minimum 1 worker always running

#### 4.3 Caching MSS queries

- The assignment warns: _"continuously querying and exhaustively iterating this storage system is expensive and may also become a performance bottleneck for the LB"_
- Cache recent DynamoDB query results in-memory on the LB
- Refresh cache periodically (e.g., every 30s) or on-demand when a new request type is first seen

### Phase 5: Testing, Reports & Polish (Priority: HIGH at the end)

#### 5.1 Local testing

- Write a test script that sends concurrent requests with varying parameters
- Verify metrics are collected and stored in DynamoDB
- Verify LB distributes load reasonably
- Verify AS scales up under load and down when idle

#### 5.2 AWS end-to-end testing

- Deploy full system on AWS
- Run the professor-provided test suite
- Collect performance data: latency, throughput, cost
- Generate charts for the report

#### 5.3 Reports

- **Checkpoint report:** 1 page, describe what's implemented + pseudocode for LB/AS algorithms
- **Final report:** Up to 6 pages, describe architecture, algorithms, justify decisions with data

#### 5.4 Demo videos

- Record short videos showing the system handling requests, scaling up/down

---

## Appendix: Key Files Reference

| File | Purpose |
|---|---|
| `javassist/JavassistAgent.java` | Instruments workload classes at load time |
| `javassist/MetricRegistry.java` | Thread-local metric collection per request |
| `webserver/WebServer.java` | Worker HTTP server (runs on EC2 with `-javaagent`) |
| `loadbalancer/LoadBalancer.java` | LB entry point, forwards to workers |
| `loadbalancer/WorkerPool.java` | Worker pool management, selection strategies |
| `loadbalancer/AutoScaler.java` | (Skeleton) Monitors load, decides scale up/down |
| `fractals/FractalsHandler.java` | Fractal workload handler (professor-provided) |
| `grayscott/GrayScottHandler.java` | GrayScott workload handler (professor-provided) |
| `dna/DnaHandler.java` | DNA matcher handler (professor-provided) |

## Appendix: How to Run Locally

```bash
# Build everything
mvn clean package

# Start a worker on port 8000 (with Javassist agent)
cd webserver
java -javaagent:../javassist/target/javassist-agent-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
     -cp target/webserver-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
     pt.ulisboa.tecnico.cnv.webserver.WebServer 8000

# Start a second worker on port 8001 (optional)
java -javaagent:../javassist/target/javassist-agent-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
     -cp target/webserver-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
     pt.ulisboa.tecnico.cnv.webserver.WebServer 8001

# Start the Load Balancer on port 8080 pointing to both workers
cd ../loadbalancer
java -cp target/loadbalancer-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
     pt.ulisboa.tecnico.cnv.loadbalancer.LoadBalancer 8080 localhost:8000 localhost:8001

# Test requests via LB
curl "http://localhost:8080/fractals?w=400&h=300&iterations=100"
curl "http://localhost:8080/grayscott?size=128&maxIterations=1000"
curl "http://localhost:8080/dna?seq1=seq1:ATGCATGC&seq2=seq2:ATGCATGC&minLength=3&stopOnFirst=false"
```
