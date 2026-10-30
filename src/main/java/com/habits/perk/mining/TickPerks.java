package com.habits.perk.mining;

import com.habits.Habits;
import com.habits.core.HabitsApi;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.behavior.BehaviorUtils;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.piglin.PiglinBrute;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BrushableBlockEntity;
import net.minecraft.world.level.block.entity.ConduitBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.VanillaGameEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import net.neoforged.neoforge.event.entity.living.LivingBreatheEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

/** Per-tick mechanics (run through {@code PerkMechanic.tick} only while the skill is active, every one throttled to
 * a fixed interval) plus the passive world hooks that are not about breaking or placing. */
public final class TickPerks {
    private TickPerks() {}

    static Map<String, BiConsumer<Player, HabitsApi.PerkHit>> ticks() {
        Map<String, BiConsumer<Player, HabitsApi.PerkHit>> m = new HashMap<>();
        m.put("stonebreaker", BreakPerks::momentumTick);
        m.put("ore_pulse", (p, h) -> {});
        m.put("debris_diver", Glow::debris);
        m.put("bug_hunter", Glow::bugs);
        m.put("diamond_dowsing", Glow::dowsing);
        m.put("treasure_sense", Glow::treasure);
        m.put("torchlight", TickPerks::torchlight);
        m.put("auto_bridge", TickPerks::autoBridge);
        m.put("dark_sight", TickPerks::darkSight);
        m.put("lantern_bearer", TickPerks::lantern);
        m.put("snowplow", TickPerks::snowplow);
        m.put("web_cutter", TickPerks::webCutter);
        m.put("doorkeeper", Doors::tick);
        m.put("standard_bearer", TickPerks::banner);
        m.put("golden_truce", TickPerks::truce);
        m.put("scribe", Scribe::tick);
        m.put("curator", TickPerks::curator);
        return m;
    }

    // ---------------------------------------------------------------- attributes (all players, every 10 ticks)

