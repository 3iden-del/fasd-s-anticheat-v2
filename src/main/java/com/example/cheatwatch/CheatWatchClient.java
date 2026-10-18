package com.example.cheatwatch;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.*;

/**
 * fasd's Anti Cheat: a CLIENT-SIDE heuristic cheat spotter.
 * It only uses what the server already tells your client about other players
 * (position, swing animation, hurt animation, held item, effects, ping).
 * It cannot see other players' packets or their client brand.
 */
public class CheatWatchClient implements ClientModInitializer {

    public static CheatWatchClient INSTANCE;

    // ---- tuning ----
    static final double REPORT_VL = 3.0;        // VL needed before a player counts as flagged
    static final double DECAY_PER_TICK = 0.004; // VL fades over time
    static final boolean AUTO_ALERT = false;    // true = print a chat line the first time someone crosses REPORT_VL
    static final double WATCH_RANGE = 96.0;
    static final int MAX_PING = 400;            // players above this ping are not judged (too laggy to trust)
    static final int WARMUP_TICKS = 40;         // ignore a player for 2s after they appear / teleport

    private final Map<UUID, Track> tracks = new HashMap<>();
    private final Set<String> alerted = new HashSet<>();
    private int now = 0;
    private KeyMapping menuKey, reportKey, clearKey;

    @Override
    public void onInitializeClient() {
        INSTANCE = this;
        KeyMapping.Category cat = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("fasds_anti_cheat", "main"));
        menuKey = KeyBindingHelper.registerKeyBinding(
                new KeyMapping("key.fasds_anti_cheat.menu", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, cat));
        reportKey = KeyBindingHelper.registerKeyBinding(
                new KeyMapping("key.fasds_anti_cheat.report", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, cat));
        clearKey = KeyBindingHelper.registerKeyBinding(
                new KeyMapping("key.fasds_anti_cheat.clear", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, cat));
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    public int now() { return now; }

    // =====================================================================
    private void onTick(Minecraft mc) {
        if (mc.level == null || mc.player == null) {
            tracks.clear();
            alerted.clear();
            return;
        }
        now++;
        ClientLevel level = mc.level;

        while (menuKey.consumeClick()) {
            if (mc.screen == null) mc.setScreen(new FlagScreen(this));
        }
        while (reportKey.consumeClick()) report(mc);
        while (clearKey.consumeClick()) {
            clearAll();
            mc.player.displayClientMessage(Component.literal("[fasd's Anti Cheat] Cleared all flags.").withStyle(ChatFormatting.GRAY), false);
        }

        List<AbstractClientPlayer> players = new ArrayList<>();
        for (AbstractClientPlayer p : level.players()) {
            if (p == mc.player || p.isRemoved()) continue;
            if (p.distanceTo(mc.player) > WATCH_RANGE) continue;
            players.add(p);
        }

        // pass 1: movement + bookkeeping
        for (AbstractClientPlayer p : players) {
            Track t = tracks.computeIfAbsent(p.getUUID(), k -> new Track());
            t.uuid = p.getUUID();
            t.name = p.getName().getString();
            t.lastSeen = now;
            t.dist = p.distanceTo(mc.player);
            t.ping = pingOf(mc, p);
            movementChecks(level, p, t);
        }
        // pass 2: combat checks (need every player's state updated first)
        for (AbstractClientPlayer p : players) {
            combatChecks(level, players, p, tracks.get(p.getUUID()));
        }

        // decay + cleanup
        Set<UUID> present = new HashSet<>();
        for (AbstractClientPlayer p : players) present.add(p.getUUID());
        for (Iterator<Map.Entry<UUID, Track>> it = tracks.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Track> e = it.next();
            Track t = e.getValue();
            t.vl.replaceAll((c, v) -> Math.max(0, v - DECAY_PER_TICK));
            boolean gone = !present.contains(e.getKey());
            if (gone && (now - t.lastSeen > 6000 || (t.score() < 0.01 && t.reachHits == 0))) it.remove();
        }

        if (AUTO_ALERT) {
            for (Track t : tracks.values()) {
                for (Map.Entry<Cheat, Double> e : t.vl.entrySet()) {
                    String key = t.name + e.getKey();
                    if (e.getValue() >= REPORT_VL && alerted.add(key)) {
                        mc.player.displayClientMessage(Component.literal("[fasd's Anti Cheat] " + t.name + " may be using " + e.getKey().label)
                                .withStyle(ChatFormatting.RED), false);
                    }
                }
            }
        }
    }

