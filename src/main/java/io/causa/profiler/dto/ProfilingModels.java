package io.causa.profiler.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Shared DTOs that form the contract between the MCP tool layer and
 * the Jafra Analyzer backend.
 *
 * Schema version: 1.0
 *
 * The Jafra Analyzer exposes two key endpoints consumed by this MCP server:
 *   GET /api/v1/namespaces/{ns}/pods/{pod}/containers/{container}/report?last=5m
 *       → JafraSummaryReport  (JMC rule-based findings)
 *
 *   GET /api/v1/namespaces/{ns}/pods/{pod}/containers/{container}/summary?last=5m
 *       → JafraSummaryDocument  (raw JFR event statistics)
 *       → transformed into McpSummary before returning to the LLM
 */
public final class ProfilingModels {
    private ProfilingModels() {}

    // ──────────────────────────────────────────────
    // Recording lifecycle enums
    // ──────────────────────────────────────────────

    public enum RecordingStatus {
        /** Waiting for a profiler slot. */
        QUEUED,
        /** async-profiler is actively recording. */
        RECORDING,
        /** JFR file is being copied out of the pod. */
        DOWNLOADING,
        /** JFR file is being parsed and analysed. */
        ANALYSING,
        /** Analysis is complete; report is available. */
        READY,
        /** Report has been retrieved at least once. */
        DELIVERED,
        /** Exceeded the retention window. */
        EXPIRED,
        /** Something went wrong. */
        ERROR
    }

    public enum TerminationReason {
        /** Causa called stop_recording(). */
        USER_REQUESTED,
        /** Hit the configured duration limit (default 5 min). */
        DURATION_EXPIRED,
        /** The target container ran out of memory. */
        CONTAINER_OOM,
        /** The pod was deleted or evicted. */
        POD_DELETED,
        /** Recording or download failed. */
        ERROR
    }

    // ──────────────────────────────────────────────
    // Pod discovery
    // ──────────────────────────────────────────────

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ProfiledPodMetadata {
        public String podName;
        public String namespace;
        /** One of: READY, NOT_READY, UNKNOWN */
        public String profilerStatus;
        public String jvmVersion;
        public String profilerVersion;
        public Instant lastProfiledAt;
        public String latestRecordingId;

        public ProfiledPodMetadata() {}
    }

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PodJvmStatus {
        public String podName;
        /** One of: READY, NOT_READY, UNKNOWN */
        public String profilerStatus;
        public JvmHealth jvmHealth;
        public RecordingMetadata latestRecording;
        public String message;

        @RegisterForReflection
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class JvmHealth {
            public boolean isHealthy;
            public String jvmUptime;
            public int activeThreadCount;
            public Instant lastHeartbeat;
        }
    }

    // ──────────────────────────────────────────────
    // Recording lifecycle
    // ──────────────────────────────────────────────

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class RecordingMetadata {
        public String recordingId;
        public String podName;
        public RecordingStatus status;
        /** Populated only after recording ends; null while still QUEUED/RECORDING. */
        public TerminationReason terminationReason;
        public Instant createdAt;
        /** Null while the recording is still active (QUEUED / RECORDING). */
        public Instant terminatedAt;
        public long durationSeconds;
        /** Null until the JFR file is fully downloaded; zero-valued fields are hidden via NON_NULL. */
        public Long sizeBytes;
        /** e.g. CPU, MEMORY, LOCK, ALLOCATION, ALL */
        public String profileType;
        /** When this record expires from the controller's store. */
        public Instant expiresAt;
    }

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class StartRecordingRequest {
        /** Profiling duration in seconds.  0 means "run until stop is called". */
        public int durationSeconds = 300;
        /** CPU | MEMORY | LOCK | ALLOCATION | ALL */
        public String profileType = "CPU";
    }

