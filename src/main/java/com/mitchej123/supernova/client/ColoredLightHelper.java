package com.mitchej123.supernova.client;

import com.mitchej123.supernova.light.ChunkLightHelper;
import com.mitchej123.supernova.light.SWMRNibbleArray;
import com.mitchej123.supernova.light.SupernovaChunk;
import com.mitchej123.supernova.util.WorldUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;

import java.util.Arrays;

public final class ColoredLightHelper {

    @FunctionalInterface
    public interface TintFunction {

        void computeTint(float br, float bg, float bb, float sr, float sg, float sb, float[] out);
    }

    /** Active tint function. Defaults to TintBlendMode.current; redirected to Angelica's TintRegistry when present. */
    private static volatile TintFunction activeTintFunction = (br, bg, bb, sr, sg, sb, out) -> TintBlendMode.current.computeTint(br, bg, bb, sr, sg, sb, out);

    /** Called by AngelicaCompat to redirect tint lookups through Angelica's TintRegistry. */
    public static void setActiveTintFunction(TintFunction function) {
        activeTintFunction = function;
    }

    private static final float[] NO_TINT = { 1.0f, 1.0f, 1.0f };
    // Reused return buffer -- callers must consume before the next call on the same thread.
    private static final float[] RESULT = new float[3];

    // [corner TL/BL/BR/TR][R/G/B]
    // Reused return buffer -- callers must consume before the next call on the same thread.
    private static final float[][] VERTEX_TINTS = new float[4][3];
    private static final float[][] NO_VERTEX_TINTS = { { 1, 1, 1 }, { 1, 1, 1 }, { 1, 1, 1 }, { 1, 1, 1 } };

    private static final int[] SAMPLES = new int[9];

    private static final int CACHE_SIZE = 4;
    private static final int[] cachedChunkXs = new int[CACHE_SIZE];
    private static final int[] cachedChunkZs = new int[CACHE_SIZE];
    private static final Chunk[] cachedChunks = new Chunk[CACHE_SIZE];
    private static int cacheWriteIndex = 0;

    static {
        Arrays.fill(cachedChunkXs, Integer.MIN_VALUE);
    }

    // 0=Y-, 1=Y+, 2=Z-, 3=Z+, 4=X-, 5=X+
    private static final int[][] FACE_NORMAL = { { 0, -1, 0 }, { 0, 1, 0 }, { 0, 0, -1 }, { 0, 0, 1 }, { -1, 0, 0 }, { 1, 0, 0 } };

    // Tangent axis A per face (X for Y/Z faces, Z for X faces)
    private static final int[][] TANGENT_A = { { 1, 0, 0 }, { 1, 0, 0 }, { 1, 0, 0 }, { 1, 0, 0 }, { 0, 0, 1 }, { 0, 0, 1 } };

    // Tangent axis B per face (Z for Y faces, Y for Z/X faces)
    private static final int[][] TANGENT_B = { { 0, 0, 1 }, { 0, 0, 1 }, { 0, 1, 0 }, { 0, 1, 0 }, { 0, 1, 0 }, { 0, 1, 0 } };

    // [face][corner TL=0/BL=1/BR=2/TR=3] = {sign_a, sign_b}
    // Derived from MC's AO quadrant averaging in renderStandardBlockWithAmbientOcclusion.
    private static final int[][][] CORNER_SIGNS = { { { -1, 1 }, { -1, -1 }, { 1, -1 }, { 1, 1 } }, // Y-Neg
            { { 1, 1 }, { 1, -1 }, { -1, -1 }, { -1, 1 } }, // Y-Pos
            { { -1, 1 }, { 1, 1 }, { 1, -1 }, { -1, -1 } }, // Z-Neg
            { { -1, 1 }, { -1, -1 }, { 1, -1 }, { 1, 1 } }, // Z-Pos
            { { 1, 1 }, { -1, 1 }, { -1, -1 }, { 1, -1 } }, // X-Neg
            { { 1, -1 }, { -1, -1 }, { -1, 1 }, { 1, 1 } }, // X-Pos
    };

    private ColoredLightHelper() {}

    static void computeTint(float br, float bg, float bb, float sr, float sg, float sb, float[] out) {
        // White sky + no block light -> always white tint regardless of blend mode
        if (sr >= 14.5f && sg >= 14.5f && sb >= 14.5f && br < 0.5f && bg < 0.5f && bb < 0.5f) {
            out[0] = out[1] = out[2] = 1f;
            return;
        }
        activeTintFunction.computeTint(br, bg, bb, sr, sg, sb, out);
    }