    private int pingOf(Minecraft mc, AbstractClientPlayer p) {
        ClientPacketListener conn = mc.getConnection();
        if (conn == null) return 0;
        PlayerInfo info = conn.getPlayerInfo(p.getUUID());
        return info == null ? 0 : info.getLatency();
    }

    // =====================================================================
    private void movementChecks(ClientLevel level, AbstractClientPlayer p, Track t) {
        double x = p.getX(), y = p.getY(), z = p.getZ();
        if (!t.has) {
            t.has = true;
            t.born = now;
            t.px = x; t.py = y; t.pz = z;
            t.lastHeadYaw = p.getYHeadRot();
            t.fallPeak = y;
            return;
        }
        double dx = x - t.px, dy = y - t.py, dz = z - t.pz;
        double h = Math.sqrt(dx * dx + dz * dz);
        t.px = x; t.py = y; t.pz = z;

        // teleport / pearl / respawn: ignore and reset
        if (dx * dx + dy * dy + dz * dz > 25) {
            t.resetMovement();
            t.lastTeleport = now;
            t.airborne = false;
            t.nfDue = 0;
            t.kbDue = 0;
            return;
        }

        // ---- bookkeeping for exemptions ----
        if (p.hurtTime > 0) t.lastHurt = now;
        if (p.isInWater() || p.isInLava()) t.lastWater = now;
        if (holdingSpecial(p)) t.lastSpecial = now;
        BlockState under = level.getBlockState(BlockPos.containing(x, y - 0.2, z));
        if (under.getBlock().getFriction() > 0.62f) t.lastSlip = now;
        if (under.is(Blocks.SLIME_BLOCK) || under.getBlock() instanceof BedBlock) t.lastBounce = now;

        boolean onGround = p.onGround();
        boolean feetBlocked = !level.getBlockState(p.blockPosition()).isAir(); // webs, water, vines, bushes...

        fallTracking(level, p, t, x, y, z, onGround, feetBlocked);

        boolean exempt = p.isSpectator() || p.isCreative() || p.isPassenger() || p.isFallFlying()
                || p.isAutoSpinAttack() || p.isSleeping() || p.isDeadOrDying();
        boolean recent = now - t.lastHurt < 40 || now - t.lastSpecial < 100
                || now - t.lastWater < 20 || now - t.lastBounce < 60;
        boolean warm = now - t.born < WARMUP_TICKS || now - t.lastTeleport < 20;
        if (exempt || recent || warm || t.ping > MAX_PING) {
            t.resetMovement();
            t.wasGround = onGround;
            return;
        }

        boolean slip = now - t.lastSlip < 20;

        // ---------------- SPEED ----------------
        if (p.onClimbable() || slip || feetBlocked) {
            t.winN = 0;
        } else {
            t.win[t.winI++ % 10] = h;
            t.winN = Math.min(t.winN + 1, 10);
            if (t.winN == 10) {
                double sum = 0;
                for (double d : t.win) sum += d;
                double avg = sum / 10.0;
                double limit = 0.45;
                if (p.hasEffect(MobEffects.SPEED)) {
                    limit *= 1.0 + 0.2 * (p.getEffect(MobEffects.SPEED).getAmplifier() + 1);
                }
                // needs two separate fast windows within 2 seconds before it counts
                if (avg > limit && now - t.lastStrike >= 10) {
                    if (now - t.lastStrike > 40) t.speedStrikes = 0;
                    t.speedStrikes++;
                    t.lastStrike = now;
                    if (t.speedStrikes >= 2) {
                        t.speedStrikes = 0;
                        flag(t, Cheat.SPEED, 1.0, String.format("%.1f b/s (limit %.1f)", avg * 20, limit * 20));
                    }
                }
            }
        }

        // ---------------- NOSLOW ----------------
        if (p.isUsingItem() && onGround && !slip && !feetBlocked) {
            t.useTicks++;
            t.useSum += h;
            if (t.useTicks >= 10) {
                double avg = t.useSum / t.useTicks;
                if (avg > 0.12) {
                    flag(t, Cheat.NOSLOW, 1.0, String.format("%.1f b/s while using an item (vanilla ~1)", avg * 20));
                }
                t.useTicks = 0;
                t.useSum = 0;
            }
        } else {
            t.useTicks = 0;
            t.useSum = 0;
        }

        boolean canFlyCheck = !p.onClimbable() && !feetBlocked && !p.hasEffect(MobEffects.LEVITATION)
                && !p.hasEffect(MobEffects.JUMP_BOOST);

        // ---------------- FLY / HOVER / SPIDER ----------------
        if (!onGround && canFlyCheck) {
            if (Math.abs(dy) < 0.005) t.hoverTicks++; else t.hoverTicks = 0;
            if (dy > 0.02) t.ascendTicks++; else t.ascendTicks = 0;
            if (t.hoverTicks >= 12 && t.hoverTicks % 12 == 0) flag(t, Cheat.FLY, 1.0, "hovering in mid-air");
            if (t.ascendTicks >= 12 && t.ascendTicks % 12 == 0) flag(t, Cheat.FLY, 1.0, "rising for " + t.ascendTicks + " ticks");
        } else {
            t.hoverTicks = 0;
            t.ascendTicks = 0;
        }

        // ---------------- HIGH JUMP ----------------
        if (t.wasGround && !onGround) {
            t.airStartY = t.py - dy; // y just before leaving the ground
            t.flaggedJump = false;
        }
        if (!onGround && canFlyCheck && !t.flaggedJump) {
            double rise = y - t.airStartY;
            if (rise > 1.6) {
                t.flaggedJump = true;
                flag(t, Cheat.HIGH_JUMP, 1.5, String.format("rose %.2f blocks (vanilla ~1.25)", rise));
            }
        }
        if (onGround) t.flaggedJump = false;

        // ---------------- STEP ----------------
        if (onGround) {
            t.groundRun++;
            t.yRing[t.ringI++ % 4] = y;
            if (t.groundRun >= 4) {
                double oldest = t.yRing[t.ringI % 4];
                double rise = y - oldest;
                if (rise > 0.8 && now - t.lastFlag.getOrDefault(Cheat.STEP, -1000) >= 20) {
                    flag(t, Cheat.STEP, 1.0, String.format("stepped up %.2f blocks without jumping", rise));
                }
            }
        } else {
            t.groundRun = 0;
        }

        // ---------------- JESUS ----------------
        BlockState below = level.getBlockState(BlockPos.containing(x, y - 0.2, z));
        boolean onWaterSurface = below.is(Blocks.WATER) && !p.isInWater() && !feetBlocked;
        if (onWaterSurface && h > 0.08 && Math.abs(dy) < 0.01) t.jesusTicks++; else t.jesusTicks = 0;
        if (t.jesusTicks >= 10 && t.jesusTicks % 10 == 0) flag(t, Cheat.JESUS, 1.0, "moving across the water surface");

        t.wasGround = onGround;
    }

