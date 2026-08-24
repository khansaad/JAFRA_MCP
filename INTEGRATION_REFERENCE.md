# Async Profiler MCP Server — Integration Reference

---

## Server Details

| Property | Value |
|---|---|
| **Image** | `quay.io/khansaad/async-profiler-mcp-server:0.1.0` |
| **Architecture** | `linux/amd64` |
| **Base** | `eclipse-temurin:21-jre` |
| **Transports** | Streamable HTTP (`POST /mcp`) · SSE (`GET /mcp/sse`) · stdio |
| **Port** | `8080` |
| **Java** | 21 |
| **Framework** | Quarkus 3.20 + quarkus-mcp-server |

---

## Transport Modes

| Mode | Endpoint | When to use |
|---|---|---|
| **Streamable HTTP** | `POST /mcp` | Default for network deployment (MCP spec §6.3) |
| **SSE** | `GET /mcp/sse` | Older MCP clients using Server-Sent Events |
| **stdio** | stdin / stdout | AI agent spawns the server as a child process |

The MCP handshake requires three steps:

```
1. POST /mcp  {"method":"initialize",...}          → receive Mcp-Session-Id header
2. POST /mcp  {"method":"notifications/initialized"} Accept: application/json, text/event-stream
3. POST /mcp  {"method":"tools/list",...}           with Mcp-Session-Id header
```

---

## Health Probes

```
GET /q/health/ready   → {"status":"UP"}
GET /q/health/live    → {"status":"UP"}
```

Kubernetes probe config (already in `manifests/async-profiler-mcp-server-kind.yaml`):

```yaml
readinessProbe:
  httpGet:
    path: /q/health/ready
    port: http
  initialDelaySeconds: 5
  periodSeconds: 5
livenessProbe:
  httpGet:
    path: /q/health/live
    port: http
  initialDelaySeconds: 10
  periodSeconds: 10
```

---

## Port-forward

```bash
# Kind / Kubernetes
kubectl -n jafra-system port-forward svc/async-profiler-mcp-server 18081:8080
```

---

## Environment Variables

| Variable | Required | Default | Description |
|---|---|---|---|
| `JAFRA_ANALYZER_URL` | **Yes** | `http://localhost:8080` | Base URL of the Jafra Analyzer. Every `get_recording_report` and `get_jfr_summary` call is forwarded here. |
| `PROFILER_DEFAULT_NAMESPACE` | No | `default` | Fallback Kubernetes namespace when a caller omits `namespace`. |
| `QUARKUS_HTTP_PORT` | No | `8080` | Override the HTTP port. |

> **`JAFRA_ANALYZER_URL` is required.** Without it the server starts but `get_recording_report` and `get_jfr_summary` will fail with a connection error.

### In-cluster DNS (Kind / Kubernetes)

```yaml
env:
  - name: JAFRA_ANALYZER_URL
    value: "http://jafra-analyzer.jafra-system.svc.cluster.local:8080"
  - name: PROFILER_DEFAULT_NAMESPACE
    value: "default"
```

---

## MCP Tools (9 tools)

| # | Tool | Status | Arguments | Description |
|---|------|--------|-----------|-------------|
| 1 | `list_profiled_pods` | ✅ Live | `namespace?` | Discover pods with profiler sidecar |
| 2 | `get_pod_jvm_status` | ✅ Live | `pod_name`, `namespace?` | Sidecar health + latest recording metadata |
| 3 | `get_jvm_statistics` | ✅ Live | `pod_name` | Live JVM snapshot: heap, threads, GC |
| 4 | `start_recording` | 🔧 Stub | `pod_name`, `duration_seconds?`, `profile_type?` | Trigger a JFR recording |
| 5 | `stop_recording` | 🔧 Stub | `recording_id` | Stop a recording early |
| 6 | `get_recording` | 🔧 Stub | `recording_id` | Poll recording status |
| 7 | `get_recording_report` | ✅ Live | `namespace?`, `pod`, `container`, `last?` | JMC rule findings for a pod/window |
| 8 | `get_jfr_summary` | ✅ Live | `namespace?`, `pod`, `container`, `last?` | Compact LLM-ready event summary |
| 9 | `get_flame_graph` | 🔧 Stub | `recording_id`, `format?` | Flame graph as JSON or SVG |

**Stub tools** return a valid but placeholder response. They will be wired to the jafra-controller recording API once that is finalised.

---

## Jafra Analyzer Endpoints Called

| MCP Tool | HTTP call |
|---|---|
| `get_recording_report` | `GET /api/v1/namespaces/{ns}/pods/{pod}/containers/{c}/report?last={last}` |
| `get_jfr_summary` | `GET /api/v1/namespaces/{ns}/pods/{pod}/containers/{c}/summary?last={last}` |

---

## `get_jfr_summary` Response Structure

The `get_jfr_summary` tool fetches the raw `JafraSummaryDocument` from the analyzer and transforms it into a compact `McpSummary` payload (equivalent to the Python `summary_to_mcp.py` script in the jafra-io repo):

```
McpSummary
├── scope        — namespace, pod, container, recording window, file metadata
├── environment  — JVM version/args, GC config (young/old collector), heap
│                  settings, container CPU/memory limits, tick_hz
└── signals
    ├── cpu_throttled_time_ns, cpu_load_machine_pct, thread_cpu_user_pct
    ├── context_switch_rate_hz
    ├── wall_clock     — sample count, states (SLEEPING/RUNNABLE)
    ├── gc             — count, pause_ns, collectors, causes, young/old breakdown
    ├── heap           — used_b, committed_b, rss_b, container_memory_b, metaspace
    ├── alloc_top_classes, alloc_size_b
    ├── live_top_classes, live_alloc_size_b
    ├── locks          — monitor_enter, monitor_wait, thread_park, native_lock
    ├── deoptimization — count, reasons, actions, compilers
    ├── vm_operations  — count, operations, duration_ns
    ├── compiler       — compile_count, bailout_count, nmethods_size
    ├── threads        — active, daemon, peak
    ├── errors         — count, classes, messages
    └── classloading   — count, loaded/unloaded samples
```

---

## `get_recording_report` Response Structure

Returns the raw `JafraSummaryReport` from the Jafra Analyzer — a map of JMC rule findings:

```json
{
  "namespace": "default",
  "pod": "auth-cache-6fcdc64b64-2cnw9",
  "container": "auth-cache",
  "generatedAt": "2026-08-24T...",
  "findings": {
    "VMOperations": {
      "score": 100.0,
      "name": "VMOperation Peak Duration",
      "topic": "vm_operations",
      "description": "There are long lasting blocking VM operations..."
    },
    "GcPauseRatio": {
      "score": 91.9,
      "name": "GC Pressure",
      "topic": "heap",
      "description": "..."
    }
  }
}
```

Higher `score` = worse. Negative score = rule not applicable.

---

## Connect with MCP Inspector

```bash
npm install -g @modelcontextprotocol/inspector
npx @modelcontextprotocol/inspector http://localhost:18081/mcp/
```

---

## Source of truth for types

All DTOs are defined in [`ProfilingModels.java`](src/main/java/io/causa/profiler/dto/ProfilingModels.java).
