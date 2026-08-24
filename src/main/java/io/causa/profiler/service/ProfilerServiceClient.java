package io.causa.profiler.service;

import io.causa.profiler.dto.ProfilingModels.*;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * REST client that talks to the Jafra Analyzer backend.
 *
 * Jafra Analyzer REST API contract (base: /api/v1):
 *
 *   GET /recordings                                                           → RecordingListResponse
 *   GET /namespaces/{ns}/pods/{pod}/containers/{container}                    → WorkloadRecordings
 *   GET /namespaces/{ns}/pods/{pod}/containers/{container}/report?last=5m     → JafraSummaryReport
 *   GET /namespaces/{ns}/pods/{pod}/containers/{container}/summary?last=5m    → JafraSummaryDocument
 *
 * Pod discovery endpoints (pod metadata / status / stats) are served by the
 * jafra-controller; they are retained here with stub implementations until
 * the controller REST API is confirmed.
 */
@ApplicationScoped
public class ProfilerServiceClient implements ProfilerService {

    @Inject
    @RestClient
    JafraAnalyzerApi api;

    @ConfigProperty(name = "profiler.controller.default-namespace", defaultValue = "default")
    String defaultNamespace;

    // ── Pod discovery (controller stubs — wire up when controller API is confirmed) ──

    @Override
    public List<ProfiledPodMetadata> listProfiledPods() {
        Log.debug("listProfiledPods — not yet wired to jafra-controller");
        return Collections.emptyList();
    }

    @Override
    public PodJvmStatus getPodJvmStatus(String podName, String namespace) {
        Log.debugf("getPodJvmStatus pod=%s — not yet wired to jafra-controller", podName);
        return null;
    }

    @Override
    public JvmStatistics getJvmStatistics(String podName) {
        Log.debugf("getJvmStatistics pod=%s — not yet wired to jafra-controller", podName);
        return null;
    }

    // ── Recording lifecycle stubs (TODO: wire to jafra-controller) ────────────
    // These return stub responses today.  Replace with real REST calls once the
    // jafra-controller exposes /recordings, /recordings/{id}, etc.

    @Override
    public RecordingMetadata startRecording(String podName, int durationSeconds, String profileType) {
        Log.infof("startRecording pod=%s — not yet wired to jafra-controller", podName);
        RecordingMetadata stub = new RecordingMetadata();
        stub.podName = podName;
        stub.status = RecordingStatus.QUEUED;
        stub.profileType = profileType;
        stub.durationSeconds = durationSeconds;
        return stub;
    }

    @Override
    public RecordingMetadata stopRecording(String recordingId) {
        Log.infof("stopRecording id=%s — not yet wired to jafra-controller", recordingId);
        RecordingMetadata stub = new RecordingMetadata();
        stub.recordingId = recordingId;
        stub.status = RecordingStatus.ERROR;
        stub.terminationReason = TerminationReason.USER_REQUESTED;
        return stub;
    }

    @Override
    public RecordingMetadata getRecording(String recordingId) {
        Log.debugf("getRecording id=%s — not yet wired to jafra-controller", recordingId);
        RecordingMetadata stub = new RecordingMetadata();
        stub.recordingId = recordingId;
        stub.status = RecordingStatus.ERROR;
        return stub;
    }

    @Override
    public String getFlameGraph(String recordingId, String format) {
        Log.infof("getFlameGraph id=%s format=%s — not yet wired to jafra-controller", recordingId, format);
        return "json".equals(format)
                ? "{\"error\":\"flame-graph not yet wired to jafra-controller\"}"
                : "<svg><text y='20'>flame-graph not yet wired to jafra-controller</text></svg>";
    }

    // ── Jafra Analyzer — analysis endpoints ───────────────────────────────────

    @Override
    public JafraSummaryReport getRecordingReport(String namespace, String pod, String container, String last) {
        String ns = Objects.requireNonNullElse(namespace, defaultNamespace);
        Log.infof("getRecordingReport ns=%s pod=%s container=%s last=%s", ns, pod, container, last);
        return api.report(ns, pod, container, last);
    }