    // ---------------- NOFALL ----------------
    private void fallTracking(ClientLevel level, AbstractClientPlayer p, Track t,
                              double x, double y, double z, boolean onGround, boolean feetBlocked) {
        boolean highAmp = false;
        for (MobEffectInstance e : p.getActiveEffects()) {
            if (e.getAmplifier() >= 3) highAmp = true; // e.g. Resistance V can fully cancel fall damage
        }
        boolean noDamage = p.isInWater() || p.isInLava() || p.onClimbable() || p.isFallFlying() || p.isPassenger()
                || feetBlocked || p.hasEffect(MobEffects.SLOW_FALLING) || p.hasEffect(MobEffects.LEVITATION)
                || p.hasEffect(MobEffects.JUMP_BOOST) || highAmp
                || p.isCreative() || p.isSpectator() || now - t.lastSpecial < 100;

        if (!onGround) {
            if (!t.airborne) {
                t.airborne = true;
                t.fallPeak = y;
                t.fallInvalid = false;
            }
            t.fallPeak = Math.max(t.fallPeak, y);
            if (noDamage) t.fallInvalid = true;
        } else if (t.airborne) {
            t.airborne = false;
            double drop = t.fallPeak - y;
            BlockState under = level.getBlockState(BlockPos.containing(x, y - 0.2, z));
            boolean soft = under.is(Blocks.SLIME_BLOCK) || under.is(Blocks.HAY_BLOCK)
                    || under.is(Blocks.HONEY_BLOCK) || under.getBlock() instanceof BedBlock;
            boolean entityBelow = !level.getEntities(p, p.getBoundingBox().inflate(0.3, 0.6, 0.3)).isEmpty();
            if (drop > 4.5 && !t.fallInvalid && !noDamage && !soft && !entityBelow
                    && now - t.born >= WARMUP_TICKS && now - t.lastTeleport >= 20 && t.ping <= MAX_PING) {
                t.nfDue = now + 8;
                t.nfDrop = drop;
            }
        }

        if (t.nfDue > 0 && now >= t.nfDue) {
            t.nfDue = 0;
            // a hurt animation around the landing would have refreshed lastHurt
            if (now - t.lastHurt > 10) {
                flag(t, Cheat.NOFALL, 1.5, String.format("fell %.1f blocks and took no damage", t.nfDrop));
            }
        }
    }

