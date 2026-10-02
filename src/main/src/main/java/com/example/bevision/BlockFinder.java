package com.example.bevision;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Keeps the set of blocks the user picked in the menu and scans loaded chunks for them.
 * The scan is spread over many ticks so it never freezes the game.
 */
public final class BlockFinder {
    private BlockFinder() {}

    public record Hit(int x, int y, int z, float r, float g, float b) {}

    private record ChunkCoord(int x, int z) {}

    private static final int MAX_HITS = 4000;
    private static final int CHUNKS_PER_TICK = 6;
    private static final int RESCAN_DELAY_TICKS = 40;
    private static final int MAX_SCAN_RANGE = 128;

    private static final Set<Block> targets = new LinkedHashSet<>();
    private static volatile List<Hit> results = List.of();
    private static volatile boolean capped = false;

    // scan state
    private static final ArrayDeque<ChunkCoord> queue = new ArrayDeque<>();
    private static List<Hit> building = new ArrayList<>();
    private static Set<Block> scanTargets = Set.of();
    private static Map<Block, float[]> scanColors = Map.of();
    private static Vec3d scanOrigin = Vec3d.ZERO;
    private static double scanRangeSq = 0;
    private static boolean scanCapped = false;
    private static int cooldown = 0;

    // ------------------------------------------------------------ selection API

    public static boolean has(Block block) {
        return targets.contains(block);
    }

    public static void toggle(Block block) {
        if (!targets.remove(block)) {
            targets.add(block);
        }
        restart();
    }

    public static void clear() {
        targets.clear();
        restart();
    }

    public static int selectedCount() {
        return targets.size();
    }

    public static List<Hit> results() {
        return results;
    }

    public static boolean isCapped() {
        return capped;
    }

    private static void restart() {
        queue.clear();
        building = new ArrayList<>();
        results = List.of();
        capped = false;
        cooldown = 0;
    }

    // ------------------------------------------------------------ scanning

    public static void tick(MinecraftClient mc) {
        ClientWorld world = mc.world;
        if (world == null || mc.player == null || targets.isEmpty()) {
            if (!results.isEmpty()) results = List.of();
            queue.clear();
            return;
        }

        if (queue.isEmpty()) {
            if (cooldown > 0) {
                cooldown--;
                return;
            }
            startScan(mc);
        }

        for (int i = 0; i < CHUNKS_PER_TICK && !queue.isEmpty(); i++) {
            scanChunk(world, queue.poll());
        }

        if (queue.isEmpty()) {
            results = building;
            capped = scanCapped;
            cooldown = RESCAN_DELAY_TICKS;
        }
    }

    private static void startScan(MinecraftClient mc) {
        scanTargets = new HashSet<>(targets);
        scanColors = new HashMap<>();
        for (Block b : scanTargets) {
            int hash = Registries.BLOCK.getId(b).hashCode();
            float hue = ((hash & 0xFFFF) / 65535f);
            int rgb = MathHelper.hsvToRgb(hue, 0.85f, 1.0f);
            scanColors.put(b, new float[]{
                    ((rgb >> 16) & 255) / 255f, ((rgb >> 8) & 255) / 255f, (rgb & 255) / 255f});
        }

        building = new ArrayList<>();
        scanCapped = false;
        scanOrigin = mc.player.getPos();
        int range = Math.min(BEVisionClient.getRange(), MAX_SCAN_RANGE);
        scanRangeSq = (double) range * range;

        int chunkRadius = (range >> 4) + 1;
        int pcx = mc.player.getBlockPos().getX() >> 4;
        int pcz = mc.player.getBlockPos().getZ() >> 4;

        List<ChunkCoord> coords = new ArrayList<>();
        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                coords.add(new ChunkCoord(pcx + dx, pcz + dz));
            }
        }
        // nearest chunks first so results appear around you quickly
        Collections.sort(coords, (a, b) -> Integer.compare(
                (a.x() - pcx) * (a.x() - pcx) + (a.z() - pcz) * (a.z() - pcz),
                (b.x() - pcx) * (b.x() - pcx) + (b.z() - pcz) * (b.z() - pcz)));
        queue.addAll(coords);
    }

    private static void scanChunk(ClientWorld world, ChunkCoord cc) {
        WorldChunk chunk = world.getChunkManager().getWorldChunk(cc.x(), cc.z());
        if (chunk == null || scanCapped) return;

        Predicate<BlockState> wanted = state -> scanTargets.contains(state.getBlock());
        ChunkSection[] sections = chunk.getSectionArray();

        for (int i = 0; i < sections.length; i++) {
            ChunkSection section = sections[i];
            if (section == null || section.isEmpty() || !section.hasAny(wanted)) continue;

            int baseY = (chunk.getBottomSectionCoord() + i) << 4;

            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        Block block = section.getBlockState(x, y, z).getBlock();
                        if (!scanTargets.contains(block)) continue;

                        int wx = (cc.x() << 4) + x;
                        int wy = baseY + y;
                        int wz = (cc.z() << 4) + z;

                        double dx = wx + 0.5 - scanOrigin.x;
                        double dy = wy + 0.5 - scanOrigin.y;
                        double dz = wz + 0.5 - scanOrigin.z;
                        if (dx * dx + dy * dy + dz * dz > scanRangeSq) continue;

                        if (building.size() >= MAX_HITS) {
                            scanCapped = true;
                            return;
                        }

                        float[] c = scanColors.get(block);
                        building.add(new Hit(wx, wy, wz, c[0], c[1], c[2]));
                    }
                }
            }
        }
    }
}
