package io.causa.profiler.service;

import io.causa.profiler.dto.ProfilingModels.*;

import java.util.List;

/**
 * Contract between the MCP tool layer and the Jafra Analyzer / Controller backend.
 *
 * The Jafra stack owns:
 *   - sidecar injection (jafra-controller / jafra-agent)
 *   - JFR streaming from the target JVM
 *   - Recording catalog and time-window selection (jafra-analyzer)
 *   - JMC rule analysis and JFR event summarisation (jafra-analyzer)
 *
 * This MCP server only calls this interface; it knows nothing about async-profiler
 * or JFR internals.
 */
public interface ProfilerService {

    // ── Pod discovery ──────────────────────────────────────────────────────────

    /**
     * Return all pods that have an async-profiler sidecar available.
     *
     * <p>MCP tool: {@code list_profiled_pods}
     */
    List<ProfiledPodMetadata> listProfiledPods();

    /**
     * Return JVM health and profiler readiness for a single pod.
     *
     * <p>MCP tool: {@code get_pod_jvm_status}
     *
     * @param podName   Kubernetes pod name
     * @param namespace Kubernetes namespace (null → use configured default)
     */
    PodJvmStatus getPodJvmStatus(String podName, String namespace);

    /**
     * Return a live JVM statistics snapshot.  Does not require a recording.
     *
     * <p>MCP tool: {@code get_jvm_statistics}
     *
     * @param podName Kubernetes pod name
     */
    JvmStatistics getJvmStatistics(String podName);

    // ── Recording lifecycle (TODO: wire to jafra-controller) ──────────────────
    // These methods are declared here and exposed as MCP tools today.
    // Implementations return stub responses until the jafra-controller
    // recording REST API is finalised and integrated.

    /**
     * Start a JFR recording on the target pod.
     *
     * <p>MCP tool: {@code start_recording}
     *
     * @param podName         Kubernetes pod name
     * @param durationSeconds Profiling duration. 0 means "run until explicitly stopped".
     * @param profileType     CPU | MEMORY | LOCK | ALLOCATION | ALL
     */
    RecordingMetadata startRecording(String podName, int durationSeconds, String profileType);

    /**
     * Stop an in-progress recording early.
     *
     * <p>MCP tool: {@code stop_recording}
     *
     * @param recordingId The recording ID returned by {@link #startRecording}
     */
    RecordingMetadata stopRecording(String recordingId);

    /**
     * Poll the current status of a recording.
     *
     * <p>MCP tool: {@code get_recording}
     *
     * @param recordingId The recording ID
     */
    RecordingMetadata getRecording(String recordingId);

    /**
     * Return flame-graph data for a completed recording.
     *
     * <p>MCP tool: {@code get_flame_graph}
     *
     * @param recordingId The recording ID
     * @param format      "svg" for an interactive HTML page, "json" for a hotspot tree
     */
    String getFlameGraph(String recordingId, String format);

    // ── Analysis (Jafra Analyzer endpoints) ───────────────────────────────────

    /**
     * Fetch the JMC rule-based analysis report for the given container/window.
     * Calls {@code GET /api/v1/namespaces/{ns}/pods/{pod}/containers/{container}/report}.
     *
     * <p>MCP tool: {@code get_recording_report}
     *
     * @param namespace Kubernetes namespace
     * @param pod       Kubernetes pod name
     * @param container Container name within the pod
     * @param last      Time window shorthand, e.g. {@code "5m"} (optional)
     */
    JafraSummaryReport getRecordingReport(String namespace, String pod, String container, String last);

    /**
     * Fetch the raw JFR event summary and convert it to the MCP-friendly
     * {@link McpSummary} payload.
     * Calls {@code GET /api/v1/namespaces/{ns}/pods/{pod}/containers/{container}/summary}.
     *
     * <p>MCP tool: {@code get_jfr_summary}
     *
     * @param namespace Kubernetes namespace
     * @param pod       Kubernetes pod name
     * @param container Container name within the pod
     * @param last      Time window shorthand, e.g. {@code "5m"} (optional)
     */
    McpSummary getJfrSummary(String namespace, String pod, String container, String last);
}