    // =====================================================================
    private void combatChecks(ClientLevel level, List<AbstractClientPlayer> players, AbstractClientPlayer p, Track t) {
        boolean swingStart = p.swinging && (!t.prevSwinging || p.swingTime < t.prevSwingTime);
        t.prevSwinging = p.swinging;
        t.prevSwingTime = p.swingTime;

        float yaw = p.getYHeadRot();
        float yawDelta = Math.abs(Mth.wrapDegrees(yaw - t.lastHeadYaw));
        t.lastHeadYaw = yaw;

        // ---------------- VELOCITY (result of a knockback test set up 8 ticks ago) ----------------
        if (t.kbDue > 0 && now >= t.kbDue) {
            t.kbDue = 0;
            double moved = (p.getX() - t.kbSx) * t.kbDx + (p.getZ() - t.kbSz) * t.kbDz;
            if (moved < 0.15 && !p.isDeadOrDying() && t.ping <= MAX_PING) {
                flag(t, Cheat.VELOCITY, 1.0, String.format("moved only %.2f blocks after a hit (expected ~0.8)", moved));
            }
        }

        if (p.isSpectator() || p.isCreative() || t.ping > MAX_PING) {
            t.prevHurtTime = p.hurtTime;
            return;
        }

        // ---------------- AUTOCLICK (swing rate) ----------------
        if (swingStart) {
            t.swingTicks[t.swingI++ % 16] = now;
            t.swingCount = Math.min(t.swingCount + 1, 16);
            int inWindow = 0;
            for (int i = 0; i < t.swingCount; i++) if (now - t.swingTicks[i] <= 40) inWindow++;
            // the game only shows a new swing every ~3 ticks at best, so 10+ in 2s means constant max-rate spam
            if (inWindow >= 10 && now - t.lastFlag.getOrDefault(Cheat.AUTOCLICK, -1000) >= 40) {
                flag(t, Cheat.AUTOCLICK, 0.75, inWindow + " swings in 2s (could be a fast legit clicker)");
            }
        }

        // nearest other player
        AbstractClientPlayer nearest = null;
        double best = Double.MAX_VALUE;
        for (AbstractClientPlayer q : players) {
            if (q == p) continue;
            double d = q.getBoundingBox().distanceToSqr(p.getEyePosition());
            if (d < best) { best = d; nearest = q; }
        }

        // ---------------- KILLAURA ----------------
        if (nearest != null && p.swinging) {
            double dist = Math.sqrt(best);
            if (swingStart && yawDelta > 75 && dist < 6) {
                flag(t, Cheat.KILLAURA, 0.5, String.format("aim snapped %.0f degrees in 1 tick", yawDelta));
            }
            if (swingStart && dist < 3.5 && nearest.hurtTime >= 7) {
                Vec3 look = lookVec(p);
                Vec3 to = nearest.getBoundingBox().getCenter().subtract(p.getEyePosition()).normalize();
                if (look.dot(to) < -0.17) { // more than ~100 degrees off
                    flag(t, Cheat.KILLAURA, 1.5, "hit a player who was behind them");
                }
            }
        }

        // ---------------- REACH + start of VELOCITY test ----------------
        // victim just got hurt this tick -> find who was swinging at them
        if (p.hurtTime >= 9 && t.prevHurtTime < 9) {
            AbstractClientPlayer attacker = null;
            double bestD = Double.MAX_VALUE;
            for (AbstractClientPlayer a : players) {
                if (a == p || !a.swinging || a.swingTime > 3) continue;
                double d = p.getBoundingBox().distanceToSqr(a.getEyePosition());
                if (d < bestD && d < 64) { bestD = d; attacker = a; }
            }
            if (attacker != null && meleeItem(attacker.getMainHandItem()) && !attacker.isPassenger()) {
                Track at = tracks.get(attacker.getUUID());
                double dist = Math.sqrt(bestD);
                Vec3 look = lookVec(attacker);
                Vec3 to = p.getBoundingBox().getCenter().subtract(attacker.getEyePosition()).normalize();
                boolean facing = look.dot(to) > 0.7;
                if (at != null && facing && at.ping <= MAX_PING) {
                    at.maxReach = Math.max(at.maxReach, dist);
                    at.lastReach = dist;
                    at.reachHits++;
                    // allow a little extra for lag: higher ping on either side = more tolerance
                    double limit = 3.8 + Math.min(0.5, Math.max(at.ping, t.ping) / 600.0);
                    if (dist > limit && now - at.born >= WARMUP_TICKS) {
                        flag(at, Cheat.REACH, dist > limit + 0.7 ? 1.5 : 1.0,
                                String.format("hit from %.2f blocks (vanilla 3.0)", dist));
                    }
                }
                if (facing) setupKnockbackTest(level, p, t, attacker);
            }
        }
        t.prevHurtTime = p.hurtTime;
    }

