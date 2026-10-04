package com.example.bevision;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.entity.TrappedChestBlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

/**
 * Draws translucent boxes through walls on nearby block entities and on blocks picked in the
 * Block Finder menu.
 *
 * In 26.3 you may not upload buffers while a render pass is open, so instead of drawing by hand
 * we submit custom geometry to the game's own submit-node renderer (COLLECT_SUBMITS) and let the
 * game draw it at the right time. The custom render type below has the depth test removed,
 * which is what makes the boxes show through walls.
 */
public class BEVisionClient implements ClientModInitializer {
    public static final String MOD_ID = "bevision";
    private static final float ALPHA = 0.35f;
    private static final int MAX_BOXES_DRAWN = 7000;

    // Same as the vanilla debug filled box pipeline, but with the depth test removed.
    private static final RenderPipeline FILLED_THROUGH_WALLS = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath(MOD_ID, "pipeline/filled_through_walls"))
                    .withDepthStencilState(Optional.empty())
                    .build()
    );

    private static final RenderType THROUGH_WALLS_TYPE = RenderType.create(
            MOD_ID + ":filled_through_walls",
            RenderSetup.builder(FILLED_THROUGH_WALLS).createRenderSetup()
    );

    private static boolean enabled = true; // block entity highlights
    private static int range = 64;         // blocks

    /** Immutable data collected in the extraction phase and used when submitting geometry. */
    private record BoxState(double minX, double minY, double minZ,
                            double maxX, double maxY, double maxZ,
                            float r, float g, float b) { }

    private static volatile List<BoxState> boxes = List.of();

    private static KeyMapping toggleKey, menuKey, rangeUpKey, rangeDownKey;

    public static int getRange() {
        return range;
    }

    @Override
    public void onInitializeClient() {
        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath(MOD_ID, "main"));

        toggleKey = key("key.bevision.toggle", InputConstants.KEY_G, category);
        menuKey = key("key.bevision.menu", InputConstants.KEY_B, category);
        rangeUpKey = key("key.bevision.range_up", InputConstants.KEY_EQUALS, category);
        rangeDownKey = key("key.bevision.range_down", InputConstants.KEY_MINUS, category);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleKey.consumeClick()) {
                enabled = !enabled;
                say(client, "Block entity highlights: " + (enabled ? "ON" : "OFF"));
            }
            while (menuKey.consumeClick()) {
                if (client.level != null) client.gui.setScreen(new BlockFinderScreen());
            }
            while (rangeUpKey.consumeClick()) {
                range = Math.min(256, range + 16);
                say(client, "Vision range: " + range);
            }
            while (rangeDownKey.consumeClick()) {
                range = Math.max(16, range - 16);
                say(client, "Vision range: " + range);
            }
            BlockFinder.tick(client);
        });

        LevelExtractionEvents.END_EXTRACTION.register(BEVisionClient::extract);
        LevelRenderEvents.COLLECT_SUBMITS.register(BEVisionClient::collect);
    }

    private static KeyMapping key(String translationKey, int code, KeyMapping.Category category) {
        return KeyMappingHelper.registerKeyMapping(
                new KeyMapping(translationKey, code, category));
    }

    private static void say(Minecraft client, String text) {
        if (client.player != null) {
            client.player.sendSystemMessage(Component.literal(text));
        }
    }

    /** Returns 0xRRGGBB, or -1 to skip this block entity. */
    private static int colorFor(BlockEntity be) {
        if (be instanceof TrappedChestBlockEntity) return 0xFF3030;      // red
        if (be instanceof ChestBlockEntity) return 0xFFA500;             // orange
        if (be instanceof EnderChestBlockEntity) return 0xB040FF;        // purple
        if (be instanceof BarrelBlockEntity) return 0x9C6B3A;            // brown
        if (be instanceof ShulkerBoxBlockEntity) return 0xFF70C0;        // pink
        if (be instanceof AbstractFurnaceBlockEntity) return 0xB0B0B0;   // gray
        if (be instanceof HopperBlockEntity) return 0x606060;            // dark gray
        if (be instanceof DispenserBlockEntity) return 0x40A0FF;         // blue (droppers too)
        if (be instanceof BrewingStandBlockEntity) return 0x40E0D0;      // teal
        if (be instanceof SpawnerBlockEntity) return 0xFF0000;           // bright red
        return -1;
    }

    // ---------------------------------------------------------------- extraction phase

    private static void extract(LevelExtractionContext context) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        List<BlockFinder.Hit> hits = BlockFinder.results();

        if (level == null || mc.player == null || (!enabled && hits.isEmpty())) {
            boxes = List.of();
            return;
        }

        List<BoxState> out = new ArrayList<>();

        // 1) block entities (chests, furnaces, spawners...)
        if (enabled) {
            Vec3 origin = mc.player.position();
            double rangeSq = (double) range * range;
            int chunkRadius = (range >> 4) + 1;
            int pcx = mc.player.blockPosition().getX() >> 4;
            int pcz = mc.player.blockPosition().getZ() >> 4;

            for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
                for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                    ChunkAccess access = level.getChunkSource().getChunk(pcx + dx, pcz + dz, ChunkStatus.FULL, false);
                    if (!(access instanceof LevelChunk chunk)) continue;

                    for (BlockEntity be : chunk.getBlockEntities().values()) {
                        BlockPos pos = be.getBlockPos();
                        if (pos.distToCenterSqr(origin.x, origin.y, origin.z) > rangeSq) continue;

                        int rgb = colorFor(be);
                        if (rgb < 0) continue;

                        VoxelShape shape = be.getBlockState().getShape(level, pos);
                        AABB box = shape.isEmpty() ? new AABB(0, 0, 0, 1, 1, 1) : shape.bounds();
                        box = box.move(pos).inflate(0.002);

                        out.add(new BoxState(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ,
                                ((rgb >> 16) & 255) / 255f, ((rgb >> 8) & 255) / 255f, (rgb & 255) / 255f));
                    }
                }
            }
        }

        // 2) blocks picked in the Block Finder menu
        for (BlockFinder.Hit h : hits) {
            out.add(new BoxState(h.x() - 0.002, h.y() - 0.002, h.z() - 0.002,
                    h.x() + 1.002, h.y() + 1.002, h.z() + 1.002, h.r(), h.g(), h.b()));
        }

        boxes = out;
    }

    // ---------------------------------------------------------------- submit phase

    private static void collect(LevelRenderContext context) {
        List<BoxState> list = boxes;
        if (list.isEmpty()) return;

        PoseStack poseStack = context.poseStack();
        Vec3 cam = context.levelState().cameraRenderState.pos;
        SubmitNodeCollector collector = context.submitNodeCollector();

        collector.submitCustomGeometry(poseStack, THROUGH_WALLS_TYPE, (pose, buffer) -> {
            int count = 0;
            for (BoxState b : list) {
                if (count++ >= MAX_BOXES_DRAWN) break;
                // Subtract the camera in double precision first so far-away boxes don't jitter.
                addFilledBox(pose, buffer,
                        (float) (b.minX() - cam.x), (float) (b.minY() - cam.y), (float) (b.minZ() - cam.z),
                        (float) (b.maxX() - cam.x), (float) (b.maxY() - cam.y), (float) (b.maxZ() - cam.z),
                        b.r(), b.g(), b.b(), ALPHA);
            }
        });
    }

    private static void addFilledBox(PoseStack.Pose p, VertexConsumer buffer,
                                     float minX, float minY, float minZ,
                                     float maxX, float maxY, float maxZ,
                                     float red, float green, float blue, float alpha) {
        // Front face
        buffer.addVertex(p, minX, minY, maxZ).setColor(red, green, blue, alpha);
        buffer.addVertex(p, maxX, minY, maxZ).setColor(red, green, blue, alpha);
        buffer.addVertex(p, maxX, maxY, maxZ).setColor(red, green, blue, alpha);
        buffer.addVertex(p, minX, maxY, maxZ).setColor(red, green, blue, alpha);

        // Back face
        buffer.addVertex(p, maxX, minY, minZ).setColor(red, green, blue, alpha);
        buffer.addVertex(p, minX, minY, minZ).setColor(red, green, blue, alpha);
        buffer.addVertex(p, minX, maxY, minZ).setColor(red, green, blue, alpha);
        buffer.addVertex(p, maxX, maxY, minZ).setColor(red, green, blue, alpha);

        // Left face
        buffer.addVertex(p, minX, minY, minZ).setColor(red, green, blue, alpha);
        buffer.addVertex(p, minX, minY, maxZ).setColor(red, green, blue, alpha);
        buffer.addVertex(p, minX, maxY, maxZ).setColor(red, green, blue, alpha);
        buffer.addVertex(p, minX, maxY, minZ).setColor(red, green, blue, alpha);

        // Right face
        buffer.addVertex(p, maxX, minY, maxZ).setColor(red, green, blue, alpha);
        buffer.addVertex(p, maxX, minY, minZ).setColor(red, green, blue, alpha);
        buffer.addVertex(p, maxX, maxY, minZ).setColor(red, green, blue, alpha);
        buffer.addVertex(p, maxX, maxY, maxZ).setColor(red, green, blue, alpha);

        // Top face
        buffer.addVertex(p, minX, maxY, maxZ).setColor(red, green, blue, alpha);
        buffer.addVertex(p, maxX, maxY, maxZ).setColor(red, green, blue, alpha);
        buffer.addVertex(p, maxX, maxY, minZ).setColor(red, green, blue, alpha);
        buffer.addVertex(p, minX, maxY, minZ).setColor(red, green, blue, alpha);

        // Bottom face
        buffer.addVertex(p, minX, minY, minZ).setColor(red, green, blue, alpha);
        buffer.addVertex(p, maxX, minY, minZ).setColor(red, green, blue, alpha);
        buffer.addVertex(p, maxX, minY, maxZ).setColor(red, green, blue, alpha);
        buffer.addVertex(p, minX, minY, maxZ).setColor(red, green, blue, alpha);
    }
}
