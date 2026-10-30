package com.habits.perk.exploration;

import com.habits.core.Category;
import com.habits.core.HabitsApi;
import com.habits.core.Specs;
import com.habits.core.TargetKind;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.EyeOfEnder;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BrushableBlock;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** One-shot finds: fresh loot chests, first visits to biomes and structures, dimension arrivals, brushing, sifting
 * and ender eyes. Also fires the CUSTOM event {@code habits:new_structure}. */
public final class Finds {
    private Finds() {}

    static ResourceLocation CHEST_XP, CHEST_SPEED, CHEST_HEAL, RELIC, MAP;
    static ResourceLocation BIOME_XP, BIOME_HEAL, STRUCT_XP, STRUCT_SHIELD;
    static ResourceLocation ARRIVAL, SHERD, BRUSH_XP, DIG_SENSE, SIFTER, EYE;
    static ResourceLocation LOCKPICK, WORLDSEER, TREASURE_SENSE;

    static void init() {
        CHEST_XP = X.mech("chest_scholar", "XP points per fresh loot chest");
        CHEST_SPEED = X.mech("treasure_rush", "seconds of speed per fresh loot chest");
        CHEST_HEAL = X.mech("breather", "health healed per fresh loot chest");
        RELIC = X.mech("relic_hunter", "% chance a fresh loot chest adds an enchanted book");
        MAP = X.mech("map_maker", "% chance a fresh loot chest adds an explorer map");
        BIOME_XP = X.mech("field_notes", "XP points per biome walked into");
        BIOME_HEAL = X.mech("first_breath", "health healed per biome walked into");
        STRUCT_XP = X.mech("surveyor", "XP points per new structure");
        STRUCT_SHIELD = X.mech("discoverers_pride", "absorption level per new structure");
        ARRIVAL = X.mech("grand_entrance", "seconds invisible after changing dimension");
        SHERD = X.mech("sherd_seeker", "% chance brushing adds a pottery sherd");
        BRUSH_XP = X.mech("patient_hands", "XP points per brushed block");
        DIG_SENSE = X.mech("dig_sense", "radius in blocks that shows hidden suspicious blocks");
        SIFTER = X.mech("sand_sifter", "% chance digging finds a trinket");
        EYE = X.mech("eye_keeper", "% chance a thrown eye of ender never breaks");
        // class skills (Tomb Delver, Worldseer)
        LOCKPICK = X.mech("delver_lockpick", "% chance a fresh loot chest rolls its loot again for you");
        WORLDSEER = X.mech("worldseer", "% chance a new structure gives a map to an unfound one");
        TREASURE_SENSE = X.mech("worldseer_treasure_sense", "radius that shows unopened loot containers when you find a structure");
    }

    // ------------------------------------------------------------------ fresh loot chests

    @SubscribeEvent
    static void onClickBlock(PlayerInteractEvent.RightClickBlock e) {
        if (X.ok(e.getEntity())) freshChest(e.getEntity(), e.getPos());
    }

