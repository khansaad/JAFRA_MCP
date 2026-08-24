# async-profiler-mcp-server

MCP server that exposes JFR profiling data from Kubernetes JVM pods to an LLM agent.

## What it does

1. **Exposes 9 MCP tools** so an agent can discover pods, trigger/monitor recordings, and retrieve JFR analysis from any pod in the cluster.
2. **Delegates analysis** to the [Jafra Analyzer](https://github.com/causa-ai-hub/jafra) backend via a clean REST API — the MCP server has zero knowledge of JFR internals.
3. **Live tools (5):** `list_profiled_pods`, `get_pod_jvm_status`, `get_jvm_statistics`, `get_recording_report`, `get_jfr_summary`.
4. **Stub tools (4):** `start_recording`, `stop_recording`, `get_recording`, `get_flame_graph` — defined and advertised today; will be wired to the jafra-controller once its recording API is finalised.

## Architecture

```
LLM Agent
    │  MCP (stdio / SSE / Streamable HTTP)
    ▼
async-profiler-mcp-server   ← this repo
    │  REST
    ▼
Jafra Analyzer   (GET /api/v1/namespaces/{ns}/pods/{pod}/containers/{c}/report)
    │            (GET /api/v1/namespaces/{ns}/pods/{pod}/containers/{c}/summary)
    ▼
Jafra Agent (DaemonSet — streams JFR chunks from every node)
    │
    └─ async-profiler sidecar injected by jafra-controller (MutatingWebhook)
```

## Documentation

- **[MCP Tool Contract Specification](MCP_TOOL_CONTRACT_SPECIFICATION.md)** — Formal spec for all 9 tools with schemas, error codes, and examples
- **[MCP Tool Quick Reference](MCP_TOOL_QUICK_REFERENCE.md)** — Quick lookup guide with workflow and polling strategy
- **[Quick Start Guide](QUICK_START.md)** — Local dev and Kind/Kubernetes deployment
- **[Integration Reference](INTEGRATION_REFERENCE.md)** — Image, environment variables, transport modes

## MCP Tools

| # | Tool                   | Status  | What it does                                                                    |
|---|------------------------|---------|---------------------------------------------------------------------------------|
| 1 | `list_profiled_pods`   | ✅ Live  | Discover pods with an async-profiler sidecar                                    |
| 2 | `get_pod_jvm_status`   | ✅ Live  | Profiler readiness + latest recording info                                      |
| 3 | `get_jvm_statistics`   | ✅ Live  | Live heap / thread / GC snapshot (no recording needed)                          |
| 4 | `start_recording`      | 🔧 Stub | Trigger a JFR recording — wired to jafra-controller when recording API is ready |
| 5 | `stop_recording`       | 🔧 Stub | Stop an in-progress recording early                                             |
| 6 | `get_recording`        | 🔧 Stub | Poll recording status: `QUEUED → RECORDING → ANALYSING → READY`                 |
| 7 | `get_recording_report` | ✅ Live  | JMC rule-based findings for a pod/time window (calls `/report`)                 |
| 8 | `get_jfr_summary`      | ✅ Live  | Compact LLM-ready JFR event summary (calls `/summary`)                          |
| 9 | `get_flame_graph`      | 🔧 Stub | Flame graph as SVG or JSON — wired to jafra-controller when ready               |

## Typical agent flow (current)

```
1. list_profiled_pods()                               → find pods being profiled
2. get_jfr_summary(ns, pod, container, last="5m")     → broad performance overview
   • scope       — recording window, file metadata
   • environment — JVM version, GC config, heap/container limits
   • signals     — CPU, GC pauses, heap, locks, allocations, threads, errors
3. get_recording_report(ns, pod, container, last="5m") → JMC rule findings
   • findings["VMOperations"] score=100  → long VM operations
   • findings["GC Pressure"]  score=84  → GC is a problem
   • findings["PID-1 Rule"]   score=75  → Java running as PID 1
4. Agent correlates findings → Root Cause Analysis
```

## Jafra Analyzer REST API (live endpoints)

```
GET /api/v1/namespaces/{ns}/pods/{pod}/containers/{container}/report?last=5m
    → JafraSummaryReport  (JMC rule-based findings keyed by rule ID)

GET /api/v1/namespaces/{ns}/pods/{pod}/containers/{container}/summary?last=5m
    → JafraSummaryDocument  (raw JFR event statistics, transformed to McpSummary)
```

All request/response types are defined in [`ProfilingModels.java`](src/main/java/io/causa/profiler/dto/ProfilingModels.java).

## Configuration

```properties
# src/main/resources/application.properties

# Jafra Analyzer base URL (or set JAFRA_ANALYZER_URL env var)
quarkus.rest-client.jafra-analyzer.url=${JAFRA_ANALYZER_URL:http://localhost:8080}

# Default Kubernetes namespace when callers omit it (or set PROFILER_DEFAULT_NAMESPACE)
profiler.controller.default-namespace=${PROFILER_DEFAULT_NAMESPACE:default}
```

## Building

```bash
./mvnw clean package -DskipTests
```

## Building the image

```bash
# linux/amd64 (for Kubernetes / OCP deployments)
podman build --platform linux/amd64 -t quay.io/khansaad/async-profiler-mcp-server:0.1.0 .
podman push quay.io/khansaad/async-profiler-mcp-server:0.1.0
```

## Testing

```bash
./mvnw test
```

## Project layout

```
async-profiler-mcp-server/
├── Dockerfile
├── smoke_test.py                              ← end-to-end MCP handshake smoke test
├── manifests/
│   ├── async-profiler-mcp-server-kind.yaml   ← Kind deployment (jafra-system namespace)
│   ├── async-profiler-mcp-server-openshift.yaml
│   └── async-profiler-mcp-server-minikube.yaml
└── src/
    ├── main/java/io/causa/profiler/
    │   ├── dto/
    │   │   └── ProfilingModels.java          ← all DTOs: ProfilingModels, JafraSummaryReport,
    │   │                                        JafraSummaryDocument, McpSummary, RecordingMetadata…
    │   ├── mcp/
    │   │   └── ProfilingTools.java           ← all 9 @Tool methods
    │   └── service/
    │       ├── ProfilerService.java          ← interface
    │       └── ProfilerServiceClient.java    ← Jafra Analyzer REST client +
    │                                            summary_to_mcp.py logic (Java port)
    └── test/java/io/causa/profiler/mcp/
        └── ProfilingToolsTest.java           ← 16 unit tests (all 9 tools, mocked service)
```