    @Override
    public McpSummary getJfrSummary(String namespace, String pod, String container, String last) {
        String ns = Objects.requireNonNullElse(namespace, defaultNamespace);
        Log.infof("getJfrSummary ns=%s pod=%s container=%s last=%s", ns, pod, container, last);
        JafraSummaryDocument doc = api.summary(ns, pod, container, last);
        return toMcpSummary(doc);
    }

    //  Pulls well-known JDK event types out of the raw
    // EventSummaryDocument and assembles the compact McpSummary payload.

    private static McpSummary toMcpSummary(JafraSummaryDocument doc) {
        if (doc == null) {
            return null;
        }
        Map<String, JafraSummaryDocument.EventSummary> events =
                doc.events != null ? doc.events : Collections.emptyMap();

        long tickHz = firstLong(events, "jdk.CPUTimeStampCounter", "osFrequency", 1_000_000_000L);
        double ticksToNs = 1_000_000_000.0 / tickHz;

        McpSummary out = new McpSummary();
        out.scope       = buildScope(doc);
        out.environment = buildEnvironment(events, tickHz);
        out.signals     = buildSignals(events, ticksToNs);
        return out;
    }

    private static McpSummary.ScopeBlock buildScope(JafraSummaryDocument doc) {
        McpSummary.ScopeBlock s = new McpSummary.ScopeBlock();
        s.namespace   = doc.namespace;
        s.pod         = doc.pod;
        s.container   = doc.container;
        s.bytes       = doc.bytes;
        s.chunks      = doc.chunks;
        s.generatedAt = doc.generatedAt;
        s.recordings  = doc.recordings != null ? doc.recordings : Collections.emptyList();

        McpSummary.WindowBlock w = new McpSummary.WindowBlock();
        w.start = doc.start;
        w.end   = doc.end;
        w.from  = doc.from;
        w.to    = doc.to;
        s.window = w;
        return s;
    }

    private static McpSummary.EnvironmentBlock buildEnvironment(
            Map<String, JafraSummaryDocument.EventSummary> events, long tickHz) {

        JafraSummaryDocument.EventSummary jvm     = event(events, "jdk.JVMInformation");
        JafraSummaryDocument.EventSummary gcCfg   = event(events, "jdk.GCConfiguration");
        JafraSummaryDocument.EventSummary heapCfg = event(events, "jdk.GCHeapConfiguration");
        JafraSummaryDocument.EventSummary ccfg    = event(events, "jdk.ContainerConfiguration");

        McpSummary.EnvironmentBlock env = new McpSummary.EnvironmentBlock();
        env.tickHz = tickHz;

        McpSummary.JvmInfo jvmInfo = new McpSummary.JvmInfo();
        jvmInfo.jvmName      = firstValue(jvm, "jvmName");
        jvmInfo.jvmVersion   = firstValue(jvm, "jvmVersion");
        jvmInfo.jvmArguments = firstValue(jvm, "jvmArguments");
        jvmInfo.javaArguments = firstValue(jvm, "javaArguments");
        jvmInfo.pid          = firstValue(jvm, "pid");
        env.jvm = jvmInfo;

        McpSummary.GcInfo gcInfo = new McpSummary.GcInfo();
        gcInfo.young            = firstValue(gcCfg, "youngCollector");
        gcInfo.old              = firstValue(gcCfg, "oldCollector");
        gcInfo.parallelGCThreads = numericAvg(gcCfg, "parallelGCThreads");
        gcInfo.gcTimeRatio      = numericAvg(gcCfg, "gcTimeRatio");
        env.gc = gcInfo;

        env.heapMinB     = numericAvg(heapCfg, "minSize");
        env.heapMaxB     = numericAvg(heapCfg, "maxSize");
        env.heapInitialB = numericAvg(heapCfg, "initialSize");

        McpSummary.ContainerInfo ci = new McpSummary.ContainerInfo();
        ci.containerType    = firstValue(ccfg, "containerType");
        ci.cpus             = firstValueAsNumber(ccfg, "effectiveCpuCount");
        ci.cpuQuota         = firstValueAsNumber(ccfg, "cpuQuota");
        ci.cpuPeriod        = firstValueAsNumber(ccfg, "cpuSlicePeriod");
        ci.cpuShares        = firstValueAsNumber(ccfg, "cpuShares");
        ci.memoryLimitB     = numericAvg(ccfg, "memoryLimit");
        ci.swapMemoryLimitB = numericAvg(ccfg, "swapMemoryLimit");
        env.container = ci;

        return env;
    }

