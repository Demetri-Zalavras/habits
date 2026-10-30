package com.habits.perk.nature;

import com.habits.core.HabitsApi.PerkHit;
import com.habits.perk.Later;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CaveVines;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

import java.util.List;

/** Trees, flowers and foraging. */
public final class Woods {
    private Woods() {}

    // ================================================================ drops: logs, leaves, flowers, forage finds, coral

    @SubscribeEvent
    public static void onDrops(BlockDropsEvent e) {
        if (!(e.getBreaker() instanceof Player p) || !Nat.ok(p)) return;
        drops(p, e.getLevel(), e.getPos(), e.getState(), e.getDrops(), e);
    }

    static void drops(Player p, ServerLevel l, BlockPos pos, BlockState s, List<ItemEntity> drops, BlockDropsEvent e) {
        PerkHit h;
        if (s.is(BlockTags.LOGS)) {
            if ((h = Nat.hit(p, "ring_counter")) != null && Nat.roll(l, h.value()) && e != null)
                e.setDroppedExperience(e.getDroppedExperience() + Nat.round(l, Nat.lerp(h, "xp", 1, 3)));
            if ((h = Nat.hit(p, "heartwood")) != null && Nat.roll(l, h.value()))
                Nat.effect(p, MobEffects.ABSORPTION, (int) (Nat.lerp(h, "seconds", 10, 30) * 20), (int) Nat.lerp(h, "amp", 0, 1));
            if ((h = Nat.hit(p, "chop_rhythm")) != null && p.getMainHandItem().is(ItemTags.AXES)) chopRhythm(p, h);
        }
        if (s.is(BlockTags.LEAVES) && (h = Nat.hit(p, "sapling_stash")) != null && Nat.roll(l, h.value())) {
            Item sap = saplingOf(s.getBlock());
            if (sap != null) add(l, pos, drops, new ItemStack(sap));
        }
        if (s.is(BlockTags.SMALL_FLOWERS) && (h = Nat.hit(p, "bouquet")) != null && Nat.roll(l, h.value())) {
            var tag = BuiltInRegistries.ITEM.getTag(ItemTags.SMALL_FLOWERS);
            if (tag.isPresent()) {
                var flowers = tag.get().stream().map(f -> f.value()).filter(f -> f != Items.WITHER_ROSE && f != Items.TORCHFLOWER && f != s.getBlock().asItem()).toList();
                if (!flowers.isEmpty()) add(l, pos, drops, new ItemStack(flowers.get(l.random.nextInt(flowers.size()))));
            }
        }
        if (s.is(Blocks.DANDELION) && (h = Nat.hit(p, "dandelion_wish")) != null && Nat.roll(l, h.value())) {
            String[] pool = h.skill().perk().param("effects", "minecraft:luck").split(",");
            int ticks = (int) (Nat.lerp(h, "seconds", 30, 180) * 20);
            Nat.effect(pool[l.random.nextInt(pool.length)]).ifPresent(eff ->
                    Nat.effect(p, eff, ticks, 0));
            l.sendParticles(ParticleTypes.END_ROD, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 12, 0.3, 0.3, 0.3, 0.02);
        }
        for (String m : FORAGE) forage(p, l, pos, s, drops, m);
        if ((s.is(BlockTags.CORAL_BLOCKS) || s.is(BlockTags.CORALS) || s.is(BlockTags.WALL_CORALS))
                && (h = Nat.hit(p, "coral_collector")) != null) {
            Item self = s.getBlock().asItem();
            boolean kept = drops.stream().anyMatch(d -> d.getItem().is(self));
            if (self != Items.AIR && !kept && Nat.roll(l, h.value())) {
                drops.clear();
                add(l, pos, drops, new ItemStack(self));
            }
        }
    }

    static final String[] FORAGE = {"truffle_snout", "desert_forager", "shell_seeker"};