    // ──────────────────────────────────────────────
    // Jafra Analyzer — GET /api/v1/recordings response
    // Lists all workloads that have JFR recordings in the analyzer.
    // ──────────────────────────────────────────────

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class RecordingsListResponse {
        /** All workloads with at least one JFR recording stored in the analyzer. */
        public List<WorkloadRecordings> workloads;
    }

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class WorkloadRecordings {
        public String namespace;
        public String pod;
        public String container;
        /** Summary of individual JFR files stored for this workload. */
        public List<RecordingFile> recordings;

        @RegisterForReflection
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class RecordingFile {
            public String filename;
            public int    sequence;
            public long   bytes;
            public int    chunks;
            public String start;
            public String end;
            public String reportUrl;
        }
    }

    // ──────────────────────────────────────────────
    // Jafra Analyzer — /report response
    // JMC rule-based analysis findings keyed by rule ID.
    // Matches JfrReportGenerator.SummaryReport from jafra-analyzer.
    // ──────────────────────────────────────────────

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class JafraSummaryReport {
        public String namespace;
        public String pod;
        public String container;
        public String recording;
        public List<String> recordings;
        public String start;
        public String end;
        public String from;
        public String to;
        public long bytes;
        public int chunks;
        public String generatedAt;
        /** Rule ID → finding. */
        public Map<String, AnalysisFinding> findings;

        @RegisterForReflection
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class AnalysisFinding {
            public double score;
            public String name;
            public String topic;
            public String description;
        }
    }

    // ──────────────────────────────────────────────
    // Jafra Analyzer — /summary response
    // Raw JFR event statistics, one entry per JDK event type.
    // Matches JfrEventSummarizer.EventSummaryDocument from jafra-analyzer.
    // ──────────────────────────────────────────────

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class JafraSummaryDocument {
        public String namespace;
        public String pod;
        public String container;
        public String recording;
        public List<String> recordings;
        public String start;
        public String end;
        public String from;
        public String to;
        public long bytes;
        public int chunks;
        public String generatedAt;
        /** JDK event type ID → per-event summary. */
        public Map<String, EventSummary> events;
        /** Topic → cross-event aggregate. */
        public Map<String, TopicSummary> topics;

        @RegisterForReflection
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class EventSummary {
            public String name;
            public String topic;
            public long count;
            public Map<String, FieldStats> fields;
        }

        @RegisterForReflection
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class FieldStats {
            public String name;
            public String unit;
            public Number min;
            public Number max;
            public Number avg;
            public Number sum;
            public String minDisplay;
            public String maxDisplay;
            public String avgDisplay;
            public String sumDisplay;
            public List<String> values;
        }

        @RegisterForReflection
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class TopicSummary {
            public long eventCount;
            public List<String> eventTypes;
            public Map<String, ValueStats> stats;
        }

        @RegisterForReflection
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class ValueStats {
            public String name;
            public String unit;
            public Number value;
            public String display;
            public String text;
        }
    }

