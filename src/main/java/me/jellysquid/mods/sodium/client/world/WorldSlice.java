package me.jellysquid.mods.sodium.client.world;

import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import me.jellysquid.mods.sodium.client.util.math.ChunkSectionPos;
import me.jellysquid.mods.sodium.client.world.biome.BiomeCache;
import me.jellysquid.mods.sodium.client.world.biome.BiomeColorCache;
import me.jellysquid.mods.sodium.client.world.cloned.ChunkRenderContext;
import me.jellysquid.mods.sodium.client.world.cloned.ClonedChunkSection;
import me.jellysquid.mods.sodium.client.world.cloned.ClonedChunkSectionCache;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Biomes;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraft.world.WorldType;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeColorHelper;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraft.world.gen.structure.StructureBoundingBox;

import java.util.Arrays;
import java.util.Map;

/**
 * Takes a slice of world state (block states, biome and light data arrays) and copies the data for use in off-thread
 * operations. This allows chunk build tasks to see a consistent snapshot of chunk data at the exact moment the task was
 * created.
 *
 * World slices are not safe to use from multiple threads at once, but the data they contain is safe from modification
 * by the main client thread.
 *
 * Object pooling should be used to avoid huge allocations as this class contains many large arrays.
 */
public class WorldSlice implements IBlockAccess {
    // The number of blocks on each axis in a section.
    private static final int SECTION_BLOCK_LENGTH = 16;

    // The number of blocks in a section.
    private static final int SECTION_BLOCK_COUNT = SECTION_BLOCK_LENGTH * SECTION_BLOCK_LENGTH * SECTION_BLOCK_LENGTH;

    // The radius of blocks around the origin chunk that should be copied.
    private static final int NEIGHBOR_BLOCK_RADIUS = 2;

    // The radius of chunks around the origin chunk that should be copied.
    private static final int NEIGHBOR_CHUNK_RADIUS = MathHelper.roundUp(NEIGHBOR_BLOCK_RADIUS, 16) >> 4;

    // The number of sections on each axis of this slice.
    private static final int SECTION_LENGTH = 1 + (NEIGHBOR_CHUNK_RADIUS * 2);

    // The size of the lookup tables used for mapping values to coordinate int pairs. The lookup table size is always
    // a power of two so that multiplications can be replaced with simple bit shifts in hot code paths.
    private static final int TABLE_LENGTH = MathHelper.smallestEncompassingPowerOfTwo(SECTION_LENGTH);

    // The number of bits needed for each X/Y/Z component in a lookup table.
    private static final int TABLE_BITS = Integer.bitCount(TABLE_LENGTH - 1);

    // The array size for the section lookup table.
    private static final int SECTION_TABLE_ARRAY_SIZE = TABLE_LENGTH * TABLE_LENGTH * TABLE_LENGTH;

    // The world this slice has copied data from
    private final World world;

    // Local Section->BlockState table.
    private final IBlockState[][] blockStatesArrays;

    // Local section copies. Read-only.
    private ClonedChunkSection[] sections;

    // Biome caches for each chunk section
    private BiomeCache[] biomeCaches;

    // The biome blend caches for each color resolver type
    // This map is always re-initialized, but the caches themselves are taken from an object pool
    private final Map<BiomeColorHelper.ColorResolver, BiomeColorCache> biomeColorCaches = new Reference2ObjectOpenHashMap<>();

    // The previously accessed and cached color resolver, used in conjunction with the cached color cache field
    private BiomeColorHelper.ColorResolver prevColorResolver;

    // The cached lookup result for the previously accessed color resolver to avoid excess hash table accesses
    // for vertex color blending
    private BiomeColorCache prevColorCache;

    // The starting point from which this slice captures blocks
    private int baseX, baseY, baseZ;

    // The chunk origin of this slice
    private ChunkSectionPos origin;

    // The volume that this slice contains
    private StructureBoundingBox volume;
    private WorldType worldType;

