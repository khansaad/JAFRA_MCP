package io.causa.profiler.mcp;

import io.causa.profiler.dto.ProfilingModels.*;
import io.causa.profiler.service.ProfilerService;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/**
 * MCP tool definitions for the Async Profiler MCP Server.
 *
 * Every public method annotated with {@link Tool} becomes an MCP tool that the
 * LLM can discover and call.  The Quarkus MCP extension handles JSON-RPC
 * serialisation, tool-list advertising, and transport (stdio / SSE).
 *
 * Tool inventory (9 tools):
 *   1. list_profiled_pods    – discover pods with an async-profiler sidecar
 *   2. get_pod_jvm_status    – profiler readiness + latest recording info
 *   3. get_jvm_statistics    – live JVM snapshot (no recording needed)
 *   4. start_recording       – trigger a new JFR recording (TODO: wire to jafra-controller)
 *   5. stop_recording        – stop an in-progress recording early (TODO: wire to jafra-controller)
 *   6. get_recording         – poll recording status (TODO: wire to jafra-controller)
 *   7. get_recording_report  – JMC rule-based analysis findings for a pod/window
 *   8. get_jfr_summary       – compact MCP-friendly JFR event summary for a pod/window
 *   9. get_flame_graph       – retrieve SVG or JSON flame-graph (TODO: wire to jafra-controller)
 *
 * Tools 4, 5, 6, 9 are defined and exposed to the LLM today.  Their service-layer
 * implementations currently return stub responses and will be wired to the
 * jafra-controller REST API once that API is finalised.
 */
@ApplicationScoped
public class ProfilingTools {

    @Inject
    ProfilerService profilerService;

    // ── 1. list_profiled_pods ─────────────────────────────────────────────────

    @Tool(name = "list_profiled_pods",
          description = """
              Discover all JVM pods that have an async-profiler sidecar ready for profiling.
              Returns pod name, namespace, JVM version, profiler status, and the ID of the
              most recent recording (if any).
              """)
    public List<ProfiledPodMetadata> listProfiledPods(
            @ToolArg(name = "namespace",
                     description = "Kubernetes namespace to filter by. Optional — omit to list all namespaces.",
                     required = false)
            String namespace) {

        Log.infof("[MCP] list_profiled_pods namespace=%s", namespace);
        return profilerService.listProfiledPods();
    }

    // ── 2. get_pod_jvm_status ─────────────────────────────────────────────────

    @Tool(name = "get_pod_jvm_status",
          description = """
              Return the profiler readiness and JVM health for a specific pod.
              Use this before requesting a report to confirm the sidecar is attached.
              """)
    public PodJvmStatus getPodJvmStatus(
            @ToolArg(name = "pod_name", description = "Kubernetes pod name.") String podName,
            @ToolArg(name = "namespace",
                     description = "Kubernetes namespace. Optional — omit to use the configured default.",
                     required = false)
            String namespace) {

        Log.infof("[MCP] get_pod_jvm_status pod=%s", podName);
        return profilerService.getPodJvmStatus(podName, namespace);
    }

    // ── 3. get_jvm_statistics ─────────────────────────────────────────────────

    @Tool(name = "get_jvm_statistics",
          description = """
              Return a live JVM statistics snapshot for a pod: heap usage, thread count,
              GC collection totals, and loaded class count.
              No recording is required — data is collected on demand via the sidecar.
              """)
    public JvmStatistics getJvmStatistics(
            @ToolArg(name = "pod_name", description = "Kubernetes pod name.") String podName) {

        Log.infof("[MCP] get_jvm_statistics pod=%s", podName);
        return profilerService.getJvmStatistics(podName);
    }

    // ── 4. start_recording ────────────────────────────────────────────────────
    // TODO: will be wired to the jafra-controller REST API once it exposes a
    //       recording-trigger endpoint.  Currently returns a stub response.