    // ──────────────────────────────────────────────
    // MCP-friendly summary — output of get_jfr_summary tool.
    // ──────────────────────────────────────────────

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class McpSummary {
        public ScopeBlock scope;
        public EnvironmentBlock environment;
        public SignalsBlock signals;

        @RegisterForReflection
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class ScopeBlock {
            public String namespace;
            public String pod;
            public String container;
            public WindowBlock window;
            public long bytes;
            public int chunks;
            public String generatedAt;
            public List<String> recordings;
        }

        @RegisterForReflection
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class WindowBlock {
            public String start;
            public String end;
            public String from;
            public String to;
        }

        @RegisterForReflection
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class EnvironmentBlock {
            public JvmInfo jvm;
            public GcInfo gc;
            @JsonProperty("heap_min_b") public Double heapMinB;
            @JsonProperty("heap_max_b") public Double heapMaxB;
            @JsonProperty("heap_initial_b") public Double heapInitialB;
            public ContainerInfo container;
            @JsonProperty("tick_hz") public Long tickHz;
        }

        @RegisterForReflection
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class JvmInfo {
            public String jvmName;
            public String jvmVersion;
            public String jvmArguments;
            public String javaArguments;
            public String pid;
        }

        @RegisterForReflection
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class GcInfo {
            public String young;
            public String old;
            public Double parallelGCThreads;
            public Double gcTimeRatio;
        }

        @RegisterForReflection
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class ContainerInfo {
            public String containerType;
            public Number cpus;
            @JsonProperty("cpu_quota") public Number cpuQuota;
            @JsonProperty("cpu_period") public Number cpuPeriod;
            @JsonProperty("cpu_shares") public Number cpuShares;
            @JsonProperty("memory_limit_b") public Double memoryLimitB;
            @JsonProperty("swap_memory_limit_b") public Double swapMemoryLimitB;
        }

        @RegisterForReflection
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class SignalsBlock {
            @JsonProperty("cpu_throttled_time_ns") public MetricStats cpuThrottledTimeNs;
            @JsonProperty("cpu_load_machine_pct") public MetricStats cpuLoadMachinePct;
            @JsonProperty("thread_cpu_user_pct") public MetricStats threadCpuUserPct;
            @JsonProperty("context_switch_rate_hz") public MetricStats contextSwitchRateHz;
            @JsonProperty("wall_clock") public WallClockStats wallClock;
            public GcStats gc;
            public HeapStats heap;
            @JsonProperty("alloc_top_classes") public List<String> allocTopClasses;
            @JsonProperty("alloc_size_b") public MetricStats allocSizeB;
            @JsonProperty("live_top_classes") public List<String> liveTopClasses;
            @JsonProperty("live_alloc_size_b") public MetricStats liveAllocSizeB;
            @JsonProperty("metaspace_failures") public Long metaspaceFailures;
            public LockStats locks;
            public DeoptStats deoptimization;
            @JsonProperty("vm_operations") public VmOpStats vmOperations;
            public CompilerStats compiler;
            public ThreadStats threads;
            public ErrorStats errors;
            public ClassloadingStats classloading;
            /** Non-null only when metaspace allocation failures were observed. */
            @JsonProperty("metaspace_failure_detail") public MetaspaceFailureDetail metaspaceFailureDetail;
        }

        @RegisterForReflection
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class MetricStats {
            public String unit;
            public Double min;
            public Double max;
            public Double avg;
            public Double sum;
        }

        @RegisterForReflection
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class WallClockStats {
            public long count;
            public List<String> states;
            public MetricStats samples;
        }

        @RegisterForReflection
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class GcStats {
            public long count;
            @JsonProperty("pause_ns") public MetricStats pauseNs;
            @JsonProperty("sum_of_pauses_ns") public MetricStats sumOfPausesNs;
            @JsonProperty("longest_pause_ns") public MetricStats longestPauseNs;
            public List<String> collectors;
            public List<String> causes;
            public YoungGcStats young;
            public OldGcStats old;
        }

        @RegisterForReflection
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class YoungGcStats {
            public long count;
            @JsonProperty("duration_ns") public MetricStats durationNs;
            @JsonProperty("tenuring_threshold") public MetricStats tenuringThreshold;
        }

        @RegisterForReflection
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class OldGcStats {
            public long count;
            @JsonProperty("duration_ns") public MetricStats durationNs;
        }

        @RegisterForReflection
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class HeapStats {
            @JsonProperty("used_b") public MetricStats usedB;
            @JsonProperty("committed_b") public MetricStats committedB;
            @JsonProperty("max_b") public MetricStats maxB;
            @JsonProperty("gc_heap_used_b") public MetricStats gcHeapUsedB;
            @JsonProperty("rss_b") public MetricStats rssB;
            @JsonProperty("rss_peak_b") public MetricStats rssPeakB;
            @JsonProperty("container_memory_b") public MetricStats containerMemoryB;
            @JsonProperty("memory_fail_count") public Number memoryFailCount;
            public MetaspaceStats metaspace;
        }

        @RegisterForReflection
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class MetaspaceStats {
            @JsonProperty("used_b") public MetricStats usedB;
            @JsonProperty("committed_b") public MetricStats committedB;
            @JsonProperty("gc_threshold_b") public MetricStats gcThresholdB;
        }

        @RegisterForReflection
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class LockStats {
            @JsonProperty("monitor_enter") public MonitorStats monitorEnter;
            @JsonProperty("monitor_wait") public MonitorStats monitorWait;
            @JsonProperty("thread_park") public ParkStats threadPark;
            @JsonProperty("native_lock") public NativeLockStats nativeLock;
        }

        @RegisterForReflection
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class MonitorStats {
            public long count;
            public List<String> classes;
            @JsonProperty("duration_ns") public MetricStats durationNs;
        }

        @RegisterForReflection
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class ParkStats {
            public long count;
            public List<String> classes;
            @JsonProperty("duration_ns") public MetricStats durationNs;
        }

        @RegisterForReflection
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class NativeLockStats {
            public long count;
            @JsonProperty("duration_ns") public MetricStats durationNs;
        }

        @RegisterForReflection
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class DeoptStats {
            public long count;
            public List<String> reasons;
            public List<String> actions;
            public List<String> compilers;
        }

        @RegisterForReflection
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class VmOpStats {
            public long count;
            public List<String> operations;
            @JsonProperty("duration_ns") public MetricStats durationNs;
        }

        @RegisterForReflection
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class CompilerStats {
            @JsonProperty("compile_count") public MetricStats compileCount;
            @JsonProperty("bailout_count") public MetricStats bailoutCount;
            @JsonProperty("total_time_spent") public MetricStats totalTimeSpent;
            @JsonProperty("nmethods_size") public MetricStats nmethodsSize;
        }

        @RegisterForReflection
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class ThreadStats {
            public MetricStats active;
            public MetricStats daemon;
            public MetricStats peak;
        }

        @RegisterForReflection
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class ErrorStats {
            public long count;
            public List<String> classes;
            /** Capped at 10 entries to keep payload size manageable. */
            public List<String> messages;
        }

        @RegisterForReflection
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class ClassloadingStats {
            public long count;
            @JsonProperty("loadedClassCount_samples") public List<String> loadedClassCountSamples;
            @JsonProperty("unloadedClassCount_samples") public List<String> unloadedClassCountSamples;
        }

        /**
         * Present only when metaspace allocation failures were recorded.
         */
        @RegisterForReflection
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class MetaspaceFailureDetail {
            @JsonProperty("classLoaders") public List<String> classLoaders;
            @JsonProperty("objectTypes")  public List<String> objectTypes;
            @JsonProperty("size_b")       public MetricStats sizeB;
        }
    }

    // ──────────────────────────────────────────────
    // Live JVM stats (no recording needed)
    // ──────────────────────────────────────────────

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class JvmStatistics {
        public String podName;
        public Instant capturedAt;
        public long heapUsedBytes;
        public long heapMaxBytes;
        public long nonHeapUsedBytes;
        public double heapUsagePercent;
        public int threadCount;
        public int peakThreadCount;
        public long gcCollectionCount;
        public long gcCollectionTimeMs;
        public int loadedClassCount;
    }

    // ──────────────────────────────────────────────
    // Error envelope
    // ──────────────────────────────────────────────

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ProfilingError {
        public String errorCode;
        public String message;
        public String details;
        public Instant timestamp = Instant.now();

        public ProfilingError() {}

        public ProfilingError(String errorCode, String message) {
            this.errorCode = errorCode;
            this.message = message;
        }

        public ProfilingError(String errorCode, String message, String details) {
            this(errorCode, message);
            this.details = details;
        }
    }
}
