# MCP Tool Quick Reference

**For:** LLM Agent / Causa Engine Developers  
**Purpose:** Quick lookup of all 9 MCP tools — inputs, outputs, and workflow

---

## Tools at a Glance

| # | Tool | Status | Input | Output | When |
|---|------|--------|-------|--------|------|
| 1 | `list_profiled_pods` | ✅ Live | `namespace?` | Pod list | Discovery |
| 2 | `get_pod_jvm_status` | ✅ Live | `pod, namespace?` | Status | Pre-check |
| 3 | `get_jvm_statistics` | ✅ Live | `pod` | Live stats | Quick snapshot |
| 4 | `start_recording` | 🔧 Stub | `pod, duration?, type?` | RecordingMetadata | On alert |
| 5 | `stop_recording` | 🔧 Stub | `recording_id` | RecordingMetadata | Alert resolves |
| 6 | `get_recording` | 🔧 Stub | `recording_id` | RecordingMetadata | Poll every 10–15s |
| 7 | `get_recording_report` | ✅ Live | `ns?, pod, container, last?` | JMC findings map | Analysis |
| 8 | `get_jfr_summary` | ✅ Live | `ns?, pod, container, last?` | McpSummary | Analysis |
| 9 | `get_flame_graph` | 🔧 Stub | `recording_id, format?` | SVG \| JSON | Visualization |

> **Stub tools** return a valid placeholder response. They will be wired to the jafra-controller recording API once that is finalised.

---

## Current Workflow (live tools)

```
1. get_jfr_summary(ns, pod, container, last="5m")
   → broad performance overview: CPU, GC, heap, locks, errors

2. get_recording_report(ns, pod, container, last="5m")
   → 60+ JMC rule findings, each with score + description

3. Correlate findings → Root Cause Analysis
```

## Future Workflow (once stub tools are wired)

```
Prometheus Alert (heap > 75%)
  ↓
1. list_profiled_pods()                           → find target pods
  ↓
2. get_pod_jvm_status(pod)                        → verify READY
  ↓
3. start_recording(pod, duration_seconds=300)     → get recording_id
  ↓
4. POLL get_recording(recording_id) every 10–15s until status=READY
  ↓
5. get_recording_report(ns, pod, container)       → JMC findings
   get_jfr_summary(ns, pod, container)            → event-level signals
  ↓
(optional) get_flame_graph(recording_id, format="json")
  ↓
Correlate with metrics → Root Cause Analysis
```

---

## Tool Details

### 1. `list_profiled_pods` ✅

Discover pods with an async-profiler sidecar.

**Input:**
```json
{ "namespace": "default" }
```

**Output:**
```json
[{ "podName": "auth-cache-…", "namespace": "default", "profilerStatus": "READY" }]
```

---

### 2. `get_pod_jvm_status` ✅

Verify profiler readiness before requesting a report.

**Input:**
```json
{ "pod_name": "auth-cache-6fcdc64b64-2cnw9", "namespace": "default" }
```

**Output:**
```json
{
  "podName": "auth-cache-6fcdc64b64-2cnw9",
  "profilerStatus": "READY",
  "jvmHealth": { "isHealthy": true, "activeThreadCount": 42 }
}
```

---

### 3. `get_jvm_statistics` ✅

Live JVM metrics — no recording needed.

**Input:**
```json
{ "pod_name": "auth-cache-6fcdc64b64-2cnw9" }
```

**Output:**
```json
{
  "podName": "auth-cache-6fcdc64b64-2cnw9",
  "capturedAt": "2026-08-24T…",
  "heapUsedBytes": 258375680,
  "heapMaxBytes": 396361728,
  "heapUsagePercent": 65.2,
  "threadCount": 38,
  "gcCollectionCount": 14
}
```

---

### 4. `start_recording` 🔧 Stub

Trigger a JFR recording. Returns immediately with a `recording_id`.

**Input:**
```json
{
  "pod_name": "auth-cache-6fcdc64b64-2cnw9",
  "duration_seconds": 300,
  "profile_type": "CPU"
}
```

**Supported `profile_type` values:** `CPU`, `MEMORY`, `LOCK`, `ALLOCATION`, `ALL`

**Output:** `RecordingMetadata` with `status: "QUEUED"`

---

### 5. `stop_recording` 🔧 Stub

Stop an active recording early.

**Input:**
```json
{ "recording_id": "rec-abc123" }
```

**Output:** `RecordingMetadata` with `terminationReason: "USER_REQUESTED"`

---

### 6. `get_recording` 🔧 Stub

Poll recording status. Call every 10–15 seconds.

**Input:**
```json
{ "recording_id": "rec-abc123" }
```

**Status lifecycle:**
```
QUEUED → RECORDING → DOWNLOADING → ANALYSING → READY → DELIVERED
                                                ↑
                                     call get_recording_report here
```

