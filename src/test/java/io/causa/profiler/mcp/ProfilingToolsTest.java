package io.causa.profiler.mcp;

import io.causa.profiler.dto.ProfilingModels.*;
import io.causa.profiler.service.ProfilerService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for all 9 MCP tools.
 *
 * ProfilerService is mocked — the tests verify that:
 *   a) the correct service method is called with the right arguments, and
 *   b) the tool returns the value from the service unchanged.
 *
 * Tool-name → Java-method mapping:
 *   list_profiled_pods   → listProfiledPods
 *   get_pod_jvm_status   → getPodJvmStatus
 *   get_jvm_statistics   → getJvmStatistics
 *   start_recording      → startRecording       (stub — not yet wired to jafra-controller)
 *   stop_recording       → stopRecording        (stub — not yet wired to jafra-controller)
 *   get_recording        → getRecording         (stub — not yet wired to jafra-controller)
 *   get_recording_report → getRecordingReport   (ns, pod, container, last)
 *   get_jfr_summary      → getJfrSummary        (ns, pod, container, last)
 *   get_flame_graph      → getFlameGraph        (stub — not yet wired to jafra-controller)
 */
@QuarkusTest
class ProfilingToolsTest {

    @InjectMock
    ProfilerService profilerService;

    // ProfilingTools is a CDI bean; Quarkus injects the mock automatically.
    @jakarta.inject.Inject
    ProfilingTools tools;

    // ── 1. list_profiled_pods ──────────────────────────────────────────────────

