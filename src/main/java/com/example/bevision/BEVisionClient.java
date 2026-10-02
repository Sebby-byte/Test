package com.example.bevision;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.block.entity.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.ShaderProgramKeys;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.*;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.chunk.WorldChunk;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

public class BEVisionClient implements ClientModInitializer {
    private static boolean enabled = true;
    private static int range = 64; // blocks
    private static final float ALPHA = 0.35f;

    private static KeyBinding toggleKey, rangeUpKey, rangeDownKey;

    @Override
    public void onInitializeClient() {
        toggleKey = reg("key.bevision.toggle", GLFW.GLFW_KEY_G);
        rangeUpKey = reg("key.bevision.range_up", GLFW.GLFW_KEY_EQUAL);
        rangeDownKey = reg("key.bevision.range_down", GLFW.GLFW_KEY_MINUS);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleKey.wasPressed()) {
                enabled = !enabled;
                msg(client, "Block Entity Vision: " + (enabled ? "ON" : "OFF"));
            }
            while (rangeUpKey.wasPressed()) {
                range = Math.min(256, range + 16);
                msg(client, "BE Vision range: " + range);
            }
            while (rangeDownKey.wasPressed()) {
                range = Math.max(16, range - 16);
                msg(client, "BE Vision range: " + range);
            }
        });

        WorldRenderEvents.AFTER_TRANSLUCENT.register(BEVisionClient::render);
    }

    private static KeyBinding reg(String key, int code) {
        return KeyBindingHelper.registerKeyBinding(
                new KeyBinding(key, InputUtil.Type.KEYSYM, code, "category.bevision"));
    }

    private static void msg(MinecraftClient c, String s) {
        if (c.player != null) c.player.sendMessage(Text.literal(s), true);
    }

    /** Returns 0xRRGGBB, or -1 to skip this block entity. */
    private static int colorFor(BlockEntity be) {
        if (be instanceof TrappedChestBlockEntity) return 0xFF3030; // red
        if (be instanceof ChestBlockEntity) return 0xFFA500;        // orange
        if (be instanceof EnderChestBlockEntity) return 0xB040FF;   // purple
        if (be instanceof BarrelBlockEntity) return 0x9C6B3A;       // brown
        if (be instanceof ShulkerBoxBlockEntity) return 0xFF70C0;   // pink
        if (be instanceof AbstractFurnaceBlockEntity) return 0xB0B0B0; // gray
        if (be instanceof HopperBlockEntity) return 0x606060;       // dark gray
        if (be instanceof DispenserBlockEntity) return 0x40A0FF;    // blue (also droppers)
        if (be instanceof BrewingStandBlockEntity) return 0x40E0D0; // teal
        if (be instanceof MobSpawnerBlockEntity) return 0xFF0000;   // bright red
        return -1;
    }

    private static void render(WorldRenderContext ctx) {
        if (!enabled) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientWorld world = mc.world;
        MatrixStack matrices = ctx.matrixStack();
        if (world == null || mc.player == null || matrices == null) return;

        Vec3d cam = ctx.camera().getPos();
        Matrix4f m = matrices.peek().getPositionMatrix();
        double rangeSq = (double) range * range;
        int chunkRadius = (range >> 4) + 1;
        int pcx = mc.player.getBlockPos().getX() >> 4;
        int pcz = mc.player.getBlockPos().getZ() >> 4;

        BufferBuilder buf = Tessellator.getInstance()
                .begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);

        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                WorldChunk chunk = world.getChunkManager().getWorldChunk(pcx + dx, pcz + dz);
                if (chunk == null) continue;

                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    BlockPos pos = be.getPos();
                    if (pos.getSquaredDistance(cam) > rangeSq) continue;

                    int rgb = colorFor(be);
                    if (rgb < 0) continue;

                    VoxelShape shape = be.getCachedState().getOutlineShape(world, pos);
                    Box box = shape.isEmpty() ? new Box(0, 0, 0, 1, 1, 1) : shape.getBoundingBox();
                    box = box.offset(pos).expand(0.002).offset(-cam.x, -cam.y, -cam.z);

                    addBox(buf, m, box,
                            ((rgb >> 16) & 255) / 255f,
                            ((rgb >> 8) & 255) / 255f,
                            (rgb & 255) / 255f);
                }
            }
        }

        BuiltBuffer built = buf.endNullable();
        if (built == null) return; // nothing to draw

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest(); // <- this is what lets it show through walls
        RenderSystem.disableCull();
        RenderSystem.setShader(ShaderProgramKeys.POSITION_COLOR);

        BufferRenderer.drawWithGlobalProgram(built);

        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    private static void addBox(BufferBuilder b, Matrix4f m, Box bx, float r, float g, float bl) {
        float x1 = (float) bx.minX, y1 = (float) bx.minY, z1 = (float) bx.minZ;
        float x2 = (float) bx.maxX, y2 = (float) bx.maxY, z2 = (float) bx.maxZ;

        // bottom
        quad(b, m, x1,y1,z1, x2,y1,z1, x2,y1,z2, x1,y1,z2, r,g,bl);
        // top
        quad(b, m, x1,y2,z1, x1,y2,z2, x2,y2,z2, x2,y2,z1, r,g,bl);
        // north
        quad(b, m, x1,y1,z1, x1,y2,z1, x2,y2,z1, x2,y1,z1, r,g,bl);
        // south
        quad(b, m, x1,y1,z2, x2,y1,z2, x2,y2,z2, x1,y2,z2, r,g,bl);
        // west
        quad(b, m, x1,y1,z1, x1,y1,z2, x1,y2,z2, x1,y2,z1, r,g,bl);
        // east
        quad(b, m, x2,y1,z1, x2,y2,z1, x2,y2,z2, x2,y1,z2, r,g,bl);
    }

    private static void quad(BufferBuilder b, Matrix4f m,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz,
                             float r, float g, float bl) {
        b.vertex(m, ax, ay, az).color(r, g, bl, ALPHA);
        b.vertex(m, bx, by, bz).color(r, g, bl, ALPHA);
        b.vertex(m, cx, cy, cz).color(r, g, bl, ALPHA);
        b.vertex(m, dx, dy, dz).color(r, g, bl, ALPHA);
    }
}