**Early termination reasons:** `CONTAINER_OOM`, `POD_DELETED`, `ERROR`

---

### 7. `get_recording_report` ✅

JMC rule-based findings for a pod/time window. Calls the Jafra Analyzer `/report` endpoint.

**Input:**
```json
{
  "namespace": "default",
  "pod": "auth-cache-6fcdc64b64-2cnw9",
  "container": "auth-cache",
  "last": "5m"
}
```

**Output:** `JafraSummaryReport` — a map of findings keyed by rule ID:
```json
{
  "namespace": "default",
  "pod": "auth-cache-6fcdc64b64-2cnw9",
  "container": "auth-cache",
  "generatedAt": "2026-08-24T…",
  "findings": {
    "VMOperations": {
      "score": 100.0,
      "name": "VMOperation Peak Duration",
      "topic": "vm_operations",
      "description": "There are long lasting blocking VM operations…"
    },
    "HighGc": {
      "score": 91.9,
      "name": "GC Pressure",
      "topic": "heap",
      "description": "The JVM was paused for 1.0 during…"
    },
    "PID1Rule": {
      "score": 75.0,
      "name": "PID-1 Rule",
      "topic": "jvm_information",
      "description": "Process runs with PID 1…"
    }
  }
}
```

**Interpreting scores:** Higher = worse. Negative = not applicable to this recording.

---

### 8. `get_jfr_summary` ✅

Compact LLM-ready event summary. Calls the Jafra Analyzer `/summary` endpoint and transforms the raw JFR statistics into a structured `McpSummary`.

**Input:**
```json
{
  "namespace": "default",
  "pod": "auth-cache-6fcdc64b64-2cnw9",
  "container": "auth-cache",
  "last": "5m"
}
```

**Output:** `McpSummary` with three top-level sections:

```json
{
  "scope": {
    "namespace": "default",
    "pod": "auth-cache-6fcdc64b64-2cnw9",
    "container": "auth-cache",
    "window": { "start": "…", "end": "…", "from": "…", "to": "…" },
    "bytes": 5021697,
    "chunks": 51,
    "recordings": ["profile-0.jfr", "profile-1.jfr", "…"]
  },
  "environment": {
    "jvm": {
      "jvmName": "OpenJDK 64-Bit Server VM",
      "jvmVersion": "21.0.10+7-LTS",
      "jvmArguments": "-XX:+UseContainerSupport -Xms256m -Xmx378m …",
      "pid": "1"
    },
    "gc": { "young": "DefNew", "old": "SerialOld", "gcTimeRatio": 99.0 },
    "heap_min_b": 268435456,
    "heap_max_b": 396361728,
    "container": { "containerType": "cgroupv2", "cpus": 1, "memory_limit_b": 1073741824 },
    "tick_hz": 1000000000
  },
  "signals": {
    "cpu_load_machine_pct": { "unit": "%", "min": 0.0, "max": 43.2, "avg": 25.5 },
    "gc": {
      "count": 13,
      "pause_ns": { "unit": "ticks", "avg": 341672906 },
      "collectors": ["DefNew", "SerialOld"],
      "causes": ["Allocation Failure", "Metadata GC Threshold"]
    },
    "heap": {
      "used_b": { "avg": 280000000 },
      "metaspace": { "used_b": { "avg": 62000000 } }
    },
    "locks": {
      "monitor_enter": { "count": 3, "classes": ["java.util.HashMap", "…"] }
    },
    "errors": { "count": 1, "classes": ["java.lang.NoSuchMethodError"] }
  }
}
```

---

### 9. `get_flame_graph` 🔧 Stub

Flame graph for a completed recording.

**Input:**
```json
{ "recording_id": "rec-abc123", "format": "json" }
```

**`format` values:** `json` (default, LLM-readable hotspot tree) · `svg` (interactive browser page)

---

## Error Handling

All errors follow the JSON-RPC error envelope:

```json
{
  "error": {
    "code": -32603,
    "message": "Internal error",
    "data": {
      "errorCode": "SPECIFIC_CODE",
      "message": "Human-readable message",
      "timestamp": "2026-08-24T…"
    }
  }
}
```

---

## Polling Strategy (for stub tools, when wired)

| Operation | Interval | Timeout |
|-----------|----------|---------|
| `get_recording` | 10–15 s | 10–15 min |
| `get_jvm_statistics` (monitoring) | 5–10 s | continuous |

---

## Links

- **Full Specification:** [`MCP_TOOL_CONTRACT_SPECIFICATION.md`](MCP_TOOL_CONTRACT_SPECIFICATION.md)
- **Models:** [`ProfilingModels.java`](src/main/java/io/causa/profiler/dto/ProfilingModels.java)
- **Tools implementation:** [`ProfilingTools.java`](src/main/java/io/causa/profiler/mcp/ProfilingTools.java)
- **Architecture:** [`README.md`](README.md)