    private static McpSummary.SignalsBlock buildSignals(
            Map<String, JafraSummaryDocument.EventSummary> events, double ticksToNs) {

        JafraSummaryDocument.EventSummary wall    = event(events, "profiler.WallClockSample");
        JafraSummaryDocument.EventSummary gc      = event(events, "jdk.GarbageCollection");
        JafraSummaryDocument.EventSummary young   = event(events, "jdk.YoungGarbageCollection");
        JafraSummaryDocument.EventSummary old     = event(events, "jdk.OldGarbageCollection");
        JafraSummaryDocument.EventSummary metaFail = event(events, "jdk.MetaspaceAllocationFailure");
        JafraSummaryDocument.EventSummary enter   = event(events, "jdk.JavaMonitorEnter");
        JafraSummaryDocument.EventSummary wait    = event(events, "jdk.JavaMonitorWait");
        JafraSummaryDocument.EventSummary park    = event(events, "jdk.ThreadPark");
        JafraSummaryDocument.EventSummary nlock   = event(events, "profiler.NativeLock");
        JafraSummaryDocument.EventSummary deopt   = event(events, "jdk.Deoptimization");
        JafraSummaryDocument.EventSummary vmOp    = event(events, "jdk.ExecuteVMOperation");
        JafraSummaryDocument.EventSummary comp    = event(events, "jdk.CompilerStatistics");
        JafraSummaryDocument.EventSummary threads = event(events, "jdk.JavaThreadStatistics");
        JafraSummaryDocument.EventSummary err     = event(events, "jdk.JavaErrorThrow");
        JafraSummaryDocument.EventSummary cls     = event(events, "jdk.ClassLoadingStatistics");
        JafraSummaryDocument.EventSummary meta    = event(events, "jdk.MetaspaceSummary");

        McpSummary.SignalsBlock sig = new McpSummary.SignalsBlock();

        sig.cpuThrottledTimeNs  = metric(event(events, "jdk.ContainerCPUThrottling"), "cpuThrottledTime", 1.0);
        sig.cpuLoadMachinePct   = metric(event(events, "jdk.CPULoad"),                "machineTotal", 1.0);
        sig.threadCpuUserPct    = metric(event(events, "jdk.ThreadCPULoad"),           "user", 1.0);
        sig.contextSwitchRateHz = metric(event(events, "jdk.ThreadContextSwitchRate"), "switchRate", 1.0);

        // wall-clock
        McpSummary.WallClockStats wc = new McpSummary.WallClockStats();
        wc.count   = countOf(wall);
        wc.states  = values(wall, "state");
        wc.samples = metric(wall, "samples", 1.0);
        sig.wallClock = hasContent(wc) ? wc : null;

        // GC
        McpSummary.GcStats gcStats = new McpSummary.GcStats();
        gcStats.count          = countOf(gc);
        gcStats.pauseNs        = metric(gc, "duration",      ticksToNs);
        gcStats.sumOfPausesNs  = metric(gc, "sumOfPauses",   ticksToNs);
        gcStats.longestPauseNs = metric(gc, "longestPause",  ticksToNs);
        gcStats.collectors     = values(gc, "name");
        gcStats.causes         = values(gc, "cause");

        McpSummary.YoungGcStats youngStats = new McpSummary.YoungGcStats();
        youngStats.count           = countOf(young);
        youngStats.durationNs      = metric(young, "duration",          ticksToNs);
        youngStats.tenuringThreshold = metric(young, "tenuringThreshold", 1.0);
        gcStats.young = youngStats;

        McpSummary.OldGcStats oldStats = new McpSummary.OldGcStats();
        oldStats.count     = countOf(old);
        oldStats.durationNs = metric(old, "duration", ticksToNs);
        gcStats.old = oldStats;
        sig.gc = gcStats;

        // Heap
        McpSummary.HeapStats heap = new McpSummary.HeapStats();
        heap.usedB            = metric(event(events, "jdk.GCHeapMemoryUsage"), "used", 1.0);
        heap.committedB       = metric(event(events, "jdk.GCHeapMemoryUsage"), "committed", 1.0);
        heap.maxB             = metric(event(events, "jdk.GCHeapMemoryUsage"), "max", 1.0);
        heap.gcHeapUsedB      = metric(event(events, "jdk.GCHeapSummary"),    "heapUsed", 1.0);
        heap.rssB             = metric(event(events, "jdk.ResidentSetSize"),   "size", 1.0);
        heap.rssPeakB         = metric(event(events, "jdk.ResidentSetSize"),   "peak", 1.0);
        heap.containerMemoryB = metric(event(events, "jdk.ContainerMemoryUsage"), "memoryUsage", 1.0);
        heap.memoryFailCount  = firstValueAsNumber(event(events, "jdk.ContainerMemoryUsage"), "memoryFailCount");

        McpSummary.MetaspaceStats ms = new McpSummary.MetaspaceStats();
        ms.usedB        = metric(meta, "metaspace:used", 1.0);
        ms.committedB   = metric(meta, "metaspace:committed", 1.0);
        ms.gcThresholdB = metric(meta, "gcThreshold", 1.0);
        heap.metaspace  = ms;
        sig.heap = heap;

        // Allocations
        sig.allocTopClasses = cleanValues(event(events, "jdk.ObjectAllocationInNewTLAB"), "objectClass");
        sig.allocSizeB      = metric(event(events, "jdk.ObjectAllocationInNewTLAB"), "allocationSize", 1.0);
        sig.liveTopClasses  = cleanValues(event(events, "profiler.LiveObject"), "objectClass");
        sig.liveAllocSizeB  = metric(event(events, "profiler.LiveObject"), "allocationSize", 1.0);
        sig.metaspaceFailures = countOf(metaFail) > 0 ? countOf(metaFail) : null;

        // Locks
        McpSummary.LockStats locks = new McpSummary.LockStats();
        McpSummary.MonitorStats monEnter = new McpSummary.MonitorStats();
        monEnter.count      = countOf(enter);
        monEnter.classes    = values(enter, "monitorClass");
        monEnter.durationNs = metric(enter, "duration", ticksToNs);
        locks.monitorEnter  = monEnter;

        McpSummary.MonitorStats monWait = new McpSummary.MonitorStats();
        monWait.count      = countOf(wait);
        monWait.classes    = values(wait, "monitorClass");
        monWait.durationNs = metric(wait, "duration", ticksToNs);
        locks.monitorWait  = monWait;

        McpSummary.ParkStats parkStats = new McpSummary.ParkStats();
        parkStats.count      = countOf(park);
        parkStats.classes    = values(park, "parkedClass");
        parkStats.durationNs = metric(park, "duration", ticksToNs);
        locks.threadPark     = parkStats;

        McpSummary.NativeLockStats nlStats = new McpSummary.NativeLockStats();
        nlStats.count      = countOf(nlock);
        nlStats.durationNs = metric(nlock, "duration", ticksToNs);
        locks.nativeLock   = nlStats;
        sig.locks = locks;

        // Deoptimisation
        McpSummary.DeoptStats deoptStats = new McpSummary.DeoptStats();
        deoptStats.count     = countOf(deopt);
        deoptStats.reasons   = values(deopt, "reason");
        deoptStats.actions   = values(deopt, "action");
        deoptStats.compilers = values(deopt, "compiler");
        sig.deoptimization = deoptStats;

        // VM operations
        McpSummary.VmOpStats vmOpStats = new McpSummary.VmOpStats();
        vmOpStats.count      = countOf(vmOp);
        vmOpStats.operations = values(vmOp, "operation");
        vmOpStats.durationNs = metric(vmOp, "duration", ticksToNs);
        sig.vmOperations = vmOpStats;

        // Compiler
        McpSummary.CompilerStats compStats = new McpSummary.CompilerStats();
        compStats.compileCount    = metric(comp, "compileCount", 1.0);
        compStats.bailoutCount    = metric(comp, "bailoutCount", 1.0);
        compStats.totalTimeSpent  = metric(comp, "totalTimeSpent", 1.0);
        compStats.nmethodsSize    = metric(comp, "nmethodsSize", 1.0);
        sig.compiler = compStats;

        // Threads
        McpSummary.ThreadStats threadStats = new McpSummary.ThreadStats();
        threadStats.active  = metric(threads, "activeCount", 1.0);
        threadStats.daemon  = metric(threads, "daemonCount", 1.0);
        threadStats.peak    = metric(threads, "peakCount", 1.0);
        sig.threads = threadStats;

        // Errors
        McpSummary.ErrorStats errStats = new McpSummary.ErrorStats();
        errStats.count    = countOf(err);
        errStats.classes  = values(err, "thrownClass");
        errStats.messages = cappedValues(err, "message", 10);
        sig.errors = errStats;

        // Class loading
        McpSummary.ClassloadingStats clsStats = new McpSummary.ClassloadingStats();
        clsStats.count = countOf(cls);
        clsStats.loadedClassCountSamples   = values(cls, "loadedClassCount");
        clsStats.unloadedClassCountSamples = values(cls, "unloadedClassCount");
        sig.classloading = clsStats;

        // Metaspace failure detail — present only when failures were recorded
        if (countOf(metaFail) > 0) {
            McpSummary.MetaspaceFailureDetail mfd = new McpSummary.MetaspaceFailureDetail();
            mfd.classLoaders = values(metaFail, "classLoader");
            mfd.objectTypes  = values(metaFail, "metaspaceObjectType");
            mfd.sizeB        = metric(metaFail, "size", 1.0);
            sig.metaspaceFailureDetail = mfd;
        }

        return sig;
    }