    public static ChunkRenderContext prepare(World world, ChunkSectionPos origin, ClonedChunkSectionCache sectionCache) {
        Chunk chunk = world.getChunk(origin.getX(), origin.getZ());
        ExtendedBlockStorage section = chunk.getBlockStorageArray()[origin.getY()];

        // If the chunk section is absent or empty, simply terminate now. There will never be anything in this chunk
        // section to render, so we need to signal that a chunk render task shouldn't created. This saves a considerable
        // amount of time in queueing instant build tasks and greatly accelerates how quickly the world can be loaded.
        if (section == null || section.isEmpty()) {
            return null;
        }

        StructureBoundingBox volume = new StructureBoundingBox(origin.getMinX() - NEIGHBOR_BLOCK_RADIUS,
                origin.getMinY() - NEIGHBOR_BLOCK_RADIUS,
                origin.getMinZ() - NEIGHBOR_BLOCK_RADIUS,
                origin.getMaxX() + NEIGHBOR_BLOCK_RADIUS,
                origin.getMaxY() + NEIGHBOR_BLOCK_RADIUS,
                origin.getMaxZ() + NEIGHBOR_BLOCK_RADIUS);

        // The min/max bounds of the chunks copied by this slice
        final int minChunkX = origin.getX() - NEIGHBOR_CHUNK_RADIUS;
        final int minChunkY = origin.getY() - NEIGHBOR_CHUNK_RADIUS;
        final int minChunkZ = origin.getZ() - NEIGHBOR_CHUNK_RADIUS;

        final int maxChunkX = origin.getX() + NEIGHBOR_CHUNK_RADIUS;
        final int maxChunkY = origin.getY() + NEIGHBOR_CHUNK_RADIUS;
        final int maxChunkZ = origin.getZ() + NEIGHBOR_CHUNK_RADIUS;

        ClonedChunkSection[] sections = new ClonedChunkSection[SECTION_TABLE_ARRAY_SIZE];

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                for (int chunkY = minChunkY; chunkY <= maxChunkY; chunkY++) {
                    sections[getLocalSectionIndex(chunkX - minChunkX, chunkY - minChunkY, chunkZ - minChunkZ)] =
                            sectionCache.acquire(chunkX, chunkY, chunkZ);
                }
            }
        }

        return new ChunkRenderContext(origin, sections, volume);
    }

    public WorldSlice(World world) {
        this.world = world;
        this.worldType = world.getWorldType();

        this.sections = new ClonedChunkSection[SECTION_TABLE_ARRAY_SIZE];
        this.blockStatesArrays = new IBlockState[SECTION_TABLE_ARRAY_SIZE][];
        this.biomeCaches = new BiomeCache[SECTION_TABLE_ARRAY_SIZE];

        for (int x = 0; x < SECTION_LENGTH; x++) {
            for (int y = 0; y < SECTION_LENGTH; y++) {
                for (int z = 0; z < SECTION_LENGTH; z++) {
                    int i = getLocalSectionIndex(x, y, z);

                    this.blockStatesArrays[i] = new IBlockState[SECTION_BLOCK_COUNT];
                    Arrays.fill(this.blockStatesArrays[i], Blocks.AIR.getDefaultState());
                    this.biomeCaches[i] = new BiomeCache(this.world);
                }
            }
        }
    }

    public void copyData(ChunkRenderContext context) {
        this.origin = context.getOrigin();
        this.sections = context.getSections();
        this.volume = context.getVolume();

        this.prevColorCache = null;
        this.prevColorResolver = null;

        this.biomeColorCaches.clear();

        this.baseX = (this.origin.getX() - NEIGHBOR_CHUNK_RADIUS) << 4;
        this.baseY = (this.origin.getY() - NEIGHBOR_CHUNK_RADIUS) << 4;
        this.baseZ = (this.origin.getZ() - NEIGHBOR_CHUNK_RADIUS) << 4;

        for (int x = 0; x < SECTION_LENGTH; x++) {
            for (int y = 0; y < SECTION_LENGTH; y++) {
                for (int z = 0; z < SECTION_LENGTH; z++) {
                    int idx = getLocalSectionIndex(x, y, z);

                    this.biomeCaches[idx].reset();

                    this.unpackBlockData(this.blockStatesArrays[idx], this.sections[idx], context.getVolume());
                }
            }
        }
    }

    private void unpackBlockData(IBlockState[] states, ClonedChunkSection section, StructureBoundingBox box) {
        if (this.origin.equals(section.getPosition()))  {
            this.unpackBlockDataZ(states, section);
        } else {
            this.unpackBlockDataR(states, section, box);
        }
    }

    private static void copyBlocks(IBlockState[] blocks, ClonedChunkSection section, int minBlockY, int maxBlockY, int minBlockZ, int maxBlockZ, int minBlockX, int maxBlockX) {
        for (int y = minBlockY; y <= maxBlockY; y++) {
            for (int z = minBlockZ; z <= maxBlockZ; z++) {
                for (int x = minBlockX; x <= maxBlockX; x++) {
                    final int blockIdx = getLocalBlockIndex(x & 15, y & 15, z & 15);
                    blocks[blockIdx] = section.getBlockState(x & 15, y & 15, z & 15);
                }
            }
        }
    }

    private void unpackBlockDataR(IBlockState[] states, ClonedChunkSection section, StructureBoundingBox box) {
        ChunkSectionPos pos = section.getPosition();

        int minBlockX = Math.max(box.minX, pos.getMinX());
        int maxBlockX = Math.min(box.maxX, pos.getMaxX());

        int minBlockY = Math.max(box.minY, pos.getMinY());
        int maxBlockY = Math.min(box.maxY, pos.getMaxY());

        int minBlockZ = Math.max(box.minZ, pos.getMinZ());
        int maxBlockZ = Math.min(box.maxZ, pos.getMaxZ());

        copyBlocks(states, section, minBlockY, maxBlockY, minBlockZ, maxBlockZ, minBlockX, maxBlockX);
    }

    private void unpackBlockDataZ(IBlockState[] states, ClonedChunkSection section) {
        // TODO: Look into a faster copy for this?
        final ChunkSectionPos pos = section.getPosition();

        final int minBlockX = pos.getMinX();
        final int maxBlockX = pos.getMaxX();

        final int minBlockY = pos.getMinY();
        final int maxBlockY = pos.getMaxY();

        final int minBlockZ = pos.getMinZ();
        final int maxBlockZ = pos.getMaxZ();

        // TODO: Can this be optimized?
        copyBlocks(states, section, minBlockY, maxBlockY, minBlockZ, maxBlockZ, minBlockX, maxBlockX);
    }

    private static boolean blockBoxContains(StructureBoundingBox box, int x, int y, int z) {
        return x >= box.minX &&
                x <= box.maxX &&
                y >= box.minY &&
                y <= box.maxY &&
                z >= box.minZ &&
                z <= box.maxZ;
    }

    @Override
    public IBlockState getBlockState(BlockPos pos) {
        return this.getBlockState(pos.getX(), pos.getY(), pos.getZ());
    }

    @Override
    public boolean isAirBlock(BlockPos pos) {
        IBlockState state = this.getBlockState(pos);
        return state.getBlock().isAir(state, this, pos);
    }

    public IBlockState getBlockState(int x, int y, int z) {
        if (!blockBoxContains(this.volume, x, y, z)) {
            return Blocks.AIR.getDefaultState();
        }

        int relX = x - this.baseX;
        int relY = y - this.baseY;
        int relZ = z - this.baseZ;

        return this.blockStatesArrays[getLocalSectionIndex(relX >> 4, relY >> 4, relZ >> 4)]
                [getLocalBlockIndex(relX & 15, relY & 15, relZ & 15)];
    }

    public IBlockState getBlockStateRelative(int x, int y, int z) {
        // NOTE: Not bounds checked. We assume ChunkRenderRebuildTask is the only function using this
        return this.blockStatesArrays[getLocalSectionIndex(x >> 4, y >> 4, z >> 4)]
                [getLocalBlockIndex(x & 15, y & 15, z & 15)];
    }

    @Override
    public TileEntity getTileEntity(BlockPos pos) {
        return this.getBlockEntity(pos.getX(), pos.getY(), pos.getZ());
    }

    public TileEntity getBlockEntity(int x, int y, int z) {
        if (!blockBoxContains(this.volume, x, y, z)) {
            return null;
        }

        int relX = x - this.baseX;
        int relY = y - this.baseY;
        int relZ = z - this.baseZ;

        return this.sections[getLocalSectionIndex(relX >> 4, relY >> 4, relZ >> 4)]
                .getBlockEntity(relX & 15, relY & 15, relZ & 15);
    }

    @Override
    public int getCombinedLight(BlockPos pos, int ambientDarkness) {
        return this.world.getCombinedLight(pos, ambientDarkness);
    }

    @Override
    public Biome getBiome(BlockPos pos) {
        int x2 = (pos.getX() - this.baseX) >> 4;
        int z2 = (pos.getZ() - this.baseZ) >> 4;

        ClonedChunkSection section = this.sections[getLocalChunkIndex(x2, z2)];

        if (section != null) {
            return section.getBiomeForNoiseGen(pos.getX() & 15, pos.getZ() & 15);
        }

        return this.world.getBiomeForCoordsBody(pos);
    }

    @Override
    public int getStrongPower(BlockPos pos, EnumFacing direction) {
        IBlockState state = this.getBlockState(pos);
        return state.getBlock().getStrongPower(state, this, pos, direction);
    }

    @Override
    public WorldType getWorldType() {
        return this.worldType;
    }

    @Override
    public boolean isSideSolid(BlockPos pos, EnumFacing side, boolean _default) {
        return getBlockState(pos).isSideSolid(this, pos, side);
    }

    /**
     * Gets or computes the biome at the given global coordinates.
     */
    public Biome getBiome(int x, int y, int z) {
        int relX = x - this.baseX;
        int relY = y - this.baseY;
        int relZ = z - this.baseZ;

        int idx = getLocalSectionIndex(relX >> 4, relY >> 4, relZ >> 4);

        if (idx < 0 || idx >= this.biomeCaches.length) {
            return Biomes.PLAINS;
        }

        return this.biomeCaches[idx]
                .getBiome(x, relY >> 4, z);
    }

    public ChunkSectionPos getOrigin() {
        return this.origin;
    }

    // Modern checks if the sky is darkened, which only happens in the nether. However, I think 1.7.10's hasNoSky is
    // close enough and possibly a behavior change between versions. I also don't know why it's rotationally asymmetric
    public float getBrightness(EnumFacing direction, boolean shaded) {
        if (!shaded) {
            return !world.provider.hasSkyLight() ? 0.9f : 1.0f;
        }
        switch (direction) {
            case DOWN:
                return !world.provider.hasSkyLight() ? 0.9f : 0.5f;
            case UP:
                return !world.provider.hasSkyLight() ? 0.9f : 1.0f;
            case NORTH:
            case SOUTH:
                return 0.8f;
            case WEST:
            case EAST:
                return 0.6f;
            default:
                return 1.0f;
        }
    }

    // [VanillaCopy] PalettedContainer#toIndex
    public static int getLocalBlockIndex(int x, int y, int z) {
        return y << 8 | z << 4 | x;
    }

    public static int getLocalSectionIndex(int x, int y, int z) {
        return y << TABLE_BITS << TABLE_BITS | z << TABLE_BITS | x;
    }

    public static int getLocalChunkIndex(int x, int z) {
        return z << TABLE_BITS | x;
    }
}