    /** A forage skill: {@code blocks} (IdMatch), optional {@code biome} (IdMatch), {@code items} (weighted list). */
    static void forage(Player p, ServerLevel l, BlockPos pos, BlockState s, List<ItemEntity> drops, String m) {
        PerkHit h = Nat.hit(p, m);
        if (h == null || !Nat.blockMatches(l, h.skill().perk().param("blocks", ""), s)) return;
        String biome = h.skill().perk().param("biome", null);
        if (biome != null && !Nat.matches(l, biome, pos)) return;
        if (Nat.roll(l, h.value())) add(l, pos, drops, Nat.pick(l, h.skill().perk().param("items", "minecraft:stick")));
    }

    static void add(Level l, BlockPos pos, List<ItemEntity> drops, ItemStack s) {
        if (s.isEmpty()) return;
        ItemEntity it = new ItemEntity(l, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, s);
        it.setDefaultPickUpDelay();
        drops.add(it);
    }

    /** oak_leaves -> oak_sapling, plus the odd ones; modded leaves work when they follow the same naming. */
    static Item saplingOf(Block leaves) {
        if (leaves == Blocks.MANGROVE_LEAVES) return Items.MANGROVE_PROPAGULE;
        if (leaves == Blocks.AZALEA_LEAVES) return Items.AZALEA;
        if (leaves == Blocks.FLOWERING_AZALEA_LEAVES) return Items.FLOWERING_AZALEA;
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(leaves);
        if (!id.getPath().endsWith("_leaves")) return null;
        ResourceLocation sap = id.withPath(id.getPath().replace("_leaves", "_sapling"));
        return BuiltInRegistries.ITEM.getOptional(sap).orElse(null);
    }

    /** A log within {@code window} seconds of the last adds a Haste level, up to the skill value. */
    static void chopRhythm(Player p, PerkHit h) {
        var d = Nat.data(p);
        long now = p.level().getGameTime();
        int window = (int) (Nat.lerp(h, "window", 2, 4) * 20);
        int stacks = now - d.getLong("habits_chop_at") <= window ? Math.min(d.getInt("habits_chop") + 1, (int) h.value()) : 1;
        d.putLong("habits_chop_at", now);
        d.putInt("habits_chop", stacks);
        Nat.effect(p, MobEffects.DIG_SPEED, window, stacks - 1);
    }

    // ================================================================ right clicks: berries, hives

    @SubscribeEvent
    public static void onRightClick(PlayerInteractEvent.RightClickBlock e) {
        Player p = e.getEntity();
        if (!(e.getLevel() instanceof ServerLevel l) || e.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND || !Nat.ok(p)) return;
        BlockState s = l.getBlockState(e.getPos());
        PerkHit h;
        boolean sweet = s.getBlock() instanceof SweetBerryBushBlock && s.getValue(SweetBerryBushBlock.AGE) >= 2;
        if ((sweet || CaveVines.hasGlowBerries(s)) && !p.getMainHandItem().is(Items.BONE_MEAL)
                && (h = Nat.hit(p, "berry_picker")) != null && Nat.roll(l, h.value()))
            Nat.give(p, new ItemStack(sweet ? Items.SWEET_BERRIES : Items.GLOW_BERRIES, Nat.round(l, Nat.lerp(h, "extra", 1, 3))));
        if (s.getBlock() instanceof BeehiveBlock && s.getValue(BeehiveBlock.HONEY_LEVEL) >= BeehiveBlock.MAX_HONEY_LEVELS
                && (p.getMainHandItem().is(Items.SHEARS) || p.getMainHandItem().is(Items.GLASS_BOTTLE)) && (h = Nat.hit(p, "bee_dance")) != null) {
            int t = (int) (h.value() * 20), amp = (int) Nat.lerp(h, "amp", 0, 1);
            Nat.effect(p, MobEffects.MOVEMENT_SPEED, t, amp);
            Nat.effect(p, MobEffects.DIG_SPEED, t, amp);
        }
    }