    // ── Field helpers ───────────────────

    private static JafraSummaryDocument.EventSummary event(
            Map<String, JafraSummaryDocument.EventSummary> events, String name) {
        JafraSummaryDocument.EventSummary e = events.get(name);
        return e != null ? e : new JafraSummaryDocument.EventSummary();
    }

    private static long countOf(JafraSummaryDocument.EventSummary ev) {
        return ev != null ? ev.count : 0L;
    }

    private static JafraSummaryDocument.FieldStats fieldOf(
            JafraSummaryDocument.EventSummary ev, String fieldName) {
        if (ev == null || ev.fields == null) {
            return null;
        }
        return ev.fields.get(fieldName);
    }

    /**
     * Build a MetricStats from a numeric field, optionally scaling the ticks value.
     * Returns {@code null} if the field has no numeric data (so NON_NULL annotation omits it).
     */
    private static McpSummary.MetricStats metric(
            JafraSummaryDocument.EventSummary ev, String fieldName, double scale) {
        JafraSummaryDocument.FieldStats f = fieldOf(ev, fieldName);
        if (f == null) {
            return null;
        }
        boolean hasNumeric = f.min != null || f.max != null || f.avg != null || f.sum != null;
        if (!hasNumeric) {
            return null;
        }
        McpSummary.MetricStats m = new McpSummary.MetricStats();
        m.unit = f.unit;
        if (f.min != null) m.min = f.min.doubleValue() * scale;
        if (f.max != null) m.max = f.max.doubleValue() * scale;
        if (f.avg != null) m.avg = f.avg.doubleValue() * scale;
        if (f.sum != null) m.sum = f.sum.doubleValue() * scale;
        return m;
    }