    static final ResourceLocation AQUA = Habits.id("mining_aqua_digger"), SOUL = Habits.id("mining_soul_digger"),
            PATH = Habits.id("mining_path_maker");

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || p.tickCount % 10 != 0) return;
        boolean ok = MiningMechanics.allowed(p);
        HabitsApi.PerkHit aqua = ok ? MiningMechanics.hit(p, "aqua_digger") : null;
        modifier(p, Attributes.SUBMERGED_MINING_SPEED, AQUA, aqua == null ? 0 : aqua.value(), AttributeModifier.Operation.ADD_VALUE);
        BlockState under = p.level().getBlockState(p.getBlockPosBelowThatAffectsMyMovement());
        HabitsApi.PerkHit soul = ok ? MiningMechanics.hit(p, "soul_digger") : null;
        // soul speed: undo the soul sand drag (x0.4) and add about +10.5% per level like the enchant
        double soulBonus = soul != null && under.is(BlockTags.SOUL_SPEED_BLOCKS)
                ? (under.is(Blocks.SOUL_SAND) ? 1.5 : 0) + 0.105 * soul.value() : 0;
        modifier(p, Attributes.MOVEMENT_SPEED, SOUL, soulBonus, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
        HabitsApi.PerkHit path = ok ? MiningMechanics.hit(p, "path_maker") : null;
        double pathBonus = path != null && (under.is(Blocks.DIRT_PATH) || p.level().getBlockState(p.blockPosition()).is(Blocks.DIRT_PATH))
                ? MiningMechanics.lerp(path, "speed", 5, 30) / 100 : 0;
        modifier(p, Attributes.MOVEMENT_SPEED, PATH, pathBonus, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
        if (LIGHTS.containsKey(p.getUUID()) && (!ok || MiningMechanics.hit(p, "lantern_bearer") == null)) clearLight(p);
    }

    static void modifier(Player p, Holder<Attribute> attr, ResourceLocation id, double amount, AttributeModifier.Operation op) {
        AttributeInstance inst = p.getAttribute(attr);
        if (inst == null) return;
        AttributeModifier cur = inst.getModifier(id);
        if (amount == 0) { if (cur != null) inst.removeModifier(id); return; }
        if (cur == null || cur.amount() != amount) inst.addOrUpdateTransientModifier(new AttributeModifier(id, amount, op));
    }

    // ---------------------------------------------------------------- torchlight, auto bridge, dark sight

    static void torchlight(Player player, HabitsApi.PerkHit h) {
        if (!(player instanceof ServerPlayer p) || p.tickCount % 10 != 0) return;
        long now = p.level().getGameTime();
        if (now < p.getPersistentData().getLong("habits_torch_ready") || !Work.has(p, Items.TORCH, 1)) return;
        ServerLevel level = p.serverLevel();
        BlockPos feet = p.blockPosition();
        if (level.getBrightness(LightLayer.BLOCK, feet) > h.value() || level.getBrightness(LightLayer.SKY, feet) > h.value()) return;
        BlockState torch = Blocks.TORCH.defaultBlockState();
        boolean placed = Work.placeAs(p, feet, torch, Direction.UP);
        if (!placed) {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockState wall = Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, d.getOpposite());
                if (Work.placeAs(p, feet.above(), wall, d.getOpposite())) { placed = true; break; }
            }
        }
        if (placed) {
            Work.take(p, Items.TORCH);
            p.getPersistentData().putLong("habits_torch_ready", now + (long) (MiningMechanics.lerp(h, "cooldown_s", 10, 1) * 20));
        }
    }

    static void autoBridge(Player player, HabitsApi.PerkHit h) {
        if (!(player instanceof ServerPlayer p) || !p.isShiftKeyDown() || p.getAbilities().flying || !(p.getMainHandItem().getItem() instanceof BlockItem bi)) return;
        var tag = p.getPersistentData();
        double tokens = Math.min(1.5, tag.getDouble("habits_bridge_tokens") + h.value() / 20);
        BlockPos below = p.blockPosition().below();
        if (tokens >= 1 && p.level().getBlockState(below).canBeReplaced() && p.getKnownMovement().horizontalDistanceSqr() > 1e-5) {
            BlockState s = bi.getBlock().defaultBlockState();
            if (s.isCollisionShapeFullBlock(p.level(), below) && Work.has(p, bi, 1) && Work.placeAs(p, below, s, Direction.UP)) {
                Work.take(p, bi);
                tokens -= 1;
            }
        }
        tag.putDouble("habits_bridge_tokens", tokens);
    }

    static void darkSight(Player player, HabitsApi.PerkHit h) {
        if (!(player instanceof ServerPlayer p) || p.tickCount % 20 != 0) return;
        ServerLevel level = p.serverLevel();
        BlockPos eye = BlockPos.containing(p.getEyePosition());
        double maxY = MiningMechanics.lerp(h, "max_y", 0, 320);
        boolean dark = !level.canSeeSky(eye) && level.getMaxLocalRawBrightness(eye) <= 4 && eye.getY() <= maxY;
        var tag = p.getPersistentData();
        long now = level.getGameTime();
        if (!dark) {
            tag.remove("habits_dark_since");
            MobEffectInstance cur = p.getEffect(MobEffects.NIGHT_VISION);
            if (tag.getBoolean("habits_dark_nv") && cur != null && cur.isAmbient()) p.removeEffect(MobEffects.NIGHT_VISION);
            tag.remove("habits_dark_nv");
            return;
        }
        if (!tag.contains("habits_dark_since")) tag.putLong("habits_dark_since", now);
        if (now - tag.getLong("habits_dark_since") < MiningMechanics.lerp(h, "fade_delay", 5, 0) * 20) return;
        MobEffectInstance cur = p.getEffect(MobEffects.NIGHT_VISION);
        if (cur == null || cur.isAmbient()) {
            // 400 ticks keeps it above the 10s flicker point; refreshed every second
            p.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 400, 0, true, false, true));
            tag.putBoolean("habits_dark_nv", true);
        }
    }

    // ---------------------------------------------------------------- lantern bearer (a light block that follows you)

    private static final Map<UUID, Map.Entry<ServerLevel, BlockPos>> LIGHTS = new HashMap<>();

    static void lantern(Player player, HabitsApi.PerkHit h) {
        if (!(player instanceof ServerPlayer p) || p.tickCount % 2 != 0) return;
        ServerLevel level = p.serverLevel();
        BlockPos want = p.blockPosition().above();
        var cur = LIGHTS.get(p.getUUID());
        if (cur != null && cur.getKey() == level && cur.getValue().equals(want)) return;
        clearLight(p);
        if (!level.getBlockState(want).isAir()) want = p.blockPosition();
        if (!level.getBlockState(want).isAir()) return;
        int light = (int) Math.max(1, Math.min(15, h.value()));
        level.setBlock(want, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, light), 3);
        LIGHTS.put(p.getUUID(), Map.entry(level, want.immutable()));
    }

    static void clearLight(Player p) {
        var cur = LIGHTS.remove(p.getUUID());
        if (cur != null && cur.getKey().isLoaded(cur.getValue()) && cur.getKey().getBlockState(cur.getValue()).is(Blocks.LIGHT))
            cur.getKey().setBlock(cur.getValue(), Blocks.AIR.defaultBlockState(), 3);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        clearLight(e.getEntity());
    }

    @SubscribeEvent
    public static void onChangeDim(PlayerEvent.PlayerChangedDimensionEvent e) {
        clearLight(e.getEntity());
    }

    @SubscribeEvent
    public static void onStop(ServerStoppingEvent e) {
        for (UUID id : java.util.List.copyOf(LIGHTS.keySet())) {
            var cur = LIGHTS.remove(id);
            if (cur.getKey().isLoaded(cur.getValue()) && cur.getKey().getBlockState(cur.getValue()).is(Blocks.LIGHT))
                cur.getKey().setBlock(cur.getValue(), Blocks.AIR.defaultBlockState(), 3);
        }
    }

    // ---------------------------------------------------------------- snow, webs

    static void snowplow(Player player, HabitsApi.PerkHit h) {
        if (!(player instanceof ServerPlayer p) || p.tickCount % 5 != 0) return;
        ServerLevel level = p.serverLevel();
        int r = (int) h.value();
        BlockPos c = p.blockPosition();
        int balls = 0;
        for (BlockPos q : BlockPos.betweenClosed(c.offset(-r, 0, -r), c.offset(r, 1, r))) {
            BlockState s = level.getBlockState(q);
            if (s.is(Blocks.SNOW) && Work.removeAs(p, q.immutable())) balls += s.getValue(net.minecraft.world.level.block.SnowLayerBlock.LAYERS);
        }
        if (balls > 0) Work.give(p, new ItemStack(Items.SNOWBALL, Math.min(64, balls)));
    }

    static void webCutter(Player player, HabitsApi.PerkHit h) {
        if (!(player instanceof ServerPlayer p)) return;
        ServerLevel level = p.serverLevel();
        // the slow itself is cut in WebBlockMixin; from radius_level webs within 1 block are cut into string
        if (h.level() < (int) MiningMechanics.lerp(h, "radius_level", 10, 10)) return;
        BlockPos c = p.blockPosition();
        for (BlockPos q : BlockPos.betweenClosed(c.offset(-1, 0, -1), c.offset(1, 1, 1))) {
            if (!level.getBlockState(q).is(Blocks.COBWEB)) continue;
            if (Work.removeAs(p, q.immutable())) Work.give(p, new ItemStack(Items.STRING));
        }
    }

    // ---------------------------------------------------------------- standard bearer

    static void banner(Player player, HabitsApi.PerkHit h) {
        if (!(player instanceof ServerPlayer p) || p.tickCount % 40 != 0) return;
        BlockPos pos = bannerPos(p);
        if (pos == null) return;
        double r = h.value();
        for (Player other : p.level().getEntitiesOfClass(Player.class, new AABB(pos).inflate(r), o -> MiningMechanics.allowed(o))) {
            other.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 80, 0, true, true));
            if (h.level() >= 5) other.addEffect(new MobEffectInstance(MobEffects.DIG_SPEED, 80, 0, true, true));
            if (h.level() >= 10) other.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 80, 0, true, true));
        }
    }

    /** The player's banner if it still stands in this dimension and is loaded. */
    static BlockPos bannerPos(ServerPlayer p) {
        var tag = p.getPersistentData();
        if (!tag.contains("habits_banner_pos") || !tag.getString("habits_banner_dim").equals(p.level().dimension().location().toString())) return null;
        BlockPos pos = BlockPos.of(tag.getLong("habits_banner_pos"));
        if (!p.level().isLoaded(pos)) return null;
        if (!p.level().getBlockState(pos).is(BlockTags.BANNERS)) { tag.remove("habits_banner_pos"); return null; }
        return pos;
    }

    @SubscribeEvent
    public static void onHurt(LivingIncomingDamageEvent e) {
        LivingEntity victim = e.getEntity();
        // standard bearer: hostile mobs inside your banner aura take more damage from you
        if (victim instanceof Enemy && e.getSource().getEntity() instanceof ServerPlayer p) {
            HabitsApi.PerkHit h = MiningMechanics.hit(p, "standard_bearer");
            BlockPos pos = h == null ? null : bannerPos(p);
            if (pos != null && victim.distanceToSqr(Vec3.atCenterOf(pos)) <= h.value() * h.value())
                e.setAmount((float) (e.getAmount() * (1 + MiningMechanics.lerp(h, "mob_damage", 5, 50) / 100)));
        }
        // demolitionist: your own tnt stops hurting you
        if (victim instanceof ServerPlayer p && e.getSource().getDirectEntity() instanceof net.minecraft.world.entity.item.PrimedTnt tnt && tnt.getOwner() == p) {
            HabitsApi.PerkHit h = MiningMechanics.hit(p, "demolitionist");
            if (h != null) e.setAmount((float) (e.getAmount() * (1 - MiningMechanics.lerp(h, "self_cut", 20, 100) / 100)));
        }
    }

    // ---------------------------------------------------------------- piglins

    static void truce(Player player, HabitsApi.PerkHit h) {
        if (!(player instanceof ServerPlayer p) || p.tickCount % 20 != 0) return;
        ServerLevel level = p.serverLevel();
        Piglin giver = null;
        // no anger from mining gold or opening chests: PiglinAiMixin. Here only the gift.
        for (Piglin pl : level.getEntitiesOfClass(Piglin.class, p.getBoundingBox().inflate(8), a -> a.isAlive() && !a.isBaby())) {
            if (pl.getLastHurtByMob() != p && pl.distanceToSqr(p) < 64) { giver = pl; break; }
        }
        long now = level.getGameTime();
        var tag = p.getPersistentData();
        if (giver == null || now < tag.getLong("habits_truce_gift")) return;
        tag.putLong("habits_truce_gift", now + (long) (h.value() * 1200));
        LootParams params = new LootParams.Builder(level).withParameter(LootContextParams.THIS_ENTITY, giver).create(LootContextParamSets.PIGLIN_BARTER);
        for (ItemStack st : level.getServer().reloadableRegistries().getLootTable(BuiltInLootTables.PIGLIN_BARTERING).getRandomItems(params))
            BehaviorUtils.throwItem(giver, st, p.position().add(0, 1, 0));
    }

    @SubscribeEvent
    public static void onTarget(LivingChangeTargetEvent e) {
        // bastion breaker: brutes notice you later
        if (!(e.getEntity() instanceof PiglinBrute brute) || !(e.getNewAboutToBeSetTarget() instanceof ServerPlayer p)) return;
        HabitsApi.PerkHit h = MiningMechanics.hit(p, "bastion_breaker");
        if (h == null || brute.getLastHurtByMob() == p) return;
        double range = brute.getAttributeValue(Attributes.FOLLOW_RANGE) * (1 - MiningMechanics.lerp(h, "range_cut", 10, 60) / 100);
        if (brute.distanceToSqr(p) > range * range) e.setCanceled(true);
    }

    // ---------------------------------------------------------------- curator

    static void curator(Player player, HabitsApi.PerkHit h) {
        if (!(player instanceof ServerPlayer p) || p.tickCount % 10 != 0 || h.level() < (int) MiningMechanics.lerp(h, "label_level", 5, 5)) return;
        Vec3 eye = p.getEyePosition();
        Vec3 end = eye.add(p.getLookAngle().scale(16));
        // ProjectileUtil.getEntityHitResult's pick (nearest frame whose box, grown 0.3, the look ray hits), but through
        // the class-indexed query: the generic one walked every entity in a 16-block box twice a second
        ItemFrame frame = null;
        double best = Double.MAX_VALUE;
        for (ItemFrame f : p.level().getEntitiesOfClass(ItemFrame.class, p.getBoundingBox().expandTowards(p.getLookAngle().scale(16)).inflate(1))) {
            var at = f.getBoundingBox().inflate(0.3).clip(eye, end);
            if (at.isEmpty()) continue;
            double d = eye.distanceToSqr(at.get());
            if (d < best) { best = d; frame = f; }
        }
        if (frame == null || frame.getItem().isEmpty()) return;
        BlockPos behind = frame.getPos().relative(frame.getDirection().getOpposite());
        if (!(p.level().getBlockEntity(behind) instanceof Container c)) return;
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) if (ItemStack.isSameItem(c.getItem(i), frame.getItem())) n += c.getItem(i).getCount();
        p.displayClientMessage(Component.translatable("habits.mining.curator_count", n, frame.getItem().getHoverName()), true);
    }

    // ---------------------------------------------------------------- world hooks

    /** Gravel guard: sand or gravel that would land on you turns into an item. */
    @SubscribeEvent
    public static void onFallingJoin(EntityJoinLevelEvent e) {
        if (!(e.getEntity() instanceof FallingBlockEntity f) || !(e.getLevel() instanceof ServerLevel level)
                || !(f.getBlockState().getBlock() instanceof FallingBlock)) return;
        for (ServerPlayer p : level.players()) {
            HabitsApi.PerkHit h = MiningMechanics.hit(p, "gravel_guard");
            if (h == null || p.getY() > f.getY() || f.getY() - p.getY() > 32) continue;
            double r = h.value() + 0.8;
            double dx = p.getX() - f.getX(), dz = p.getZ() - f.getZ();
            if (dx * dx + dz * dz > r * r) continue;
            ItemStack item = new ItemStack(f.getBlockState().getBlock().asItem());
            if (h.level() >= (int) MiningMechanics.lerp(h, "inventory_level", 10, 10)) Work.give(p, item);
            else net.minecraft.world.level.block.Block.popResource(level, p.blockPosition(), item);
            e.setCanceled(true);
            return;
        }
    }

    /** Candlelight: no natural hostile spawns near your warding candles while they are lit. Chunk-indexed lookup. */
    @SubscribeEvent
    public static void onSpawn(FinalizeSpawnEvent e) {
        if (!(e.getEntity() instanceof Enemy) || e.getSpawnType() != net.minecraft.world.entity.MobSpawnType.NATURAL
                || !(e.getLevel().getLevel() instanceof ServerLevel level)) return;
        MiningData data = MiningData.get(level);
        if (data.candles.isEmpty()) return;
        BlockPos at = BlockPos.containing(e.getX(), e.getY(), e.getZ());
        int cx = at.getX() >> 4, cz = at.getZ() >> 4;
        java.util.List<Long> dead = null;
        for (int x = cx - 1; x <= cx + 1; x++)
            for (int z = cz - 1; z <= cz + 1; z++) {
                var m = data.candles.get(net.minecraft.world.level.ChunkPos.asLong(x, z));
                if (m == null) continue;
                for (var en : m.long2IntEntrySet()) {
                    BlockPos c = BlockPos.of(en.getLongKey());
                    if (c.distSqr(at) > (long) en.getIntValue() * en.getIntValue()) continue;
                    if (!level.isLoaded(c)) continue;
                    BlockState s = level.getBlockState(c);
                    if (!s.is(BlockTags.CANDLES)) { if (dead == null) dead = new java.util.ArrayList<>(); dead.add(en.getLongKey()); continue; }
                    if (s.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT)) {
                        e.setSpawnCancelled(true);
                        return;
                    }
                }
            }
        if (dead != null) for (long l : dead) data.removeCandle(l);
    }

    /** Aqua digger Lv5+: air lasts longer while you mine underwater (within 5s of a break). */
    @SubscribeEvent
    public static void onBreathe(LivingBreatheEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || e.canBreathe()) return;
        HabitsApi.PerkHit h = MiningMechanics.hit(p, "aqua_digger");
        if (h == null || h.level() < (int) MiningMechanics.lerp(h, "air_level", 5, 5)) return;
        long[] last = BreakPerks.LAST_BREAK.get(p.getUUID());
        if (last == null || p.level().getGameTime() - last[1] > 100) return;
        if (p.tickCount % 2 == 0) e.setConsumeAirAmount(0);
    }
}
