package com.mitchej123.supernova.light;

import com.google.common.util.concurrent.SettableFuture;
import com.mitchej123.supernova.Supernova;
import com.mitchej123.supernova.config.SupernovaConfig;
import com.mitchej123.supernova.light.engine.ScalarBlockEngine;
import com.mitchej123.supernova.light.engine.ScalarSkyEngine;
import com.mitchej123.supernova.light.engine.SupernovaBlockEngine;
import com.mitchej123.supernova.light.engine.SupernovaEngine;
import com.mitchej123.supernova.light.engine.SupernovaSkyEngine;
import com.gtnewhorizon.gtnhlib.util.ServerThreadUtil;
import com.mitchej123.supernova.util.CoordinateUtils;
import com.mitchej123.supernova.util.SnapshotChunkMap;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.play.server.S21PacketChunkData;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.function.LongSupplier;

/**
 * Per-World light manager managing Supernova's light engine pools.
 */
public final class WorldLightManager {

    private final World world;
    private final boolean hasSkyLight;
    private final boolean hasBlockLight;

    private final ConcurrentLinkedDeque<SupernovaEngine> cachedSkyPropagators;
    private final ConcurrentLinkedDeque<SupernovaEngine> cachedBlockPropagators;
    private final Supplier<SupernovaEngine> skyEngineFactory;
    private final Supplier<SupernovaEngine> blockEngineFactory;

    private final SnapshotChunkMap loadedChunkMap = new SnapshotChunkMap();
    private final DynamicEmissionSnapshots dynamicEmission;

    private final LightQueue skyQueue;
    private final LightQueue blockQueue;
    private final Thread skyWorkerThread;
    private final Thread blockWorkerThread;
    private volatile boolean running = true;

    private final LightStats stats;

    private final InitialLightCoordinator initialLighting;

    private final Lane skyLane;
    private final Lane blockLane;

    private static final int MAX_RELIGHT_ATTEMPTS = 2;
    private static final long SLOW_TASK_NS = 100_000_000L;
    private static final long UNLOAD_WAIT_NS = 10_000_000L;
    private static final long SERVER_BATCH_BUDGET_NS = 10_000_000L;
    private static final long CLIENT_TICK_BUDGET_NS = 2_000_000L;
    private boolean clientStartsWithSky = true;

    public WorldLightManager(final World world, final boolean hasSkyLight, final boolean hasBlockLight) {
        this.world = world;
        this.hasSkyLight = hasSkyLight;
        this.hasBlockLight = hasBlockLight;
        this.dynamicEmission = new DynamicEmissionSnapshots(world);
        this.dynamicEmission.setManager(this);
        this.cachedSkyPropagators = hasSkyLight ? new ConcurrentLinkedDeque<>() : null;
        this.cachedBlockPropagators = hasBlockLight ? new ConcurrentLinkedDeque<>() : null;

        this.skyEngineFactory = hasSkyLight ? (SupernovaConfig.isScalarMode() ? () -> new ScalarSkyEngine(world) : () -> new SupernovaSkyEngine(world, this.loadedChunkMap)) : null;
        this.blockEngineFactory = hasBlockLight ? (SupernovaConfig.isScalarMode() ? () -> new ScalarBlockEngine(world, this.dynamicEmission) : () -> new SupernovaBlockEngine(world, this.loadedChunkMap, this.dynamicEmission)) : null;

        this.skyQueue = hasSkyLight ? new LightQueue() : null;
        this.blockQueue = hasBlockLight ? new LightQueue() : null;
        this.stats = new LightStats(world.isRemote);
        if (this.skyQueue != null) this.skyQueue.setStats(this.stats);
        if (this.blockQueue != null) this.blockQueue.setStats(this.stats);
        this.initialLighting = new InitialLightCoordinator(this.skyQueue, this.blockQueue, new ChunkLightPublisher(), this.loadedChunkMap::get);

        this.skyLane = hasSkyLight ? new Lane("Sky", InitialLightCoordinator.SKY, this.skyQueue, this.cachedSkyPropagators, this.skyEngineFactory,
            this.stats.skyChangeBudgetYields, this.stats.skyWorkerTimeNs, this.stats.skyTasksProcessed) : null;
        this.blockLane = hasBlockLight ? new Lane("Block", InitialLightCoordinator.BLOCK, this.blockQueue, this.cachedBlockPropagators, this.blockEngineFactory,
            this.stats.blockChangeBudgetYields, this.stats.blockWorkerTimeNs, this.stats.blockTasksProcessed) : null;

        if (this.cachedSkyPropagators != null) this.cachedSkyPropagators.addFirst(this.skyEngineFactory.get());
        if (this.cachedBlockPropagators != null) this.cachedBlockPropagators.addFirst(this.blockEngineFactory.get());

        // Client drains both lanes on the main thread before the frame renders; a worker there would race the render and the sync path.
        final boolean useWorkers = !world.isRemote;
        this.skyWorkerThread = hasSkyLight && useWorkers ? startWorker(this.skyLane, "Supernova-Sky") : null;
        this.blockWorkerThread = hasBlockLight && useWorkers ? startWorker(this.blockLane, "Supernova-Block") : null;
    }

