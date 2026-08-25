# Documentation Index

**Repository:** async-profiler-mcp-server
**Image:** `quay.io/khansaad/async-profiler-mcp-server:0.1.0`
**Status:** ✅ Live tools working on Kind cluster

---

## Documents

### [`README.md`](README.md)
Architecture overview, tool inventory, typical agent flow, project layout.  
**Start here.**

### [`QUICK_START.md`](QUICK_START.md)
Step-by-step instructions for local dev, Kind deployment, and OpenShift deployment.  
Includes smoke test instructions.

### [`INTEGRATION_REFERENCE.md`](INTEGRATION_REFERENCE.md)
Image details, environment variables, transport modes, health probes, and full `McpSummary` / `JafraSummaryReport` response shapes.  
**For teams integrating the MCP server into an agent or infrastructure.**

### [`MCP_TOOL_QUICK_REFERENCE.md`](MCP_TOOL_QUICK_REFERENCE.md)
All 9 tools at a glance — inputs, outputs, real example payloads, workflow diagrams, error handling.  
**For daily development reference.**

### [`MCP_TOOL_CONTRACT_SPECIFICATION.md`](MCP_TOOL_CONTRACT_SPECIFICATION.md)
Full formal specification with complete JSON schemas, all error codes, and example JSON-RPC request/response pairs.  
**For deep-dive implementation and edge-case handling.**

---

## Tool Status Summary

| # | Tool                   | Status             | Backend                                 |
|---|------------------------|--------------------|-----------------------------------------|
| 1 | `list_profiled_pods`   | ✅ **Fully wired**  | Jafra Analyzer `GET /api/v1/recordings` |
| 2 | `get_recording_report` | ✅ **Fully wired**  | Jafra Analyzer `/report`                |
| 3 | `get_jfr_summary`      | ✅ **Fully wired**  | Jafra Analyzer `/summary`               |
| 4 | `get_pod_jvm_status`   | ✅ Live (stub impl) | jafra-controller (TBD)                  |
| 5 | `get_jvm_statistics`   | ✅ Live (stub impl) | jafra-controller (TBD)                  |
| 6 | `start_recording`      | 🔧 Stub            | jafra-controller (TBD)                  |
| 7 | `stop_recording`       | 🔧 Stub            | jafra-controller (TBD)                  |
| 8 | `get_recording`        | 🔧 Stub            | jafra-controller (TBD)                  |
| 9 | `get_flame_graph`      | 🔧 Stub            | jafra-controller (TBD)                  |

---

## Source Files

| File                                                                                               | Purpose                                                                                                                                          |
|----------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------|
| [`ProfilingModels.java`](src/main/java/io/causa/profiler/dto/ProfilingModels.java)                 | All DTOs: `JafraSummaryReport`, `JafraSummaryDocument`, `McpSummary`, `RecordingsListResponse`, `WorkloadRecordings`, `RecordingMetadata`, enums |
| [`ProfilingTools.java`](src/main/java/io/causa/profiler/mcp/ProfilingTools.java)                   | All 9 `@Tool` methods                                                                                                                            |
| [`ProfilerService.java`](src/main/java/io/causa/profiler/service/ProfilerService.java)             | Service interface                                                                                                                                |
| [`ProfilerServiceClient.java`](src/main/java/io/causa/profiler/service/ProfilerServiceClient.java) | Jafra Analyzer REST client + `summary_to_mcp.py` logic (Java port) + 404 error handling                                                          |
| [`ProfilingToolsTest.java`](src/test/java/io/causa/profiler/mcp/ProfilingToolsTest.java)           | 19 unit tests covering all 9 tools                                                                                                               |
| [`smoke_test.py`](smoke_test.py)                                                                   | End-to-end MCP handshake smoke test                                                                                                              |
| [`manifests/async-profiler-mcp-server-kind.yaml`](manifests/async-profiler-mcp-server-kind.yaml)   | Kind deployment manifest                                                                                                                         |

---

## Key Design Decisions

**Why is `get_recording_report` now `(ns, pod, container, last)` instead of `(recording_id)`?**  
The Jafra Analyzer doesn't expose a recording-ID-based report endpoint. It uses a time-window selector (`?last=5m`) against a namespace/pod/container key. The old `recording_id`-based signature was based on a fictional controller API that doesn't exist yet.

**Why does `get_jfr_summary` exist as a separate tool?**  
The Jafra Analyzer exposes two distinct endpoints: `/report` (JMC rule analysis) and `/summary` (raw JFR event statistics). The Python `summary_to_mcp.py` script in jafra-io transforms the `/summary` response into a structured payload well-suited for LLM reasoning. `get_jfr_summary` is the Java port of that transformation, delivered as a dedicated MCP tool.

**Why are `start_recording`, `stop_recording`, `get_recording`, `get_flame_graph` stubs?**
These tools will be wired to the jafra-controller once it exposes a recording lifecycle REST API. They are kept in the tool inventory today so agent implementations can be written against the full 9-tool surface without a breaking change later.

