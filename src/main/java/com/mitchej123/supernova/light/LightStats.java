package com.mitchej123.supernova.light;

import com.mitchej123.supernova.Supernova;
import com.mitchej123.supernova.config.SupernovaConfig;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-world stats accumulator for light engine instrumentation. Dumps to {@code logs/supernova-stats.log} every {@value LOG_INTERVAL_TICKS} ticks.
 */
public final class LightStats {

    private static final int LOG_INTERVAL_TICKS = 20;
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss.SSS");

    private final String side;
    private final PrintWriter writer;
    final boolean enabled;

    private long tickCount;
    private long windowStartTick;

    // Worker stats -- written by sky and block worker threads concurrently
    final AtomicLong chunksProcessed = new AtomicLong();
    final AtomicLong initialLightsRun = new AtomicLong();
    final AtomicLong skyWorkerTimeNs = new AtomicLong();
    final AtomicLong blockWorkerTimeNs = new AtomicLong();
    final AtomicLong skyTasksProcessed = new AtomicLong();
    final AtomicLong blockTasksProcessed = new AtomicLong();
    final AtomicLong maxQueueLatencyNs = new AtomicLong();
    final AtomicLong totalQueueLatencyNs = new AtomicLong();

    // Backlog snapshots (main thread only)
    volatile int skyBacklog;
    volatile int blockBacklog;

    public static long engineRenderMarks;

    // Budget yield stats (multi-thread write)
    final AtomicInteger edgeBudgetYields = new AtomicInteger();
    final AtomicInteger blockChangeBudgetYields = new AtomicInteger();
    final AtomicInteger skyChangeBudgetYields = new AtomicInteger();

    // Queue stats (multi-thread write)
    final AtomicInteger chunksQueued = new AtomicInteger();

    // Block change diagnostics (block worker thread)
    public final AtomicLong blockPositionsProcessed = new AtomicLong();

    // Edge check diagnostics (multi-thread write, public for cross-package access from engine)
    public final AtomicLong edgeSectionPairsChecked = new AtomicLong();
    public final AtomicLong edgeSectionPairsSkippedFull = new AtomicLong();
    public final AtomicLong edgeSectionPairsSkippedZero = new AtomicLong();
    public final AtomicLong edgeBlocksTotal = new AtomicLong();
    public final AtomicLong edgeBlocksSkippedTrivial = new AtomicLong();
    public final AtomicLong edgeBlocksSkippedConsistency = new AtomicLong();
    public final AtomicLong edgeBlocksRecalculated = new AtomicLong();
    public final AtomicLong edgeBlocksMismatched = new AtomicLong();

    public LightStats(final boolean isClient) {
        this.side = isClient ? "CLIENT" : "SERVER";
        this.enabled = SupernovaConfig.enableStatsLog;
        PrintWriter pw = null;
        if (this.enabled) {
            try {
                final File logFile = new File("logs/supernova-stats.log");
                logFile.getParentFile().mkdirs();
                pw = new PrintWriter(new FileWriter(logFile, true), true);
            } catch (final IOException e) {
                Supernova.LOG.error("Failed to open supernova-stats.log", e);
            }
        }
        this.writer = pw;
    }

    public void tick(final int skyBacklog, final int blockBacklog) {
        if (!this.enabled) return;
        this.skyBacklog = skyBacklog;
        this.blockBacklog = blockBacklog;
        this.tickCount++;
        if (this.tickCount - this.windowStartTick >= LOG_INTERVAL_TICKS) {
            dump();
            reset();
        }
    }

    void recordQueueLatency(final long enqueueTimeNs) {
        final long latency = System.nanoTime() - enqueueTimeNs;
        this.maxQueueLatencyNs.accumulateAndGet(latency, Math::max);
        this.totalQueueLatencyNs.addAndGet(latency);
    }

