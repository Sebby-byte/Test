package com.example.autoattack;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Two separate, toggleable features, for hostile mobs and other players (never animals):
 *
 *  Auto Click  - presses your Attack key (a plain left click) while your crosshair is on a hostile
 *                mob or another player within normal reach. It sends no attack commands of its own.
 *  Auto Rotate - turns your view toward the nearest hostile mob or player within the set range (default 6 blocks) that you have a
 *                clear line of sight to. Rotation speed (degrees per tick) is set with a slider
 *                in the settings screen.
 *
 * Safeguards: always allowed in singleplayer; on multiplayer it only runs on servers listed in
 * "allowedServers" in config/autoattack.properties (default: localhost). Both features start OFF
 * every launch. Only use it where automation is permitted; most public servers prohibit it.
 */
public class AutoAttackClient implements ClientModInitializer {
    public static final String MOD_ID = "autoattack";

    public static final float MIN_RANGE = 1.0f;
    public static final float MAX_RANGE = 45.0f;
    public static final float DEFAULT_RANGE = 6.0f;
    public static final float MIN_SPEED = 1.0f;
    public static final float MAX_SPEED = 45.0f;
    private static final float MIN_ATTACK_STRENGTH = 0.95f;

    private static boolean autoClick = false;
    private static boolean autoRotate = false;
    private static float rotateSpeed = 10.0f; // max degrees turned per tick (saved)
    private static float rotateRange = DEFAULT_RANGE; // blocks (saved)
    private static final Set<String> allowedServers = new HashSet<>();

    private static KeyMapping toggleClickKey, toggleRotateKey, settingsKey;

    @Override
    public void onInitializeClient() {
        loadConfig();

        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath(MOD_ID, "main"));

        toggleClickKey = key("key.autoattack.toggle_click", InputConstants.KEY_V, category);
        toggleRotateKey = key("key.autoattack.toggle_rotate", InputConstants.KEY_R, category);
        settingsKey = key("key.autoattack.settings", InputConstants.KEY_U, category);

