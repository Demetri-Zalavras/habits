package com.habits.perk.survival;

import com.habits.core.HabitsApi;
import com.mojang.datafixers.util.Pair;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * What the survival mixins call (the mixin package may only hold mixins). Every hook returns at once, after one
 * {@code instanceof} and at most one snapshot read, when the player does not own the skill.
 */
public final class SurvivalHooks {
    private SurvivalHooks() {}

    /** True only while FoodData.tick heals its player (server thread); set by FoodDataMixin. */
    public static boolean naturalRegen;

    /** Below this saturation vanilla's full-bar heal (saturation / 6 every 10 ticks) is slower than its plain heal
     * (1 every 80 ticks), and with hunger perks the scrap can take ages to burn off. */
    static final float SCRAP_SATURATION = 0.75f;

    /** True when FoodData should drop its last scrap of saturation so the plain heal runs instead. */
    public static boolean dropSaturationScrap(Player p, int food, float saturation) {
        return food >= 20 && saturation > 0 && saturation < SCRAP_SATURATION && p.isHurt()
                && p.level() instanceof net.minecraft.server.level.ServerLevel sl
                && sl.getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_NATURAL_REGENERATION);
    }

    /** Snow Born max: walk on powder snow like leather boots ({@code PowderSnowBlock.canEntityWalkOnPowderSnow}). */
    public static boolean snowWalk(Entity e) {
        if (!(e instanceof Player p) || !Fx.acts(p)) return false;
        HabitsApi.PerkHit h = Sk.SNOW_BORN.hit(p);
        return h != null && Sk.p(h, "walk") > 0;
    }

    /** Web Spinner / Bramble Walker: ease the slowdown a cobweb or berry bush sets ({@code Entity.makeStuckInBlock}). */
    public static Vec3 stuck(Entity e, BlockState s, Vec3 m) {
        if (!(e instanceof Player p)) return m;
        double pct = s.is(Blocks.COBWEB) ? Sk.WEB_SPINNER.v(p) : s.is(Blocks.SWEET_BERRY_BUSH) ? Sk.BRAMBLE_WALKER.v(p) : 0;
        if (pct <= 0) return m;
        if (pct >= 100) return Vec3.ZERO; // a zero multiplier means "not stuck": the move skips the slowdown entirely
        double k = pct / 100;
        return new Vec3(m.x + (1 - m.x) * k, m.y + (1 - m.y) * k, m.z + (1 - m.z) * k);
    }

    /** Gold Standard: the piglin admires gold for less time when it is bartering with you (vanilla throws the loot at
     * NEAREST_VISIBLE_PLAYER, so that is who it is bartering with). */
    public static long admire(LivingEntity piglin, long ticks) {
        Player p = piglin.getBrain().getMemory(MemoryModuleType.NEAREST_VISIBLE_PLAYER).orElse(null);
        HabitsApi.PerkHit h = p == null ? null : Sk.GOLD_STANDARD.hit(p);
        return h == null ? ticks : Math.round(ticks * (1 - Sk.p(h, "admire_cut") / 100));
    }

    /** Gold Standard: piglins angry at you calm down sooner. */
    public static long forgive(LivingEntity target, long ticks) {
        HabitsApi.PerkHit h = target instanceof Player p ? Sk.GOLD_STANDARD.hit(p) : null;
        return h == null ? ticks : Math.round(ticks * (1 - Sk.p(h, "forgive_cut") / 100));
    }

    // ------------------------------------------------------------------ Hard Hat: the anvil does not chip

    static final String NO_CHIP = "habits_no_chip";

    /** Set by the damage handler when the anvil hit a max Hard Hat owner, read when the anvil rolls its chip. */
    public static boolean keepAnvil(FallingBlockEntity anvil) {
        return anvil.getPersistentData().getBoolean(NO_CHIP);
    }

    // ------------------------------------------------------------------ Vanishing Act: armor hidden from other players

    static boolean armorHidden(Player p) {
        if (!p.isInvisible()) return false;
        HabitsApi.PerkHit h = Sk.VANISHING_ACT.hit(p);
        return h != null && Sk.p(h, "hide_armor") > 0;
    }

    /** Rewrites an outgoing equipment packet's list so other players see no armor. Server only. */
    public static List<Pair<EquipmentSlot, ItemStack>> hideArmor(LivingEntity e, List<Pair<EquipmentSlot, ItemStack>> list) {
        if (!(e instanceof Player p) || p.level().isClientSide || !armorHidden(p)) return list;
        List<Pair<EquipmentSlot, ItemStack>> out = new ArrayList<>(list.size());
        for (var pair : list)
            out.add(pair.getFirst().getType() == EquipmentSlot.Type.HUMANOID_ARMOR ? Pair.of(pair.getFirst(), ItemStack.EMPTY) : pair);
        return out;
    }

    /** Tell everyone tracking you what your armor looks like now (after the hidden state flipped). */
    static void resendArmor(Player p) {
        List<Pair<EquipmentSlot, ItemStack>> list = new ArrayList<>(4);
        for (EquipmentSlot s : EquipmentSlot.values())
            if (s.getType() == EquipmentSlot.Type.HUMANOID_ARMOR) list.add(Pair.of(s, p.getItemBySlot(s).copy()));
        ((ServerLevel) p.level()).getChunkSource().broadcast(p, new ClientboundSetEquipmentPacket(p.getId(), hideArmor(p, list)));
    }

    // ------------------------------------------------------------------ per-player outlines (client side)

    /** Entity id -> client game time the outline ends. Written and read on the client thread only. */
    static final Int2LongOpenHashMap GLOW = new Int2LongOpenHashMap();

    static void glow(SurvivalNet.Glow g, long now) {
        GLOW.values().removeIf((java.util.function.LongPredicate) until -> until < now); // ids of mobs that unloaded mid-glow never get looked up again
        for (int id : g.ids()) GLOW.put(id, now + g.ticks());
    }

    /** Spider Sense / Spotlight / Hex Ward outlines, seen only by the owner ({@code Minecraft.shouldEntityAppearGlowing}). */
    public static boolean glowing(Entity e) {
        if (GLOW.isEmpty()) return false;
        long until = GLOW.get(e.getId());
        return until != 0 && until >= e.level().getGameTime();
    }

    public static void clearGlow() {
        GLOW.clear();
    }
}