    /** Flat tint from six neighbors. Returns {1,1,1} with no light; consume the reused array before the next call. */
    public static float[] getBlockTint(int x, int y, int z) {
        final World world = Minecraft.getMinecraft().theWorld;
        if (world == null) return NO_TINT;

        final int minLight = WorldUtil.getMinLightSection();
        final int maxLight = WorldUtil.getMaxLightSection();

        int maxBlockR = 0, maxBlockG = 0, maxBlockB = 0;
        int maxSkyR = 0, maxSkyG = 0, maxSkyB = 0;
        for (final int[] off : FACE_NORMAL) {
            final int packed = readLight(world, x + off[0], y + off[1], z + off[2], minLight, maxLight);
            if (packed < 0) continue;

            final int br = packed & 0xF;
            final int bg = (packed >> 4) & 0xF;
            final int bb = (packed >> 8) & 0xF;
            final int sr = (packed >> 12) & 0xF;
            final int sg = (packed >> 16) & 0xF;
            final int sb = (packed >> 20) & 0xF;

            if (br > maxBlockR) maxBlockR = br;
            if (bg > maxBlockG) maxBlockG = bg;
            if (bb > maxBlockB) maxBlockB = bb;
            if (sr > maxSkyR) maxSkyR = sr;
            if (sg > maxSkyG) maxSkyG = sg;
            if (sb > maxSkyB) maxSkyB = sb;
        }

        final int maxBlock = Math.max(maxBlockR, Math.max(maxBlockG, maxBlockB));
        if (maxBlock == 0 && maxSkyR == 0 && maxSkyG == 0 && maxSkyB == 0) return NO_TINT;

        // Sky contribution modulated by time of day
        final float sub = world.skylightSubtracted;
        final float effSkyR = Math.max(0, maxSkyR - sub);
        final float effSkyG = Math.max(0, maxSkyG - sub);
        final float effSkyB = Math.max(0, maxSkyB - sub);

        final float[] result = RESULT;
        computeTint(maxBlockR, maxBlockG, maxBlockB, effSkyR, effSkyG, effSkyB, result);
        if (result[0] >= 1.0f && result[1] >= 1.0f && result[2] >= 1.0f) return NO_TINT;
        return result;
    }

    /**
     * Per-vertex RGB tints for AO rendering, using the same 4 sample positions per corner that MC uses for AO brightness interpolation. Returned array is
     * reused -- consume values before calling again.
     *
     * @param face MC face index (0=Y-, 1=Y+, 2=Z-, 3=Z+, 4=X-, 5=X+)
     * @return float[4][3] -- corners TL/BL/BR/TR, each with R/G/B tint multiplier
     */
    public static float[][] computeVertexTints(int x, int y, int z, int face) {
        final World world = Minecraft.getMinecraft().theWorld;
        if (world == null) return NO_VERTEX_TINTS;

        final int minLight = WorldUtil.getMinLightSection();
        final int maxLight = WorldUtil.getMaxLightSection();

        final int[] normal = FACE_NORMAL[face];
        final int[] tanA = TANGENT_A[face];
        final int[] tanB = TANGENT_B[face];

        final int fcx = x + normal[0];
        final int fcy = y + normal[1];
        final int fcz = z + normal[2];

        final float sub = world.skylightSubtracted;
        final float[][] vertexTints = VERTEX_TINTS;

        final int[] samplesGrid = SAMPLES;
        for (int a = -1; a <= 1; a++) {
            for (int b = -1; b <= 1; b++) {
                samplesGrid[(a + 1) * 3 + (b + 1)] = readLight(world, fcx + a * tanA[0] + b * tanB[0], fcy + a * tanA[1] + b * tanB[1], fcz + a * tanA[2] + b * tanB[2], minLight, maxLight);
            }
        }

        for (int corner = 0; corner < 4; corner++) {
            final int sa = CORNER_SIGNS[face][corner][0];
            final int sb = CORNER_SIGNS[face][corner][1];

            float totalBlockR = 0, totalBlockG = 0, totalBlockB = 0;
            float totalSkyR = 0, totalSkyG = 0, totalSkyB = 0;
            int samples = 0;

            for (int da = 0; da <= 1; da++) {
                for (int db = 0; db <= 1; db++) {
                    final int packed = samplesGrid[(1 + da * sa) * 3 + (1 + db * sb)];
                    if (packed < 0) continue;

                    totalBlockR += packed & 0xF;
                    totalBlockG += (packed >> 4) & 0xF;
                    totalBlockB += (packed >> 8) & 0xF;
                    totalSkyR += Math.max(0, ((packed >> 12) & 0xF) - sub);
                    totalSkyG += Math.max(0, ((packed >> 16) & 0xF) - sub);
                    totalSkyB += Math.max(0, ((packed >> 20) & 0xF) - sub);
                    samples++;
                }
            }

            if (samples == 0) {
                vertexTints[corner][0] = vertexTints[corner][1] = vertexTints[corner][2] = 1.0f;
                continue;
            }

            final float avgBlockR = totalBlockR / samples;
            final float avgBlockG = totalBlockG / samples;
            final float avgBlockB = totalBlockB / samples;
            final float avgSkyR = totalSkyR / samples;
            final float avgSkyG = totalSkyG / samples;
            final float avgSkyB = totalSkyB / samples;

            computeTint(avgBlockR, avgBlockG, avgBlockB, avgSkyR, avgSkyG, avgSkyB, vertexTints[corner]);
        }

        return vertexTints;
    }