    /** Collect the distinct text values from a field, filtering garbled JMC internal refs. */
    private static List<String> values(JafraSummaryDocument.EventSummary ev, String fieldName) {
        return cappedValues(ev, fieldName, Integer.MAX_VALUE);
    }

    private static List<String> cleanValues(JafraSummaryDocument.EventSummary ev, String fieldName) {
        return cappedValues(ev, fieldName, Integer.MAX_VALUE);
    }

    /**
     * Like {@link #values} but stops after {@code cap} entries.
     * Matches the Python helper's {@code values(ev, field, cap=N)} overload.
     */
    private static List<String> cappedValues(
            JafraSummaryDocument.EventSummary ev, String fieldName, int cap) {
        JafraSummaryDocument.FieldStats f = fieldOf(ev, fieldName);
        if (f == null || f.values == null) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>();
        for (String v : f.values) {
            if (out.size() >= cap) break;
            if (v != null && !v.contains("StructTypes$JfrMethod@")) {
                out.add(v);
            }
        }
        return out;
    }

    private static String firstValue(JafraSummaryDocument.EventSummary ev, String fieldName) {
        List<String> vals = values(ev, fieldName);
        return vals.isEmpty() ? null : vals.get(0);
    }

    private static Number firstValueAsNumber(JafraSummaryDocument.EventSummary ev, String fieldName) {
        String v = firstValue(ev, fieldName);
        if (v == null) return null;
        String trimmed = v.trim();
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException ignored) {
            try {
                return Double.parseDouble(trimmed);
            } catch (NumberFormatException ignored2) {
                return null;
            }
        }
    }

    private static Double numericAvg(JafraSummaryDocument.EventSummary ev, String fieldName) {
        JafraSummaryDocument.FieldStats f = fieldOf(ev, fieldName);
        if (f == null) return null;
        if (f.avg != null) return f.avg.doubleValue();
        if (f.min != null) return f.min.doubleValue();
        return null;
    }

    private static long firstLong(
            Map<String, JafraSummaryDocument.EventSummary> events,
            String eventName, String fieldName, long fallback) {
        JafraSummaryDocument.FieldStats f = fieldOf(event(events, eventName), fieldName);
        if (f == null) return fallback;
        if (f.avg != null) return f.avg.longValue();
        if (f.min != null) return f.min.longValue();
        if (f.values != null && !f.values.isEmpty()) {
            try {
                return Long.parseLong(f.values.get(0).trim());
            } catch (NumberFormatException ignored) { /* fall through */ }
        }
        return fallback;
    }

    /** Return true if the WallClockStats has anything worth serialising. */
    private static boolean hasContent(McpSummary.WallClockStats wc) {
        return wc.count > 0 || !wc.states.isEmpty() || wc.samples != null;
    }

    // ── Inner REST client interface ────────────────────────────────────────────

    /**
     * MicroProfile typed REST client for the Jafra Analyzer.
     * Base URL is configured via {@code quarkus.rest-client.jafra-analyzer.url}.
     */
    @RegisterRestClient(configKey = "jafra-analyzer")
    @Path("/api/v1")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public interface JafraAnalyzerApi {

        @GET
        @Path("/namespaces/{namespace}/pods/{pod}/containers/{container}/report")
        JafraSummaryReport report(
                @PathParam("namespace") String namespace,
                @PathParam("pod") String pod,
                @PathParam("container") String container,
                @QueryParam("last") String last);

        @GET
        @Path("/namespaces/{namespace}/pods/{pod}/containers/{container}/summary")
        JafraSummaryDocument summary(
                @PathParam("namespace") String namespace,
                @PathParam("pod") String pod,
                @PathParam("container") String container,
                @QueryParam("last") String last);
    }
}