        ClientTickEvents.END_CLIENT_TICK.register(AutoAttackClient::tick);
    }

    private static KeyMapping key(String translationKey, int code, KeyMapping.Category category) {
        return KeyMappingHelper.registerKeyMapping(new KeyMapping(translationKey, code, category));
    }

    // ---------------------------------------------------------------- state used by the screen

    public static boolean isAutoClick() { return autoClick; }
    public static boolean isAutoRotate() { return autoRotate; }
    public static float getRotateSpeed() { return rotateSpeed; }
    public static float getRotateRange() { return rotateRange; }

    public static void setRotateRange(float range) {
        rotateRange = Mth.clamp(range, MIN_RANGE, MAX_RANGE);
    }

    public static void setRotateSpeed(float speed) {
        rotateSpeed = Mth.clamp(speed, MIN_SPEED, MAX_SPEED);
    }

    public static void toggleClick(Minecraft mc) {
        toggleFeature(mc, true);
    }

    public static void toggleRotate(Minecraft mc) {
        toggleFeature(mc, false);
    }

    private static void toggleFeature(Minecraft mc, boolean click) {
        LocalPlayer player = mc.player;
        boolean current = click ? autoClick : autoRotate;
        boolean next = !current;

        if (next) {
            if (player == null || mc.level == null) return;
            if (!isAllowedHere(mc)) {
                say(player, "Not enabled: this server is not in allowedServers in config/autoattack.properties. "
                        + "Only use this where automation is permitted.");
                return;
            }
        }

        if (click) autoClick = next; else autoRotate = next;
        say(player, (click ? "Auto Click: " : "Auto Rotate: ") + (next ? "ON" : "OFF"));
    }

    private static void say(LocalPlayer player, String text) {
        if (player != null) player.sendSystemMessage(Component.literal("[Auto Attack] " + text));
    }

    // ---------------------------------------------------------------- main loop

    private static void tick(Minecraft mc) {
        while (toggleClickKey.consumeClick()) toggleClick(mc);
        while (toggleRotateKey.consumeClick()) toggleRotate(mc);
        while (settingsKey.consumeClick()) {
            if (mc.level != null) mc.gui.setScreen(new AutoAttackSettingsScreen());
        }

        if (!autoClick && !autoRotate) return;

        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            autoClick = false; // left the world
            autoRotate = false;
            return;
        }
        if (!isAllowedHere(mc)) { // switched server while on
            autoClick = false;
            autoRotate = false;
            say(player, "OFF (this server is not allowed)");
            return;
        }

        // Don't act while a menu/chat is open, or while dead/spectating.
        if (mc.gui.screen() != null) return;
        if (!player.isAlive() || player.isSpectator()) return;

        if (autoRotate) rotateTowardNearestHostile(mc.level, player);
        if (autoClick) clickIfOnHostile(mc, player);
    }

    // ---------------------------------------------------------------- auto click

    private static void clickIfOnHostile(Minecraft mc, LocalPlayer player) {
        if (player.isUsingItem()) return;

        // Is the crosshair on an entity? (Only true when it is within normal reach.)
        if (!(mc.hitResult instanceof EntityHitResult hit)) return;

        Entity target = hit.getEntity();
        if (!(target instanceof LivingEntity living) || !living.isAlive()) return;
        if (!isValidTarget(player, target)) return; // hostile mobs and other players

        // Wait for the attack cooldown so each click is a full-strength hit.
        if (player.getAttackStrengthScale(0.5f) < MIN_ATTACK_STRENGTH) return;

        // Just a click: press the left mouse button once (the default Attack key).
        KeyMapping.click(InputConstants.Type.MOUSE.getOrCreate(InputConstants.MOUSE_BUTTON_LEFT));
    }

    // ---------------------------------------------------------------- auto rotate

    private static void rotateTowardNearestHostile(ClientLevel level, LocalPlayer player) {
        LivingEntity target = findNearestHostile(level, player);
        if (target == null) return;

        Vec3 eye = player.getEyePosition();
        Vec3 aim = target.getBoundingBox().getCenter();
        double dx = aim.x - eye.x;
        double dy = aim.y - eye.y;
        double dz = aim.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        float wantYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float wantPitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));

        float deltaYaw = Mth.wrapDegrees(wantYaw - player.getYRot());
        float deltaPitch = wantPitch - player.getXRot();

        // Turn at most "rotateSpeed" degrees per tick, so the slider controls how fast it snaps on.
        float step = rotateSpeed;
        player.setYRot(player.getYRot() + Mth.clamp(deltaYaw, -step, step));
        player.setXRot(Mth.clamp(player.getXRot() + Mth.clamp(deltaPitch, -step, step), -90.0f, 90.0f));
    }

    private static LivingEntity findNearestHostile(ClientLevel level, LocalPlayer player) {
        AABB box = player.getBoundingBox().inflate(rotateRange);
        List<Entity> nearby = level.getEntities(player, box);

        LivingEntity best = null;
        double bestDistSq = (double) rotateRange * rotateRange;

        for (Entity e : nearby) {
            if (!(e instanceof LivingEntity living) || !living.isAlive()) continue;
            if (!isValidTarget(player, e)) continue; // hostile mobs and other players

            double distSq = player.distanceToSqr(e);
            if (distSq > bestDistSq) continue;            // within the configured range
            if (!player.hasLineOfSight(e)) continue;      // not through walls

            best = living;
            bestDistSq = distSq;
        }
        return best;
    }

    /** Hostile mobs, plus other players (never yourself, and never spectators). */
    private static boolean isValidTarget(LocalPlayer self, Entity e) {
        if (e == self) return false;
        if (e instanceof Player other) return !other.isSpectator();
        return e.getType().getCategory() == MobCategory.MONSTER;
    }

    // ---------------------------------------------------------------- server allowlist

    /** True when connected to a multiplayer server that is not on the allowlist yet. */
    public static boolean canAllowCurrentServer(Minecraft mc) {
        return mc.getSingleplayerServer() == null && mc.getCurrentServer() != null && !isAllowedHere(mc);
    }

    /** Adds the server you are connected to onto the allowlist (saved to the config file). */
    public static void allowCurrentServer(Minecraft mc) {
        ServerData server = mc.getCurrentServer();
        if (mc.getSingleplayerServer() != null || server == null) return;
        String full = server.ip.trim().toLowerCase(Locale.ROOT);
        allowedServers.add(hostOnly(full));
        saveConfig();
        say(mc.player, "Allowed " + hostOnly(full) + " for this and future sessions. "
                + "Only use this where automation is permitted.");
    }

    private static boolean isAllowedHere(Minecraft mc) {
        if (mc.getSingleplayerServer() != null) return true; // singleplayer / opened-to-LAN own world

        ServerData server = mc.getCurrentServer();
        if (server == null) return false;

        String full = server.ip.trim().toLowerCase(Locale.ROOT);
        return allowedServers.contains(full) || allowedServers.contains(hostOnly(full));
    }

    /** Strips a trailing ":port" from "host:port" (leaves IPv6 and bare hosts alone). */
    private static String hostOnly(String address) {
        int colon = address.lastIndexOf(':');
        if (colon > 0 && address.indexOf(':') == colon) {
            return address.substring(0, colon);
        }
        return address;
    }

    // ---------------------------------------------------------------- config file

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("autoattack.properties");
    }

    private static void loadConfig() {
        Path path = configPath();
        Properties props = new Properties();

        if (Files.exists(path)) {
            try (InputStream in = Files.newInputStream(path)) {
                props.load(in);
            } catch (IOException ignored) {
            }
        }

        try {
            setRotateSpeed(Float.parseFloat(props.getProperty("rotateSpeed", "10.0")));
        } catch (NumberFormatException e) {
            rotateSpeed = 10.0f;
        }

        try {
            setRotateRange(Float.parseFloat(props.getProperty("rotateRange", Float.toString(DEFAULT_RANGE))));
        } catch (NumberFormatException e) {
            rotateRange = DEFAULT_RANGE;
        }

        allowedServers.clear();
        for (String entry : props.getProperty("allowedServers", "localhost,127.0.0.1").split(",")) {
            String s = entry.trim().toLowerCase(Locale.ROOT);
            if (!s.isEmpty()) allowedServers.add(s);
        }

        if (!Files.exists(path)) saveConfig(); // write defaults so the file is easy to find and edit
    }

    public static void saveConfig() {
        Properties props = new Properties();
        props.setProperty("rotateSpeed", Float.toString(rotateSpeed));
        props.setProperty("rotateRange", Float.toString(rotateRange));
        props.setProperty("allowedServers", String.join(",", allowedServers));

        try (OutputStream out = Files.newOutputStream(configPath())) {
            props.store(out, "Auto Attack. allowedServers = comma-separated server addresses where automation "
                    + "is permitted (singleplayer is always allowed). rotateSpeed = max degrees per tick. rotateRange = auto rotate range in blocks (1-45).");
        } catch (IOException ignored) {
        }
    }
}