    private void dump() {
        if (this.writer == null) return;

        final long processed = this.chunksProcessed.get();
        final int queued = this.chunksQueued.get();
        final StringBuilder sb = new StringBuilder(256);
        sb.append(this.timeFmt.format(new Date()));
        sb.append(" [").append(this.side).append(']');
        sb.append(" ticks=").append(this.windowStartTick).append('-').append(this.tickCount);
        sb.append(" queued=").append(queued);
        sb.append(" processed=").append(processed);
        sb.append(" initial=").append(this.initialLightsRun.get());
        sb.append(" skyMs=").append(String.format(Locale.US, "%.1f", this.skyWorkerTimeNs.get() / 1_000_000.0));
        sb.append(" blockMs=").append(String.format(Locale.US, "%.1f", this.blockWorkerTimeNs.get() / 1_000_000.0));
        sb.append(" skyTasks=").append(this.skyTasksProcessed.get());
        sb.append(" blockTasks=").append(this.blockTasksProcessed.get());
        sb.append(" blockPos=").append(this.blockPositionsProcessed.get());
        final int skyBl = this.skyBacklog;
        final int blockBl = this.blockBacklog;
        sb.append(" skyBacklog=").append(skyBl);
        sb.append(" blockBacklog=").append(blockBl);
        final int yields = this.edgeBudgetYields.get();
        if (yields > 0) {
            sb.append(" edgeBudgetYields=").append(yields);
        }
        final int blockYields = this.blockChangeBudgetYields.get();
        if (blockYields > 0) {
            sb.append(" blockChangeBudgetYields=").append(blockYields);
        }
        final int skyYields = this.skyChangeBudgetYields.get();
        if (skyYields > 0) {
            sb.append(" skyChangeBudgetYields=").append(skyYields);
        }

        final long totalLatency = this.totalQueueLatencyNs.get();
        if (processed > 0 && totalLatency > 0) {
            sb.append(" avgLatencyMs=").append(String.format(Locale.US, "%.1f", (totalLatency / (double) processed) / 1_000_000.0));
        }
        final long maxLatency = this.maxQueueLatencyNs.get();
        if (maxLatency > 0) {
            sb.append(" maxLatencyMs=").append(String.format(Locale.US, "%.1f", maxLatency / 1_000_000.0));
        }

        final long edgePairs = this.edgeSectionPairsChecked.get();
        if (edgePairs > 0) {
            sb.append(" edgeStats sectionPairs=").append(edgePairs);
            sb.append(" skippedFull=").append(this.edgeSectionPairsSkippedFull.get());
            sb.append(" skippedZero=").append(this.edgeSectionPairsSkippedZero.get());
            sb.append(" blocks=").append(this.edgeBlocksTotal.get());
            sb.append(" skippedTrivial=").append(this.edgeBlocksSkippedTrivial.get());
            sb.append(" skippedConsistency=").append(this.edgeBlocksSkippedConsistency.get());
            sb.append(" recalc=").append(this.edgeBlocksRecalculated.get());
            sb.append(" mismatched=").append(this.edgeBlocksMismatched.get());
        }

        if ("CLIENT".equals(this.side)) {
            sb.append(" engineMarks=").append(engineRenderMarks);
        }

        this.writer.println(sb);
    }

    private void reset() {
        this.windowStartTick = this.tickCount;
        this.chunksProcessed.set(0);
        this.initialLightsRun.set(0);
        this.skyWorkerTimeNs.set(0);
        this.blockWorkerTimeNs.set(0);
        this.skyTasksProcessed.set(0);
        this.blockTasksProcessed.set(0);
        this.maxQueueLatencyNs.set(0);
        this.totalQueueLatencyNs.set(0);
        engineRenderMarks = 0;
        this.edgeBudgetYields.set(0);
        this.blockChangeBudgetYields.set(0);
        this.skyChangeBudgetYields.set(0);
        this.chunksQueued.set(0);
        this.blockPositionsProcessed.set(0);
        this.edgeSectionPairsChecked.set(0);
        this.edgeSectionPairsSkippedFull.set(0);
        this.edgeSectionPairsSkippedZero.set(0);
        this.edgeBlocksTotal.set(0);
        this.edgeBlocksSkippedTrivial.set(0);
        this.edgeBlocksSkippedConsistency.set(0);
        this.edgeBlocksRecalculated.set(0);
        this.edgeBlocksMismatched.set(0);
    }

    public void close() {
        if (this.writer != null) {
            this.writer.close();
        }
    }
}