    /** Record where the victim is now; 8 ticks later we check they were actually pushed back. */
    private void setupKnockbackTest(ClientLevel level, AbstractClientPlayer victim, Track vt, AbstractClientPlayer attacker) {
        if (victim.isInWater() || victim.isInLava() || victim.isPassenger() || victim.isBlocking()
                || victim.isFallFlying() || victim.isSleeping() || victim.isCreative()
                || victim.isSpectator() || victim.isDeadOrDying()) return;
        if (vt.ping > MAX_PING || now - vt.born < WARMUP_TICKS || now - vt.lastTeleport < 20) return;

        Vec3 look = lookVec(attacker);
        double len = Math.sqrt(look.x * look.x + look.z * look.z);
        if (len < 0.3) return; // attacker looking straight up/down
        double hx = look.x / len, hz = look.z / len;

        double vx = victim.getX(), vy = victim.getY(), vz = victim.getZ();
        if (!level.getBlockState(victim.blockPosition()).isAir()) return; // webs, water, bushes...
        // a wall behind the victim would absorb the knockback
        for (double step = 0.6; step <= 1.6; step += 0.5) {
            BlockPos feet = BlockPos.containing(vx + hx * step, vy + 0.1, vz + hz * step);
            BlockPos head = BlockPos.containing(vx + hx * step, vy + 1.1, vz + hz * step);
            if (!level.getBlockState(feet).isAir() || !level.getBlockState(head).isAir()) return;
        }
        vt.kbDue = now + 8;
        vt.kbSx = vx;
        vt.kbSz = vz;
        vt.kbDx = hx;
        vt.kbDz = hz;
    }