    @Test
    void listProfiledPods_returnsDelegateResult() {
        var pod = new ProfiledPodMetadata();
        pod.podName = "myapp-0";
        pod.namespace = "default";
        pod.profilerStatus = "READY";
        when(profilerService.listProfiledPods()).thenReturn(List.of(pod));

        var result = tools.listProfiledPods(null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).podName).isEqualTo("myapp-0");
        verify(profilerService).listProfiledPods();
    }

    // ── 2. get_pod_jvm_status ──────────────────────────────────────────────────

    @Test
    void getPodJvmStatus_delegatesCorrectly() {
        var status = new PodJvmStatus();
        status.podName = "myapp-0";
        status.profilerStatus = "READY";
        when(profilerService.getPodJvmStatus("myapp-0", "default")).thenReturn(status);

        var result = tools.getPodJvmStatus("myapp-0", "default");

        assertThat(result.podName).isEqualTo("myapp-0");
        assertThat(result.profilerStatus).isEqualTo("READY");
    }

    // ── 3. get_jvm_statistics ──────────────────────────────────────────────────

    @Test
    void getJvmStatistics_returnsHeapInfo() {
        var stats = new JvmStatistics();
        stats.podName = "myapp-0";
        stats.heapUsedBytes    = 600_000_000L;
        stats.heapMaxBytes     = 1_024_000_000L;
        stats.heapUsagePercent = 58.6;
        when(profilerService.getJvmStatistics("myapp-0")).thenReturn(stats);

        var result = tools.getJvmStatistics("myapp-0");

        assertThat(result.heapUsagePercent).isEqualTo(58.6);
        verify(profilerService).getJvmStatistics("myapp-0");
    }

    // ── 4. start_recording (stub) ──────────────────────────────────────────────

    @Test
    void startRecording_usesDefaultDurationAndTypeWhenNull() {
        var stub = recordingMeta("rec-001", RecordingStatus.QUEUED);
        when(profilerService.startRecording("myapp-0", 300, "CPU")).thenReturn(stub);

        var result = tools.startRecording("myapp-0", null, null);

        assertThat(result.recordingId).isEqualTo("rec-001");
        assertThat(result.status).isEqualTo(RecordingStatus.QUEUED);
        verify(profilerService).startRecording("myapp-0", 300, "CPU");
    }

    @Test
    void startRecording_respectsExplicitDurationAndType() {
        when(profilerService.startRecording("myapp-0", 120, "MEMORY"))
                .thenReturn(recordingMeta("rec-002", RecordingStatus.QUEUED));

        tools.startRecording("myapp-0", 120, "MEMORY");

        verify(profilerService).startRecording("myapp-0", 120, "MEMORY");
    }

    @Test
    void startRecording_zeroDurationMeansRunUntilStop() {
        when(profilerService.startRecording("myapp-0", 0, "CPU"))
                .thenReturn(recordingMeta("rec-003", RecordingStatus.QUEUED));

        tools.startRecording("myapp-0", 0, null);

        verify(profilerService).startRecording("myapp-0", 0, "CPU");
    }

    // ── 5. stop_recording (stub) ───────────────────────────────────────────────

    @Test
    void stopRecording_setsTerminationReason() {
        var stub = recordingMeta("rec-001", RecordingStatus.DOWNLOADING);
        stub.terminationReason = TerminationReason.USER_REQUESTED;
        when(profilerService.stopRecording("rec-001")).thenReturn(stub);

        var result = tools.stopRecording("rec-001");

        assertThat(result.terminationReason).isEqualTo(TerminationReason.USER_REQUESTED);
        verify(profilerService).stopRecording("rec-001");
    }

    // ── 6. get_recording (stub) ────────────────────────────────────────────────

    @Test
    void getRecording_returnsAnalysingWhileProcessing() {
        when(profilerService.getRecording("rec-001"))
                .thenReturn(recordingMeta("rec-001", RecordingStatus.ANALYSING));

        assertThat(tools.getRecording("rec-001").status).isEqualTo(RecordingStatus.ANALYSING);
    }

    @Test
    void getRecording_returnsReadyWhenDone() {
        when(profilerService.getRecording("rec-001"))
                .thenReturn(recordingMeta("rec-001", RecordingStatus.READY));

        assertThat(tools.getRecording("rec-001").status).isEqualTo(RecordingStatus.READY);
    }

    @Test
    void getRecording_reflectsContainerOom() {
        var stub = recordingMeta("rec-001", RecordingStatus.ERROR);
        stub.terminationReason = TerminationReason.CONTAINER_OOM;
        when(profilerService.getRecording("rec-001")).thenReturn(stub);

        assertThat(tools.getRecording("rec-001").terminationReason)
                .isEqualTo(TerminationReason.CONTAINER_OOM);
    }

    // ── 7. get_recording_report ────────────────────────────────────────────────

    @Test
    void getRecordingReport_returnsFindings() {
        var report = new JafraSummaryReport();
        report.namespace   = "default";
        report.pod         = "auth-cache-6fcdc64b64-2cnw9";
        report.container   = "auth-cache";
        report.generatedAt = "2026-08-21T07:05:33Z";

        var finding = new JafraSummaryReport.AnalysisFinding();
        finding.score       = 100.0;
        finding.name        = "VMOperation Peak Duration";
        finding.topic       = "vm_operations";
        finding.description = "Long VM operations detected.";
        report.findings = Map.of("VMOperations", finding);

        when(profilerService.getRecordingReport("default", "auth-cache-6fcdc64b64-2cnw9", "auth-cache", "5m"))
                .thenReturn(report);

        var result = tools.getRecordingReport("default", "auth-cache-6fcdc64b64-2cnw9", "auth-cache", "5m");

        assertThat(result.namespace).isEqualTo("default");
        assertThat(result.findings).containsKey("VMOperations");
        assertThat(result.findings.get("VMOperations").score).isEqualTo(100.0);
        verify(profilerService).getRecordingReport("default", "auth-cache-6fcdc64b64-2cnw9", "auth-cache", "5m");
    }

    @Test
    void getRecordingReport_nullNamespaceDelegatesAsNull() {
        when(profilerService.getRecordingReport(null, "myapp-0", "myapp", "5m"))
                .thenReturn(new JafraSummaryReport());

        tools.getRecordingReport(null, "myapp-0", "myapp", "5m");

        verify(profilerService).getRecordingReport(null, "myapp-0", "myapp", "5m");
    }

    // ── 8. get_jfr_summary ─────────────────────────────────────────────────────

    @Test
    void getJfrSummary_returnsCompactPayload() {
        var summary = new McpSummary();

        var scope = new McpSummary.ScopeBlock();
        scope.namespace = "default";
        scope.pod       = "auth-cache-6fcdc64b64-2cnw9";
        scope.container = "auth-cache";
        summary.scope = scope;

        var env = new McpSummary.EnvironmentBlock();
        var jvmInfo = new McpSummary.JvmInfo();
        jvmInfo.jvmName    = "OpenJDK 64-Bit Server VM";
        jvmInfo.jvmVersion = "21.0.10+7-LTS";
        env.jvm = jvmInfo;
        summary.environment = env;

        when(profilerService.getJfrSummary("default", "auth-cache-6fcdc64b64-2cnw9", "auth-cache", "5m"))
                .thenReturn(summary);

        var result = tools.getJfrSummary("default", "auth-cache-6fcdc64b64-2cnw9", "auth-cache", "5m");

        assertThat(result.scope.namespace).isEqualTo("default");
        assertThat(result.environment.jvm.jvmName).isEqualTo("OpenJDK 64-Bit Server VM");
        verify(profilerService).getJfrSummary("default", "auth-cache-6fcdc64b64-2cnw9", "auth-cache", "5m");
    }

    @Test
    void getJfrSummary_nullLastIsPassedThrough() {
        when(profilerService.getJfrSummary("default", "myapp-0", "myapp", null))
                .thenReturn(new McpSummary());

        tools.getJfrSummary("default", "myapp-0", "myapp", null);

        verify(profilerService).getJfrSummary("default", "myapp-0", "myapp", null);
    }

    // ── 9. get_flame_graph (stub) ──────────────────────────────────────────────

    @Test
    void getFlameGraph_defaultsToJson() {
        when(profilerService.getFlameGraph("rec-001", "json"))
                .thenReturn("{\"frames\":[]}");

        var result = tools.getFlameGraph("rec-001", null);

        assertThat(result).contains("frames");
        verify(profilerService).getFlameGraph("rec-001", "json");
    }

    @Test
    void getFlameGraph_returnsSvgWhenRequested() {
        when(profilerService.getFlameGraph("rec-001", "svg"))
                .thenReturn("<svg>...</svg>");

        var result = tools.getFlameGraph("rec-001", "svg");

        assertThat(result).startsWith("<svg>");
        verify(profilerService).getFlameGraph("rec-001", "svg");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private RecordingMetadata recordingMeta(String id, RecordingStatus status) {
        var m = new RecordingMetadata();
        m.recordingId = id;
        m.podName     = "myapp-0";
        m.status      = status;
        m.createdAt   = Instant.now();
        return m;
    }
}