    private Thread startWorker(final Lane lane, final String name) {
        final Thread thread = new Thread(() -> runLane(lane), name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private void runLane(final Lane lane) {
        while (this.running) {
            try {
                if (lane.queue.isEmpty()) lane.queue.waitForWork();
                this.propagate(lane);
            } catch (final InterruptedException e) {
                Supernova.LOG.info("{} light worker interrupted -- exiting", lane.name);
                Thread.currentThread().interrupt();
                break;
            } catch (final Throwable t) {
                Supernova.LOG.error("{} light worker survived an uncaught throwable", lane.name, t);
            }
        }
    }

    public void registerChunk(final Chunk chunk) {
        this.dynamicEmission.registerChunk(chunk);
        this.loadedChunkMap.put(CoordinateUtils.getChunkKey(chunk.xPosition, chunk.zPosition), chunk);
    }

    public void unregisterChunk(final int cx, final int cz) {
        this.loadedChunkMap.remove(CoordinateUtils.getChunkKey(cx, cz));
        this.dynamicEmission.unregisterChunk(cx, cz);
    }

    /** Seam light is final only once no loaded neighbor is mid-relight, and 1.7.10 cannot correct a chunk once sent. */
    public boolean isReadyToSend(final int cx, final int cz) {
        if (!isChunkLightReady(cx, cz)) return false;
        return isNeighborSettled(cx - 1, cz) && isNeighborSettled(cx + 1, cz)
            && isNeighborSettled(cx, cz - 1) && isNeighborSettled(cx, cz + 1);
    }

    private boolean isChunkLightReady(final int cx, final int cz) {
        final Chunk chunk = this.loadedChunkMap.get(CoordinateUtils.getChunkKey(cx, cz));
        return chunk != null && ((SupernovaChunk) chunk).isLightReady();
    }

    /** An unloaded neighbor never blocks: the outer view ring always has one, and a late arrival queues edge checks on both sides. */
    private boolean isNeighborSettled(final int cx, final int cz) {
        final Chunk chunk = this.loadedChunkMap.get(CoordinateUtils.getChunkKey(cx, cz));
        return chunk == null || ((SupernovaChunk) chunk).isLightReady();
    }

    public Chunk getLoadedChunk(final int chunkX, final int chunkZ) {
        return this.loadedChunkMap.get(CoordinateUtils.getChunkKey(chunkX, chunkZ));
    }

    private static SupernovaEngine getEngine(final ConcurrentLinkedDeque<SupernovaEngine> cache,
            final Supplier<SupernovaEngine> factory) {
        if (cache == null) return null;
        final SupernovaEngine ret = cache.pollFirst();
        return ret != null ? ret : factory.get();
    }

    private static void releaseEngine(final ConcurrentLinkedDeque<SupernovaEngine> cache, final SupernovaEngine engine) {
        if (cache == null || engine == null) return;
        cache.addFirst(engine);
    }

    public void queueBlockChange(final int x, final int y, final int z) {
        this.dynamicEmission.refreshPosition(x, y, z);
        if (this.skyQueue != null) this.skyQueue.queueBlockChange(x, y, z);
        if (this.blockQueue != null) this.blockQueue.queueBlockChange(x, y, z);
    }

    public void tileEntityChanged(final int x, final int y, final int z) {
        this.dynamicEmission.tileEntityChanged(x, y, z);
    }

    public void refreshDynamicEmission() {
        this.dynamicEmission.tick();
    }

    /** A section appeared or vanished: the emptiness map and any nibble that depends on it have to be recomputed. */
    public void queueSectionChange(final int cx, final int sectionY, final int cz, final boolean empty) {
        if (this.skyQueue != null) this.skyQueue.queueSectionChange(cx, sectionY, cz, empty);
        if (this.blockQueue != null) this.blockQueue.queueSectionChange(cx, sectionY, cz, empty);
    }

    public void queueEdgeChecks(final int cx, final int cz) {
        if (this.skyQueue != null) this.skyQueue.queueEdgeCheckAllSections(cx, cz);
        if (this.blockQueue != null) this.blockQueue.queueEdgeCheckAllSections(cx, cz);
    }

    public void queueChunkLight(final int cx, final int cz, final Chunk chunk, final Boolean[] emptySections) {
        this.initialLighting.queue(cx, cz, chunk, emptySections);
    }

    public void removeChunkFromQueues(final int cx, final int cz) {
        this.initialLighting.removeChunk(cx, cz);
    }

    public boolean hasUpdates() {
        return (this.skyQueue != null && !this.skyQueue.isEmpty()) || (this.blockQueue != null && !this.blockQueue.isEmpty());
    }

    /** Backs {@link com.mitchej123.supernova.api.ExtendedWorld#supernova$hasChunkPendingLight}. */
    public boolean hasChunkPendingLight(final int cx, final int cz) {
        return (this.skyQueue != null && this.skyQueue.hasPendingWork(cx, cz)) || (this.blockQueue != null && this.blockQueue.hasPendingWork(cx, cz));
    }

    /** A task may write light in its own chunk or any directly adjacent chunk. */
    public boolean hasUnsettledLightValues(final int cx, final int cz) {
        return pendingWorkFuture(cx, cz) != null;
    }

    public void drainClientLight() {
        drainClientLight(System::nanoTime);
    }

    void drainClientLight(final LongSupplier nanoTime) {
        if (!this.hasUpdates()) return;
        final long deadline = nanoTime.getAsLong() + CLIENT_TICK_BUDGET_NS;
        boolean skyFirst = this.clientStartsWithSky;
        this.clientStartsWithSky = !skyFirst;
        do {
            final Lane first = skyFirst ? this.skyLane : this.blockLane;
            final Lane second = skyFirst ? this.blockLane : this.skyLane;
            final boolean processed = runClientTask(first) || runClientTask(second);
            if (!processed) break;
            skyFirst = !skyFirst;
        } while (nanoTime.getAsLong() < deadline);
        this.tick();
    }

    private boolean runClientTask(final Lane lane) {
        if (lane == null || lane.queue.isEmpty()) return false;
        final SupernovaEngine engine = getEngine(lane.cache, lane.factory);
        if (engine == null) return false;
        try {
            return runNextTask(lane, engine) != null;
        } finally {
            releaseEngine(lane.cache, engine);
        }
    }

    public void tick() {
        if (!this.stats.enabled) return;
        final int skySize = this.skyQueue != null ? this.skyQueue.size() : 0;
        final int blockSize = this.blockQueue != null ? this.blockQueue.size() : 0;
        this.stats.tick(skySize, blockSize);
    }

    private static final class Lane {

        final String name;
        final int mask;
        final LightQueue queue;
        final ConcurrentLinkedDeque<SupernovaEngine> cache;
        final Supplier<SupernovaEngine> factory;
        final AtomicInteger changeBudgetYields;
        final AtomicLong workerTimeNs;
        final AtomicLong tasksProcessed;

        Lane(final String name, final int mask, final LightQueue queue, final ConcurrentLinkedDeque<SupernovaEngine> cache,
            final Supplier<SupernovaEngine> factory, final AtomicInteger changeBudgetYields, final AtomicLong workerTimeNs, final AtomicLong tasksProcessed) {
            this.name = name;
            this.mask = mask;
            this.queue = queue;
            this.cache = cache;
            this.factory = factory;
            this.changeBudgetYields = changeBudgetYields;
            this.workerTimeNs = workerTimeNs;
            this.tasksProcessed = tasksProcessed;
        }
    }

    private void propagate(final Lane lane) {
        final SupernovaEngine engine = getEngine(lane.cache, lane.factory);
        if (engine == null) return;
        final long deadline = System.nanoTime() + SERVER_BATCH_BUDGET_NS;
        try {
            ChunkTasks task;
            while ((task = runNextTask(lane, engine)) != null) {
                if (System.nanoTime() >= deadline) {
                    if (this.stats.enabled) {
                        if (task.hasBlockChanges()) lane.changeBudgetYields.incrementAndGet();
                        else this.stats.edgeBudgetYields.incrementAndGet();
                    }
                    break;
                }
            }
        } catch (final Throwable t) {
            Supernova.LOG.error("Throwable draining the {} light lane", lane.name, t);
        } finally {
            releaseEngine(lane.cache, engine);
        }
    }

    private ChunkTasks runNextTask(final Lane lane, final SupernovaEngine engine) {
        ChunkTasks task = lane.queue.removeFirstBlockChangeTask();
        if (task == null) task = lane.queue.removeFirstInitialLightTask();
        if (task == null) task = lane.queue.removeFirstTask();
        if (task != null) runTask(lane, task, engine);
        return task;
    }

    private void runTask(final Lane lane, final ChunkTasks task, final SupernovaEngine engine) {
        try {
            processTask(lane, task, engine);
        } finally {
            lane.queue.completeTask(task);
        }
    }

    private void processTask(final Lane lane, final ChunkTasks task, final SupernovaEngine engine) {
        final boolean stats = this.stats.enabled;
        final long t0 = System.nanoTime();
        final int cx = CoordinateUtils.getChunkX(task.chunkCoordinate);
        final int cz = CoordinateUtils.getChunkZ(task.chunkCoordinate);

        long changesNs = 0, edgesNs = 0;
        int changesPos = 0, changesBfsInc = 0, changesBfsDec = 0;
        int edgeSec = 0, edgeBfsInc = 0, edgeBfsDec = 0;

        boolean finishPropagation = task.initialLightChunk != null;
        boolean finishEdges = task.edgePass && task.queuedEdgeChecks != null;

        try {
            final Chunk loaded = this.loadedChunkMap.get(task.chunkCoordinate);
            if (loaded == null) return;
            if (task.initialLightChunk != null && task.initialLightChunk != loaded) {
                // Chunk object replaced while queued.
                Supernova.LOG.debug("{} task for chunk ({}, {}) dropped -- chunk object replaced while queued", lane.name, cx, cz);
                return;
            }

            if (stats) {
                this.stats.chunksProcessed.incrementAndGet();
                this.stats.recordQueueLatency(task.enqueueTimeNs);
                engine.setStats(this.stats);
            }

            boolean valueOverflow = false;
            // checkEdges=false: neighbor light still seeds via propagateNeighbourLevels; seams wait for their own pass.
            if (task.initialLightChunk != null) {
                // Exactly one lane counts, or a world with both would double-count.
                if (stats && (lane.mask == InitialLightCoordinator.SKY || this.skyQueue == null)) this.stats.initialLightsRun.incrementAndGet();
                engine.light(task.initialLightChunk, task.initialLightEmptySections, false);
                valueOverflow = engine.wasQueueOverflowed();
            }

            if (task.changedSectionSet != null || (task.changedPositions != null && !task.changedPositions.isEmpty())) {
                final long t1 = System.nanoTime();
                engine.blocksChangedInChunk(cx, cz, task.changedPositions, task.changedSectionSet);
                changesNs = System.nanoTime() - t1;
                changesPos = engine.lastPositionsProcessed;
                changesBfsInc = engine.lastBfsIncreaseTotal;
                changesBfsDec = engine.lastBfsDecreaseTotal;
                if (stats && lane.mask == InitialLightCoordinator.BLOCK) this.stats.blockPositionsProcessed.addAndGet(changesPos);
                valueOverflow |= engine.wasQueueOverflowed();
            }

            if (task.queuedEdgeChecks != null) {
                engine.lastBfsIncreaseTotal = 0;
                engine.lastBfsDecreaseTotal = 0;
                edgeSec = task.queuedEdgeChecks.size();
                final long t2 = System.nanoTime();
                engine.checkChunkEdges(cx, cz, task.queuedEdgeChecks);
                edgesNs = System.nanoTime() - t2;
                edgeBfsInc = engine.lastBfsIncreaseTotal;
                edgeBfsDec = engine.lastBfsDecreaseTotal;
                if (engine.wasQueueOverflowed() && requeueEdgesAfterFailure(lane, task, cx, cz)) {
                    finishEdges = false;
                }
            }

            if (valueOverflow && requeueAfterFailure(lane, task, cx, cz)) {
                finishPropagation = false;
            }
        } catch (final Throwable t) {
            if (this.loadedChunkMap.get(task.chunkCoordinate) != null) {
                Supernova.LOG.error("Error processing {} task for chunk ({}, {})", lane.name, cx, cz, t);
            } else {
                Supernova.LOG.warn("{} task for chunk ({}, {}) aborted -- chunk unloaded during processing", lane.name, cx, cz);
            }
            // Retry rather than complete, or the generation publishes half-lit.
            if (finishPropagation && requeueAfterFailure(lane, task, cx, cz)) finishPropagation = false;
            if (finishEdges && requeueEdgesAfterFailure(lane, task, cx, cz)) finishEdges = false;
        } finally {
            if (finishPropagation) this.initialLighting.completePropagation(task, lane.mask);
            if (finishEdges) this.initialLighting.completeEdges(task, lane.mask);
        }

        final long totalNs = System.nanoTime() - t0;
        if (stats) {
            engine.setStats(null);
            lane.workerTimeNs.addAndGet(totalNs);
            lane.tasksProcessed.incrementAndGet();
        }

        if (totalNs > SLOW_TASK_NS) {
            Supernova.LOG.warn(
                    "Slow {} task: chunk ({},{}) total={}ms changes={}ms ({}pos, bfsInc={} bfsDec={}) edges={}ms ({}sec, bfsInc={} bfsDec={})",
                    lane.name, cx, cz, totalNs / 1_000_000L, changesNs / 1_000_000L, changesPos, changesBfsInc, changesBfsDec, edgesNs / 1_000_000L,
                    edgeSec, edgeBfsInc, edgeBfsDec);
        }
    }


    private final class ChunkLightPublisher implements InitialLightCoordinator.Publisher {

        @Override
        public void beginGeneration(final Chunk chunk) {
            // Client: stays usable, or MixinWorld's gate drops block changes until publish.
            final boolean keepUsable = WorldLightManager.this.world.isRemote && ((SupernovaChunk) chunk).isLightUsable();
            ((SupernovaChunk) chunk).setLightReady(false);
            ((SupernovaChunk) chunk).setLightUsable(keepUsable);
        }

        @Override
        public void markUsable(final Chunk chunk) {
            ((SupernovaChunk) chunk).setLightUsable(true);
        }

        @Override
        public void syncToVanilla(final Chunk chunk) {
            ((SupernovaChunk) chunk).syncLightToVanilla();
        }

        @Override
        public void markReady(final Chunk chunk) {
            ((SupernovaChunk) chunk).setLightReady(true);
            if (WorldLightManager.this.world.isRemote) {
                markChunkForRenderUpdate(chunk);
                return;
            }
            // Without the dirty mark an otherwise-unmodified chunk never persists its lighting and relights every load.
            SupernovaChunk.markLightDirty(chunk);
        }
    }

    /** Whole-nibble installs mark nothing; only per-block writes do. */
    private void markChunkForRenderUpdate(final Chunk chunk) {
        final int x0 = chunk.xPosition << 4;
        final int z0 = chunk.zPosition << 4;
        for (final ExtendedBlockStorage section : chunk.getBlockStorageArray()) {
            if (section == null) continue;
            final int y0 = section.getYLocation();
            this.world.markBlockRangeForRenderUpdate(x0, y0, z0, x0 + 15, y0 + 15, z0 + 15);
        }
    }

    /** True when a retry now owns the lane, so the caller must not release it. */
    private boolean requeueAfterFailure(final Lane lane, final ChunkTasks task, final int cx, final int cz) {
        if (task.attempts >= MAX_RELIGHT_ATTEMPTS) {
            Supernova.LOG.error("{} engine: chunk ({}, {}) failed {} times (BFS overflow or error) -- giving up.", lane.name, cx, cz, task.attempts + 1);
            return false;
        }
        final Chunk chunk = this.loadedChunkMap.get(task.chunkCoordinate);
        if (chunk == null) return false;
        if (task.lightGeneration <= 0L || task.edgePass) {
            // Neither holds a propagation lane; requeueing under an edge pass's generation consumes it and leaves the generation unpublished.
            if (this.world.isRemote) {
                Supernova.LOG.warn("{} engine: chunk ({}, {}) failed on the client -- keeping partial light, no relight.", lane.name, cx, cz);
                return false;
            }
            relightAndResend(cx, cz, chunk);
            return false;
        }
        lane.queue.requeueChunkLight(cx, cz, chunk, SupernovaEngine.getEmptySectionsForChunk(chunk), task.lightGeneration, task.attempts);
        return true;
    }

    private boolean requeueEdgesAfterFailure(final Lane lane, final ChunkTasks task, final int cx, final int cz) {
        if (task.lightGeneration <= 0L || !task.edgePass) return false;
        if (task.attempts >= MAX_RELIGHT_ATTEMPTS) {
            Supernova.LOG.error("{} engine: chunk ({}, {}) failed {} times (BFS overflow or error) -- publishing anyway.", lane.name, cx, cz,
                task.attempts + 1);
            return false;
        }
        lane.queue.queueInitialLightEdges(cx, cz, task.lightGeneration, task.attempts + 1);
        return true;
    }


    public boolean forceRelightChunk(final int cx, final int cz) {
        final long key = CoordinateUtils.getChunkKey(cx, cz);
        final Chunk chunk = this.loadedChunkMap.get(key);
        if (chunk == null) return false;
        relightAndResend(cx, cz, chunk);
        return true;
    }

    /** 1.7.10 has no light packet, so a relight is invisible without a full resend; packet construction must run on the server thread. */
    private void relightAndResend(final int cx, final int cz, final Chunk chunk) {
        final long key = CoordinateUtils.getChunkKey(cx, cz);
        final InitialLightCoordinator.Generation generation = this.initialLighting.queue(cx, cz, chunk, SupernovaEngine.getEmptySectionsForChunk(chunk));
        if (this.world.isRemote) return;
        generation.done.addListener(() -> {
            if (!generation.published) return;
            try {
                ServerThreadUtil.addScheduledTask(() -> resendChunk(key));
            } catch (final IllegalStateException stopping) {
                // Server went away between the relight finishing and the resend being scheduled.
            }
        }, Runnable::run);
    }

    /** Short on purpose: timeout drops readiness, costing a relight instead of a partial save. */
    public boolean awaitPendingWork(final int cx, final int cz) {
        final long deadline = System.nanoTime() + UNLOAD_WAIT_NS;
        while (true) {
            final SettableFuture<Void> pending = pendingWorkFuture(cx, cz);
            if (pending == null) return true;
            final long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) break;
            try {
                pending.get(remaining, TimeUnit.NANOSECONDS);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (final Exception e) {
                break;
            }
        }
        Supernova.LOG.warn("Timed out waiting for light work on chunk ({}, {})", cx, cz);
        return false;
    }

    private SettableFuture<Void> pendingWorkFuture(final int cx, final int cz) {
        for (int dz = -1; dz <= 1; ++dz) {
            for (int dx = -1; dx <= 1; ++dx) {
                final long key = CoordinateUtils.getChunkKey(cx + dx, cz + dz);
                final SettableFuture<Void> pending = pendingWorkFutureAt(key);
                if (pending != null) return pending;
            }
        }
        return null;
    }

    private SettableFuture<Void> pendingWorkFutureAt(final long key) {
        if (this.skyQueue != null) {
            final SettableFuture<Void> f = this.skyQueue.pendingWorkFuture(key);
            if (f != null) return f;
        }
        if (this.blockQueue != null) {
            final SettableFuture<Void> f = this.blockQueue.pendingWorkFuture(key);
            if (f != null) return f;
        }
        return this.initialLighting.pendingFuture(key);
    }

    private void resendChunk(final long key) {
        if (!(this.world instanceof WorldServer)) return;
        final Chunk chunk = this.loadedChunkMap.get(key);
        if (chunk == null || !((SupernovaChunk) chunk).isLightReady()) return;
        final S21PacketChunkData packet = new S21PacketChunkData(chunk, true, 0xFFFF);
        for (final Object player : this.world.playerEntities) {
            if (!(player instanceof EntityPlayerMP)) continue;
            final EntityPlayerMP mp = (EntityPlayerMP) player;
            if (((WorldServer) this.world).getPlayerManager().isPlayerWatchingChunk(mp, chunk.xPosition, chunk.zPosition)) {
                mp.playerNetServerHandler.sendPacket(packet);
            }
        }
    }

    public void shutdown() {
        this.running = false;
        if (this.skyQueue != null) this.skyQueue.wakeUp();
        if (this.blockQueue != null) this.blockQueue.wakeUp();
        if (this.skyWorkerThread != null) {
            try {
                this.skyWorkerThread.join(1000);
            } catch (final InterruptedException ignored) {
            }
        }
        if (this.blockWorkerThread != null) {
            try {
                this.blockWorkerThread.join(1000);
            } catch (final InterruptedException ignored) {
            }
        }
        this.stats.close();
    }
}