    /** A container that still has its loot table, opened by this player for the first time. Returns true if it counted. */
    static boolean freshChest(Player p, BlockPos pos) {
        if (!(p.level().getBlockEntity(pos) instanceof RandomizableContainerBlockEntity r) || r.getLootTable() == null) return false;
        // per-player loot (Lootr) keeps the table forever, so remember each chest per player
        if (!X.remember(p, "habits_explo_chests", p.level().dimension().location() + "|" + pos.asLong(), 2048)) return false;
        HabitsApi.PerkHit h;
        if ((h = X.hit(p, CHEST_XP)) != null) p.giveExperiencePoints((int) h.value());
        if ((h = X.hit(p, CHEST_SPEED)) != null)
            p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, (int) (h.value() * 20), (int) X.param(h, "level", 1) - 1));
        if ((h = X.hit(p, CHEST_HEAL)) != null) p.heal((float) h.value());
        if ((h = X.hit(p, RELIC)) != null && X.roll(p, h.value()))
            X.give(p, EnchantmentHelper.enchantItem(p.getRandom(), new ItemStack(Items.BOOK), (int) X.param(h, "levels", 20),
                    p.level().registryAccess(), Optional.empty()));
        if ((h = X.hit(p, MAP)) != null && X.roll(p, h.value())) X.give(p, explorerMap(p, h));
        if ((h = X.hit(p, LOCKPICK)) != null && X.roll(p, h.value())) rollAgain(p, pos, r.getLootTable());
        return true;
    }

    /** Rolls the chest's loot table once more straight into the player's inventory. Returns the stacks given. */
    static int rollAgain(Player p, BlockPos pos, ResourceKey<net.minecraft.world.level.storage.loot.LootTable> key) {
        if (!(p.level() instanceof ServerLevel sl)) return 0;
        var params = new net.minecraft.world.level.storage.loot.LootParams.Builder(sl)
                .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.ORIGIN, net.minecraft.world.phys.Vec3.atCenterOf(pos))
                .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.THIS_ENTITY, p)
                .withLuck(p.getLuck())
                .create(net.minecraft.world.level.storage.loot.parameters.LootContextParamSets.CHEST);
        var items = sl.getServer().reloadableRegistries().getLootTable(key).getRandomItems(params);
        items.forEach(s -> X.give(p, s));
        return items.size();
    }

    /** A map to the nearest unfound structure from a random tag of the {@code structure} param (empty if none in range). */
    static ItemStack explorerMap(Player p, HabitsApi.PerkHit h) {
        if (!(p.level() instanceof ServerLevel sl)) return ItemStack.EMPTY;
        String[] tags = X.str(h, "structure", "#minecraft:on_treasure_maps").split(",");
        String pick = tags[p.getRandom().nextInt(tags.length)].trim().replace("#", "");
        BlockPos at = sl.findNearestMapStructure(TagKey.create(Registries.STRUCTURE, ResourceLocation.parse(pick)), p.blockPosition(),
                (int) X.param(h, "radius", 50), true);
        if (at == null) return ItemStack.EMPTY;
        ItemStack map = MapItem.create(sl, at.getX(), at.getZ(), (byte) 2, true, true);
        MapItem.renderBiomePreviewMap(sl, map);
        MapItemSavedData.addTargetDecoration(map, at, "+", MapDecorationTypes.RED_X);
        return map;
    }

    // ------------------------------------------------------------------ first visits

    @SubscribeEvent
    static void onTick(PlayerTickEvent.Post e) {
        if (e.getEntity().tickCount % 20 == 0 && e.getEntity() instanceof ServerPlayer sp && X.ok(sp)
                && Specs.allows(sp, Category.EXPLORATION)) discover(sp);
    }

    static final java.util.Map<java.util.UUID, java.util.Map<ResourceLocation, Long>> BIOME_SEEN = X.playerMap();
    static final java.util.Map<java.util.UUID, ResourceLocation> BIOME_NOW = X.playerMap();

    /** Biome perks pay on walking into a biome not visited for {@code minutes}; structure perks on standing in a
     * structure (one generated instance) the player was never in. Returns the number of finds. */
    static int discover(ServerPlayer p) {
        int found = 0;
        String dim = p.level().dimension().location().toString();
        ResourceLocation biome = p.level().getBiome(p.blockPosition()).unwrapKey().map(ResourceKey::location).orElse(null);
        ResourceLocation prev = biome == null ? null : BIOME_NOW.put(p.getUUID(), biome);
        if (biome != null && prev != null && !biome.equals(prev)) { // prev null: just logged in, not a walk-in
            long now = p.level().getGameTime();
            Long last = BIOME_SEEN.computeIfAbsent(p.getUUID(), k -> new java.util.HashMap<>()).put(biome, now);
            HabitsApi.PerkHit h;
            if ((h = X.hit(p, BIOME_XP)) != null && fresh(h, last, now)) { p.giveExperiencePoints((int) h.value()); found++; }
            if ((h = X.hit(p, BIOME_HEAL)) != null && fresh(h, last, now)) { p.heal((float) h.value()); found++; }
        }
        var reg = p.level().registryAccess().registryOrThrow(Registries.STRUCTURE);
        for (StructureStart st : Zones.starts(p)) {
            if (!X.remember(p, "habits_explo_starts", dim + "|" + reg.getKey(st.getStructure()) + "|" + st.getChunkPos().toLong(), 2048)) continue;
            found++;
            newStructure(p);
        }
        return found;
    }

    /** Pays the structure perks for one structure instance the player was never in. */
    static void newStructure(ServerPlayer p) {
        X.fire(p, "new_structure", null);
        HabitsApi.PerkHit h;
        if ((h = X.hit(p, STRUCT_XP)) != null) p.giveExperiencePoints((int) h.value());
        if ((h = X.hit(p, STRUCT_SHIELD)) != null && h.value() >= 1)
            p.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, (int) (X.param(h, "seconds", 120) * 20), (int) h.value() - 1));
        if ((h = X.hit(p, WORLDSEER)) != null && X.roll(p, h.value())) X.give(p, explorerMap(p, h));
        if ((h = X.hit(p, TREASURE_SENSE)) != null) senseLoot(p, (int) h.value());
    }

    /** Particles (only this player sees them) over every unopened loot container within {@code r} blocks. Returns how many. */
    static int senseLoot(ServerPlayer p, int r) {
        ServerLevel sl = p.serverLevel();
        BlockPos at = p.blockPosition();
        int n = 0;
        for (int cx = (at.getX() - r) >> 4; cx <= (at.getX() + r) >> 4; cx++)
            for (int cz = (at.getZ() - r) >> 4; cz <= (at.getZ() + r) >> 4; cz++) {
                if (!sl.hasChunk(cx, cz)) continue;
                for (var be : sl.getChunk(cx, cz).getBlockEntities().values()) {
                    BlockPos b = be.getBlockPos();
                    if (!(be instanceof RandomizableContainerBlockEntity rc) || rc.getLootTable() == null || b.distSqr(at) > (double) r * r) continue;
                    sl.sendParticles(p, ParticleTypes.HAPPY_VILLAGER, true, b.getX() + 0.5, b.getY() + 1.2, b.getZ() + 0.5, 12, 0.3, 0.3, 0.3, 0);
                    n++;
                }
            }
        return n;
    }

    private static boolean fresh(HabitsApi.PerkHit h, Long last, long now) {
        return last == null || now - last >= X.param(h, "minutes", 10) * 1200;
    }

    @SubscribeEvent
    static void onDimension(PlayerEvent.PlayerChangedDimensionEvent e) {
        HabitsApi.PerkHit h = X.hit(e.getEntity(), ARRIVAL);
        if (h != null) e.getEntity().addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, (int) (h.value() * 20), 0, true, false, true));
    }

    // ------------------------------------------------------------------ archaeology, sifting, eyes

    /** Called from the BrushableBlockEntity mixin when brushing finishes. */
    public static void brushed(Player p, Level level, BlockPos pos) {
        if (!X.ok(p) || !(level instanceof ServerLevel sl)) return;
        HabitsApi.PerkHit h;
        if ((h = X.hit(p, BRUSH_XP)) != null) p.giveExperiencePoints((int) h.value());
        if ((h = X.hit(p, SHERD)) != null && X.roll(p, h.value())) {
            List<Item> sherds = new ArrayList<>();
            BuiltInRegistries.ITEM.getTagOrEmpty(ItemTags.DECORATED_POT_SHERDS).forEach(i -> sherds.add(i.value()));
            if (!sherds.isEmpty()) Block.popResource(level, pos, new ItemStack(sherds.get(p.getRandom().nextInt(sherds.size()))));
        }
        if ((h = X.hit(p, DIG_SENSE)) != null) {
            int r = (int) h.value();
            for (BlockPos b : BlockPos.betweenClosed(pos.offset(-r, -r, -r), pos.offset(r, r, r)))
                if (level.getBlockState(b).getBlock() instanceof BrushableBlock)
                    sl.sendParticles((ServerPlayer) p, ParticleTypes.WAX_OFF, true, b.getX() + 0.5, b.getY() + 1.1, b.getZ() + 0.5, 6, 0.3, 0.1, 0.3, 0);
        }
    }

    @SubscribeEvent
    static void onBreak(BlockEvent.BreakEvent e) {
        sift(e.getPlayer(), e.getPos(), BuiltInRegistries.BLOCK.getKey(e.getState().getBlock()));
    }

    /** Digging a {@code block} param block may pop one of the {@code items} param. */
    static boolean sift(Player p, BlockPos pos, ResourceLocation block) {
        HabitsApi.PerkHit h = X.hit(p, SIFTER);
        if (h == null || p.isCreative() || !X.matches(h, "block", block, TargetKind.BLOCK, p) || !X.roll(p, h.value())) return false;
        String[] items = X.str(h, "items", "minecraft:gold_nugget").split(",");
        Holder<Item> item = BuiltInRegistries.ITEM.getHolder(ResourceLocation.parse(items[p.getRandom().nextInt(items.length)].trim()))
                .map(r -> (Holder<Item>) r).orElse(null);
        if (item == null) return false;
        Block.popResource(p.level(), pos, new ItemStack(item));
        return true;
    }

    @SubscribeEvent
    static void onJoin(EntityJoinLevelEvent e) {
        if (!(e.getEntity() instanceof EyeOfEnder eye) || e.getLevel().isClientSide() || e.loadedFromDisk()) return;
        Player p = e.getLevel().getNearestPlayer(eye, 2); // the eye spawns at its thrower
        if (p != null) keep(eye, p);
    }

    static boolean keep(EyeOfEnder eye, Player p) {
        HabitsApi.PerkHit h = X.hit(p, EYE);
        if (h != null && X.roll(p, h.value())) eye.surviveAfterDeath = true;
        return eye.surviveAfterDeath;
    }
}