    @Tool(name = "start_recording",
          description = """
              Start a JFR recording on the target pod via async-profiler.
              Call this when a Prometheus alert fires (e.g. heap > 75%).

              Returns a recording_id immediately.  The recording runs for
              duration_seconds (default 300 / 5 min).  Pass duration_seconds=0
              to run indefinitely until stop_recording is called.

              Poll get_recording until status=READY, then call get_recording_report.

              NOTE: this tool will be fully wired to the jafra-controller once
              the controller recording API is finalised.
              """)
    public RecordingMetadata startRecording(
            @ToolArg(name = "pod_name", description = "Kubernetes pod name to profile.") String podName,
            @ToolArg(name = "duration_seconds",
                     description = "How long to record in seconds. Default 300 (5 min). Pass 0 to run until stop_recording is called.",
                     required = false)
            Integer durationSeconds,
            @ToolArg(name = "profile_type",
                     description = "What to profile: CPU | MEMORY | LOCK | ALLOCATION | ALL. Default CPU.",
                     required = false)
            String profileType) {

        int duration = (durationSeconds != null && durationSeconds >= 0) ? durationSeconds : 300;
        String type  = (profileType != null && !profileType.isBlank()) ? profileType : "CPU";

        Log.infof("[MCP] start_recording pod=%s duration=%d type=%s", podName, duration, type);
        return profilerService.startRecording(podName, duration, type);
    }

    // ── 5. stop_recording ─────────────────────────────────────────────────────
    // TODO: will be wired to the jafra-controller REST API once it exposes a
    //       recording-stop endpoint.  Currently returns a stub response.

    @Tool(name = "stop_recording",
          description = """
              Stop an in-progress JFR recording before its configured duration expires.
              The controller finalises the JFR file and begins analysis immediately.
              The returned metadata will have terminationReason=USER_REQUESTED.

              Poll get_recording to track the transition to READY,
              then call get_recording_report.

              NOTE: this tool will be fully wired to the jafra-controller once
              the controller recording API is finalised.
              """)
    public RecordingMetadata stopRecording(
            @ToolArg(name = "recording_id",
                     description = "The recording_id returned by start_recording.")
            String recordingId) {

        Log.infof("[MCP] stop_recording id=%s", recordingId);
        return profilerService.stopRecording(recordingId);
    }

    // ── 6. get_recording ──────────────────────────────────────────────────────
    // TODO: will be wired to the jafra-controller REST API once it exposes a
    //       recording-status endpoint.  Currently returns a stub response.

    @Tool(name = "get_recording",
          description = """
              Poll the current status of a JFR recording.

              Status lifecycle:
                QUEUED → RECORDING → DOWNLOADING → ANALYSING → READY → DELIVERED

              Poll every 10–15 s after starting a recording.
              Call get_recording_report once status=READY.
              CONTAINER_OOM or POD_DELETED will appear in terminationReason if the
              pod was killed while profiling.

              NOTE: this tool will be fully wired to the jafra-controller once
              the controller recording API is finalised.
              """)
    public RecordingMetadata getRecording(
            @ToolArg(name = "recording_id",
                     description = "The recording_id returned by start_recording.")
            String recordingId) {

        Log.debugf("[MCP] get_recording id=%s", recordingId);
        return profilerService.getRecording(recordingId);
    }

    // ── 7. get_recording_report ───────────────────────────────────────────────

    @Tool(name = "get_recording_report",
          description = """
              Retrieve a JMC rule-based analysis report for a Kubernetes container.

              The report contains structured 'findings' keyed by rule ID, each with:
              • score       — severity score (higher = worse); negative means not applicable
              • name        — human-readable rule name
              • topic       — category (garbage_collection, java_application, heap, etc.)
              • description — detailed explanation with recommendations

              Use the 'last' parameter to specify a time window (e.g. "5m" for the last
              5 minutes of recorded JFR data).

              Example: get_recording_report(namespace="default", pod="auth-cache-6fcdc64b64-2cnw9",
                         container="auth-cache", last="5m")
              """)
    public JafraSummaryReport getRecordingReport(
            @ToolArg(name = "namespace",
                     description = "Kubernetes namespace. Optional — defaults to the configured default.",
                     required = false)
            String namespace,
            @ToolArg(name = "pod", description = "Kubernetes pod name.")
            String pod,
            @ToolArg(name = "container", description = "Container name within the pod.")
            String container,
            @ToolArg(name = "last",
                     description = "Time window shorthand, e.g. '5m' for last 5 minutes, '10m' for last 10 minutes. Optional.",
                     required = false)
            String last) {

        Log.infof("[MCP] get_recording_report ns=%s pod=%s container=%s last=%s", namespace, pod, container, last);
        return profilerService.getRecordingReport(namespace, pod, container, last);
    }