    // ================================================================ planting: old growth

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent e) {
        if (!(e.getEntity() instanceof Player p) || !(e.getLevel() instanceof ServerLevel l) || !(e.getPlacedBlock().getBlock() instanceof SaplingBlock)) return;
        PerkHit h = Nat.hit(p, "old_growth");
        if (h == null) return;
        BlockPos pos = e.getPos().immutable();
        Block sap = e.getPlacedBlock().getBlock();
        Later.run(l, Math.max(1, (int) (h.value() * 20)), () -> grow(l, pos, sap));
    }

    /** Push a sapling through its two stages; it fails like vanilla when there is no room. */
    static void grow(ServerLevel l, BlockPos pos, Block sap) {
        for (int i = 0; i < 2 && l.hasChunkAt(pos); i++) {
            BlockState s = l.getBlockState(pos);
            if (!s.is(sap) || !(sap instanceof SaplingBlock sb)) return;
            sb.advanceTree(l, pos, s, l.random);
        }
        if (!l.getBlockState(pos).is(sap)) l.sendParticles(ParticleTypes.HAPPY_VILLAGER, pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5, 16, 1, 1, 1, 0);
    }

    // ================================================================ ticks

    /** Walk into a trunk or leaves to climb it like a ladder. Both sides: the client moves, the server forgets the fall. */
    static void squirrel(Player p, PerkHit h) {
        if (!p.horizontalCollision || p.isShiftKeyDown() || p.isPassenger() || p.getAbilities().flying) return;
        BlockPos feet = p.blockPosition();
        Direction d = p.getDirection();
        Level l = p.level();
        if (!climbable(l.getBlockState(feet.relative(d))) && !climbable(l.getBlockState(feet.above().relative(d)))) return;
        Vec3 v = p.getDeltaMovement();
        if (l.isClientSide()) p.setDeltaMovement(v.x, Math.max(v.y, h.value() / 20.0), v.z);
        p.resetFallDistance();
    }

    static boolean climbable(BlockState s) {
        return s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES);
    }

    /** Sneak against a log to heal. */
    static void treeHugger(Player p, PerkHit h) {
        if (p.level().isClientSide() || p.tickCount % 20 != 0 || !p.isShiftKeyDown() || p.getHealth() >= p.getMaxHealth()) return;
        BlockPos feet = p.blockPosition();
        for (Direction d : Direction.Plane.HORIZONTAL)
            if (p.level().getBlockState(feet.relative(d)).is(BlockTags.LOGS) || p.level().getBlockState(feet.above().relative(d)).is(BlockTags.LOGS)) {
                p.heal((float) (h.value() / 60));
                return;
            }
    }

    static void mossBed(Player p, PerkHit h) {
        if (p.level().isClientSide() || p.tickCount % 40 != 0 || p.getHealth() >= p.getMaxHealth()) return;
        BlockPos feet = p.blockPosition();
        if (p.level().getBlockState(feet).is(Blocks.MOSS_CARPET) || p.level().getBlockState(feet.below()).is(Blocks.MOSS_BLOCK))
            p.heal((float) (h.value() / 30));
    }

    static void flowerBed(Player p, PerkHit h) {
        if (p.level().isClientSide() || p.tickCount % 40 != 0 || p.getHealth() >= p.getMaxHealth()) return;
        int r = (int) Nat.lerp(h, "radius", 2, 4), need = (int) Math.round(Nat.lerp(h, "flowers", 4, 2)), n = 0;
        for (BlockPos q : BlockPos.betweenClosed(p.blockPosition().offset(-r, -1, -r), p.blockPosition().offset(r, 1, r)))
            if (p.level().getBlockState(q).is(BlockTags.FLOWERS) && ++n >= need) {
                p.heal((float) (h.value() / 30));
                return;
            }
    }

    /** Frost walker for lily pads: pads appear on the water around you and sink again after the skill's seconds. */
    static void lilyStep(Player p, PerkHit h) {
        if (!(p.level() instanceof ServerLevel l) || p.tickCount % 2 != 0 || !p.onGround() || p.isShiftKeyDown()) return;
        int r = (int) Nat.lerp(h, "radius", 1, 2);
        int life = Math.max(20, (int) (h.value() * 20));
        BlockPos feet = p.blockPosition();
        for (BlockPos q : BlockPos.betweenClosed(feet.offset(-r, 0, -r), feet.offset(r, 0, r))) {
            if (!l.isEmptyBlock(q) || !l.getFluidState(q.below()).isSourceOfType(net.minecraft.world.level.material.Fluids.WATER)
                    || !l.getBlockState(q.below()).is(Blocks.WATER)) continue;
            BlockPos at = q.immutable();
            l.setBlockAndUpdate(at, Blocks.LILY_PAD.defaultBlockState());
            Later.run(l, life, () -> {
                if (l.hasChunkAt(at) && l.getBlockState(at).is(Blocks.LILY_PAD)) l.removeBlock(at, false);
            });
        }
    }

    static void forestHeart(Player p, PerkHit h) {
        if (p.level().isClientSide() || p.tickCount % 40 != 0) return;
        var b = p.level().getBiome(p.blockPosition());
        if (!b.is(net.minecraft.tags.BiomeTags.IS_FOREST) && !b.is(net.minecraft.tags.BiomeTags.IS_JUNGLE) && !b.is(net.minecraft.tags.BiomeTags.IS_TAIGA)) return;
        Nat.effect(p, MobEffects.REGENERATION, 60, (int) h.value() - 1);
        Nat.effect(p, MobEffects.DAMAGE_RESISTANCE, 60, (int) Nat.lerp(h, "resist", 0, 1));
    }

    // ================================================================ hits: rootbind, bloom burst

    @SubscribeEvent
    public static void onHurt(LivingIncomingDamageEvent e) {
        if (!(e.getEntity() instanceof Player p) || !(p.level() instanceof ServerLevel l)) return;
        PerkHit h = Nat.hit(p, "rootbind");
        if (h == null || !(e.getSource().getEntity() instanceof LivingEntity a) || a == p || !(a instanceof Enemy)) return;
        if (!l.getBlockState(p.blockPosition().below()).is(BlockTags.DIRT)) return;
        var d = Nat.data(p);
        if (l.getGameTime() < d.getLong("habits_rootbind")) return;
        d.putLong("habits_rootbind", l.getGameTime() + (long) (h.value() * 20));
        int t = (int) (Nat.lerp(h, "seconds", 2, 5) * 20);
        Nat.effect(a, MobEffects.MOVEMENT_SLOWDOWN, t, (int) Nat.lerp(h, "amp", 2, 4));
        Nat.effect(a, MobEffects.WEAKNESS, t, 0);
        l.sendParticles(new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.BLOCK, Blocks.ROOTED_DIRT.defaultBlockState()),
                a.getX(), a.getY() + 0.2, a.getZ(), 24, 0.4, 0.2, 0.4, 0.05);
    }

    @SubscribeEvent
    public static void afterHurt(LivingDamageEvent.Post e) {
        if (!(e.getEntity() instanceof Player p) || !(p.level() instanceof ServerLevel l) || !p.isAlive()) return;
        PerkHit h = Nat.hit(p, "bloom_burst");
        if (h == null || p.getHealth() > p.getMaxHealth() * 0.3f) return;
        var d = Nat.data(p);
        if (l.getGameTime() < d.getLong("habits_bloom")) return;
        int r = (int) Nat.lerp(h, "radius", 3, 6);
        boolean flowers = false;
        for (BlockPos q : BlockPos.betweenClosed(p.blockPosition().offset(-r, -1, -r), p.blockPosition().offset(r, 1, r)))
            if (l.getBlockState(q).is(BlockTags.FLOWERS)) { flowers = true; break; }
        if (!flowers) return;
        d.putLong("habits_bloom", l.getGameTime() + (long) (h.value() * 20));
        p.heal((float) Nat.lerp(h, "heal", 4, 12));
        for (LivingEntity m : l.getEntitiesOfClass(LivingEntity.class, new AABB(p.blockPosition()).inflate(r), m -> m instanceof Enemy)) {
            Vec3 push = m.position().subtract(p.position()).normalize().scale(1.2);
            m.push(push.x, 0.4, push.z);
            m.hurtMarked = true;
        }
        l.sendParticles(ParticleTypes.CHERRY_LEAVES, p.getX(), p.getY() + 1, p.getZ(), 60, r / 2.0, 0.6, r / 2.0, 0.05);
        l.playSound(null, p.blockPosition(), SoundEvents.CHERRY_LEAVES_BREAK, SoundSource.PLAYERS, 1, 0.8f);
    }
}