    // =====================================================================
    private static Vec3 lookVec(AbstractClientPlayer p) {
        double yaw = Math.toRadians(p.getYHeadRot());
        double pitch = Math.toRadians(p.getXRot());
        return new Vec3(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
    }

    private static String itemPath(ItemStack s) {
        return BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
    }

    /** Only blame reach on real melee swings (not thrown items, bows, spears with extra range, etc). */
    private static boolean meleeItem(ItemStack s) {
        if (s.isEmpty()) return true;
        String n = itemPath(s);
        return n.endsWith("_sword") || n.endsWith("_axe") || n.endsWith("_shovel") || n.endsWith("_hoe") || n.equals("mace");
    }

    /** Items that can legitimately launch/boost someone: wind charge, mace, trident, firework, pearl, spears. */
    private static boolean holdingSpecial(AbstractClientPlayer p) {
        for (ItemStack s : new ItemStack[]{p.getMainHandItem(), p.getOffhandItem()}) {
            if (s.is(Items.WIND_CHARGE) || s.is(Items.MACE) || s.is(Items.TRIDENT)
                    || s.is(Items.FIREWORK_ROCKET) || s.is(Items.ENDER_PEARL)
                    || itemPath(s).contains("spear")) return true;
        }
        return false;
    }

    private void flag(Track t, Cheat c, double amount, String detail) {
        t.vl.merge(c, amount, Double::sum);
        t.lastFlag.put(c, now);
        t.detail.put(c, detail);
    }

    // =====================================================================
    // used by the menu
    public void clearAll() {
        tracks.clear();
        alerted.clear();
    }

    /** sort: 0 = most suspicious, 1 = closest to you, 2 = longest reach seen. */
    public List<Track> snapshot(int sort) {
        List<Track> out = new ArrayList<>();
        for (Track t : tracks.values()) {
            if (sort == 2 ? t.reachHits > 0 : t.score() >= 0.5) out.add(t);
        }
        if (sort == 0) out.sort((a, b) -> Double.compare(b.score(), a.score()));
        else if (sort == 1) out.sort((a, b) -> Double.compare(a.dist, b.dist));
        else out.sort((a, b) -> Double.compare(b.maxReach, a.maxReach));
        return out;
    }

    public void report(Minecraft mc) {
        List<Track> flagged = new ArrayList<>();
        for (Track t : tracks.values()) {
            if (t.vl.values().stream().anyMatch(v -> v >= REPORT_VL)) flagged.add(t);
        }
        var chat = mc.player;
        if (chat == null) return;
        chat.displayClientMessage(Component.literal("===== fasd's Anti Cheat report =====").withStyle(ChatFormatting.GOLD), false);
        if (flagged.isEmpty()) {
            chat.displayClientMessage(Component.literal("No one looks suspicious right now.").withStyle(ChatFormatting.GREEN), false);
            return;
        }
        flagged.sort((a, b) -> Double.compare(b.score(), a.score()));
        for (Track t : flagged) {
            int kinds = t.kindsOver(REPORT_VL);
            ChatFormatting col = kinds >= 3 ? ChatFormatting.DARK_RED : kinds == 2 ? ChatFormatting.RED : ChatFormatting.YELLOW;
            chat.displayClientMessage(Component.literal(String.format("%s - %s (%.0f blocks)", t.name, t.verdict(REPORT_VL), t.dist))
                    .withStyle(col, ChatFormatting.BOLD), false);
            for (Map.Entry<Cheat, Double> e : t.vl.entrySet()) {
                if (e.getValue() < REPORT_VL) continue;
                chat.displayClientMessage(Component.literal(String.format("  %s (vl %.1f) - %s",
                        e.getKey().label, e.getValue(), t.detail.getOrDefault(e.getKey(), e.getKey().desc)))
                        .withStyle(ChatFormatting.GRAY), false);
            }
        }
        chat.displayClientMessage(Component.literal("Heuristic only - lag and odd mechanics can cause false flags.")
                .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC), false);
    }
}