    /**
     * Read RGB block light + RGB sky light at (x, y, z). Returns packed
     * {@code blockR | (blockG << 4) | (blockB << 8) | (skyR << 12) | (skyG << 16) | (skyB << 20)}, or -1 if unavailable.
     */
    static int readLight(final World world, final int x, final int y, final int z) {
        return readLight(world, x, y, z, WorldUtil.getMinLightSection(), WorldUtil.getMaxLightSection());
    }

    /** Packed {@code bR | bG<<4 | bB<<8 | sR<<12 | sG<<16 | sB<<20}, or -1 if unavailable. */
    private static int readLight(final World world, final int x, final int y, final int z, final int minLight, final int maxLight) {
        if (y < WorldUtil.getMinBlockY() || y > WorldUtil.getMaxBlockY()) return -1;

        final int cx = x >> 4;
        final int cz = z >> 4;

        Chunk chunk = null;
        for (int i = 0; i < CACHE_SIZE; i++) {
            if (cx == cachedChunkXs[i] && cz == cachedChunkZs[i] && cachedChunks[i] != null) {
                chunk = cachedChunks[i];
                break;
            }
        }
        if (chunk == null) {
            final IChunkProvider provider = world.getChunkProvider();
            if (!provider.chunkExists(cx, cz)) return -1;
            chunk = provider.provideChunk(cx, cz);
            if (chunk == null) return -1;
            cachedChunkXs[cacheWriteIndex] = cx;
            cachedChunkZs[cacheWriteIndex] = cz;
            cachedChunks[cacheWriteIndex] = chunk;
            cacheWriteIndex = (cacheWriteIndex + 1) & (CACHE_SIZE - 1);
        }

        final int sectionY = y >> 4;
        if (sectionY < minLight || sectionY > maxLight) return -1;

        final SupernovaChunk ext = (SupernovaChunk) chunk;
        final int idx = sectionY - minLight;
        final int r = readNibble(ext.getBlockNibblesR(), idx, x, y, z);
        final int g = readNibble(ext.getBlockNibblesG(), idx, x, y, z);
        final int b = readNibble(ext.getBlockNibblesB(), idx, x, y, z);
        final int skyR = ChunkLightHelper.readSkyChannel(ext.getSkyNibblesR(), idx, x, y, z, 15);
        final int skyG = ChunkLightHelper.readSkyChannel(ext.getSkyNibblesG(), idx, x, y, z, skyR);
        final int skyB = ChunkLightHelper.readSkyChannel(ext.getSkyNibblesB(), idx, x, y, z, skyR);

        return r | (g << 4) | (b << 8) | (skyR << 12) | (skyG << 16) | (skyB << 20);
    }

    private static int readNibble(final SWMRNibbleArray[] nibbles, final int idx, final int x, final int y, final int z) {
        if (nibbles == null) return 0;
        final SWMRNibbleArray nib = nibbles[idx];
        if (nib == null || nib.isNullNibbleVisible()) return 0;
        return nib.getVisible(x, y, z);
    }

}