    // ── 8. get_jfr_summary ────────────────────────────────────────────────────

    @Tool(name = "get_jfr_summary",
          description = """
              Retrieve a compact, LLM-ready JFR event summary for a Kubernetes container.

              The response is structured into three sections:
              • scope       — recording window, pod coordinates, file metadata
              • environment — JVM version, GC configuration, heap settings, container limits
              • signals     — key performance metrics derived from JFR events:
                  - CPU throttling, machine CPU load, thread CPU
                  - Context switch rate
                  - Wall-clock sample states
                  - GC pause stats (count, duration, collectors, causes)
                  - Heap usage (used, committed, max, RSS, metaspace)
                  - Top allocation classes and sizes
                  - Lock contention (monitor enter/wait, thread park, native lock)
                  - Deoptimisation events
                  - VM operation durations
                  - JIT compiler stats
                  - Thread counts
                  - Error and exception types
                  - Class loading counts

              Use this tool when you need a broad performance overview.
              Use get_recording_report when you need actionable rule-based findings.

              Use the 'last' parameter to specify a time window (e.g. "5m" for the last
              5 minutes of recorded JFR data).

              Example: get_jfr_summary(namespace="default", pod="auth-cache-6fcdc64b64-2cnw9",
                         container="auth-cache", last="5m")
              """)
    public McpSummary getJfrSummary(
            @ToolArg(name = "namespace",
                     description = "Kubernetes namespace. Optional — defaults to the configured default.",
                     required = false)
            String namespace,
            @ToolArg(name = "pod", description = "Kubernetes pod name.")
            String pod,
            @ToolArg(name = "container", description = "Container name within the pod.")
            String container,
            @ToolArg(name = "last",
                     description = "Time window shorthand, e.g. '5m' for last 5 minutes, '10m' for last 10 minutes. Optional.",
                     required = false)
            String last) {

        Log.infof("[MCP] get_jfr_summary ns=%s pod=%s container=%s last=%s", namespace, pod, container, last);
        return profilerService.getJfrSummary(namespace, pod, container, last);
    }

    // ── 9. get_flame_graph ────────────────────────────────────────────────────
    // TODO: will be wired to the jafra-controller REST API once it exposes a
    //       flame-graph endpoint.  Currently returns a stub response.

    @Tool(name = "get_flame_graph",
          description = """
              Return flame-graph data for a completed recording.

              format=svg  — returns a self-contained interactive HTML/SVG page
              format=json — returns a JSON tree of hotspot frames consumable
                            directly by the LLM without rendering

              Prefer format=json when the LLM needs to reason over the data.
              Prefer format=svg when a developer needs a visual.

              NOTE: this tool will be fully wired to the jafra-controller once
              the controller flame-graph API is finalised.
              """)
    public String getFlameGraph(
            @ToolArg(name = "recording_id",
                     description = "The recording_id returned by start_recording.")
            String recordingId,
            @ToolArg(name = "format",
                     description = "Output format: 'svg' or 'json'. Default 'json'.",
                     required = false)
            String format) {

        String fmt = ("svg".equalsIgnoreCase(format)) ? "svg" : "json";
        Log.infof("[MCP] get_flame_graph id=%s format=%s", recordingId, fmt);
        return profilerService.getFlameGraph(recordingId, fmt);
    }
}
