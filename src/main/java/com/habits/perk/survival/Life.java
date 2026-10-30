package com.habits.perk.survival;

import java.util.UUID;
import java.util.Map;
import java.util.HashMap;
import com.habits.core.HabitsApi;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Silverfish;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.LodestoneTracker;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.EnderManAngerEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingEvent;
import net.neoforged.neoforge.event.entity.living.LivingExperienceDropEvent;
import net.neoforged.neoforge.event.entity.living.LivingUseTotemEvent;
import net.neoforged.neoforge.event.entity.player.CanPlayerSleepEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerSetSpawnEvent;
import net.neoforged.neoforge.event.entity.player.PlayerWakeUpEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.util.Optional;

/** Sleep, death, respawn, totems, mob behaviour around the player, allies and barters. */
public final class Life {
    /** Items another mod destroys on death (StarSword's wipe): Notch's Blessing and the pack rat never carry them past it. */
    public static final net.minecraft.tags.TagKey<net.minecraft.world.item.Item> LOST_ON_DEATH = net.minecraft.tags.ItemTags.create(com.habits.Habits.id("lost_on_death"));
    private Life() {}

    static final String OWNER = "habits_owner", ALLY_UNTIL = "habits_ally_until";

    // ================================================================== sleep

    /** Day index of each player's last bed card roll: [0] blocked by monsters, [1] day nap. Spamming the bed rolled
     * the card on every click; now each bed trigger rolls once per in-game day. */
    private static final Map<UUID, long[]> BED_ROLLS = new HashMap<>();

    static boolean firstToday(Player p, int kind) {
        long day = p.level().getDayTime() / 24000L;
        long[] last = BED_ROLLS.computeIfAbsent(p.getUUID(), u -> new long[] {-1, -1});
        if (last[kind] == day) return false;
        last[kind] = day;
        return true;
    }

    static void forget(Player p) {
        BED_ROLLS.remove(p.getUUID());
        logout(p);
    }

    static void clearServer() {
        BED_ROLLS.clear();
    }

    @SubscribeEvent
    public static void canSleep(CanPlayerSleepEvent e) {
        ServerPlayer p = e.getEntity();
        Player.BedSleepingProblem problem = e.getProblem();
        if (problem == Player.BedSleepingProblem.NOT_SAFE) {
            if (firstToday(p, 0)) Fx.fire(p, "sleep_blocked");
            HabitsApi.PerkHit h = Sk.LIGHT_SLEEPER.hit(p);
            if (h == null) return;
            BlockPos bed = e.getPos();
            var monsters = p.level().getEntitiesOfClass(Monster.class, new AABB(bed).inflate(8, 5, 8), m -> m.isPreventingPlayerRest(p));
            int r = (int) h.value();
            boolean blocked = false;
            for (Monster m : monsters) {
                Fx.effect(m, MobEffects.GLOWING, Sk.p(h, "glow_seconds"), 0);
                if (r == 0 ? m.getTarget() == p : Math.abs(m.getX() - bed.getX()) <= r && Math.abs(m.getZ() - bed.getZ()) <= r && Math.abs(m.getY() - bed.getY()) <= Math.min(5, r))
                    blocked = true;
            }
            if (!blocked) e.setProblem(null);
        } else if (problem == Player.BedSleepingProblem.NOT_POSSIBLE_NOW) {
            if (firstToday(p, 1)) Fx.fire(p, "day_nap");
            HabitsApi.PerkHit h = Sk.POWER_NAP.hit(p);
            CompoundTag d = Sk.data(p);
            long now = p.level().getGameTime();
            if (h == null || now < d.getLong("napReady")) return;
            d.putLong("napReady", now + (long) (Sk.p(h, "cooldown") * 20));
            Fx.effect(p, MobEffects.DIG_SPEED, h.value(), Sk.p(h, "haste_amp"));
            Fx.effect(p, MobEffects.MOVEMENT_SPEED, h.value(), Sk.p(h, "speed_amp"));
        }
    }

    @SubscribeEvent
    public static void wake(PlayerWakeUpEvent e) {
        Player p = e.getEntity();
        if (p.level().isClientSide || e.wakeImmediately()) return;
        HabitsApi.PerkHit h = Sk.DEEP_SLEEPER.hit(p);
        if (h == null) return;
        Fx.effect(p, MobEffects.REGENERATION, h.value(), Sk.p(h, "amp"));
        p.getFoodData().eat((int) Sk.p(h, "food"), 0.3f);
    }

    // ================================================================== death and respawn

    @SubscribeEvent
    public static void death(LivingDeathEvent e) {
        if (!(e.getEntity() instanceof Player p) || p.level().isClientSide) return;
        if (ninthLife(p, e.getSource())) {
            e.setCanceled(true);
            return;
        }
        Sk.data(p).putBoolean("killedByCommand", e.getSource().is(DamageTypes.GENERIC_KILL));
    }

    /**
     * Ninth Life: a death that gets this far (past Last Stand, totems and void bounce) is undone once per cooldown. You
     * are put back on the last safe ground with your items, levels and {@code hearts} hearts. Never for /kill.
     */
    static boolean ninthLife(Player p, net.minecraft.world.damagesource.DamageSource src) {
        HabitsApi.PerkHit h = Sk.NINTH_LIFE.hit(p);
        if (h == null || p.isSpectator() || src.is(DamageTypes.GENERIC_KILL)) return false;
        CompoundTag d = Sk.data(p);
        long now = p.level().getGameTime();
        if (now < d.getLong("ninthReady")) return false;
        net.minecraft.world.phys.Vec3 safe = Defense.safePos(p);
        if (safe == null && p.getY() < p.level().getMinBuildHeight()) return false; // in the void with no ground to go back to
        d.putLong("ninthReady", now + (long) (h.value() * 60 * 20));
        if (safe != null) {
            p.moveTo(safe.x, safe.y, safe.z); // server side now (fake players ignore teleportTo); teleportTo tells the client
            p.teleportTo(safe.x, safe.y, safe.z);
        }
        p.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        p.hurtMarked = true;
        p.resetFallDistance();
        p.clearFire();
        p.setHealth((float) Math.min(p.getMaxHealth(), Sk.p(h, "hearts") * 2));
        if (p.level() instanceof ServerLevel l) {
            l.sendParticles(net.minecraft.core.particles.ParticleTypes.TOTEM_OF_UNDYING, p.getX(), p.getY() + 1, p.getZ(), 30, 0.4, 0.6, 0.4, 0.3);
            l.playSound(null, p.getX(), p.getY(), p.getZ(), net.minecraft.sounds.SoundEvents.TOTEM_USE, net.minecraft.sounds.SoundSource.PLAYERS, 1, 1.2f);
        }
        p.displayClientMessage(Component.translatable("habits.survival.ninth_life"), true);
        return true;
    }

    @SubscribeEvent
    public static void drops(LivingDropsEvent e) {
        LivingEntity dead = e.getEntity();
        if (dead.level().isClientSide) return;
        if (dead instanceof Raider r && r.isPatrolLeader() && e.getSource().getEntity() instanceof Player killer) {
            HabitsApi.PerkHit h = Sk.OMEN_READER.hit(killer);
            if (h != null && Sk.roll(killer, h.value()))
                e.getDrops().add(new ItemEntity(dead.level(), dead.getX(), dead.getY(), dead.getZ(), new ItemStack(Items.EMERALD, (int) Math.round(Sk.p(h, "emeralds")))));
            return;
        }
        if (!(dead instanceof Player p)) return;
        CompoundTag d = Sk.data(p);
        if (Sk.NOTCHS_BLESSING.hit(p) != null && d.getLong("insuredUntil") > p.level().getGameTime()) {
            ListTag stash = new ListTag();
            for (ItemEntity ie : e.getDrops()) if (!ie.getItem().is(LOST_ON_DEATH)) stash.add(ie.getItem().save(p.registryAccess()));
            d.put("stash", stash);
            d.remove("insuredUntil");
            e.setCanceled(true);
            return;
        }
        double minutes = Sk.GRAVE_MAP.v(p);
        if (minutes > 0) for (ItemEntity ie : e.getDrops()) ie.lifespan += (int) (minutes * 60 * 20);
    }

    @SubscribeEvent
    public static void xpDrop(LivingExperienceDropEvent e) {
        if (!(e.getEntity() instanceof Player p) || p.level().isClientSide) return;
        double keep = Math.min(100, Sk.SOUL_KEEPER.v(p)); // Potent can push it past 100
        // keepInventory (or nothing dropping) already keeps the XP; storing it too hands it out twice on every /kill
        if (keep <= 0 || e.getDroppedExperience() <= 0) return;
        Sk.data(p).putInt("keptXp", (int) (p.totalExperience * keep / 100));
        e.setDroppedExperience((int) (e.getDroppedExperience() * (1 - keep / 100)));
    }

    /** Our scratch data survives death; the stash and kept XP are handed back here. */
    @SubscribeEvent
    public static void clone(PlayerEvent.Clone e) {
        Player old = e.getOriginal(), now = e.getEntity();
        CompoundTag d = old.getPersistentData().getCompound("habits_survival").copy();
        d.remove("noFall");
        d.remove("thunder");
        d.remove("rage");
        d.remove("armorHidden"); // the new body has not been hidden from anyone yet; Ticks resends it
        d.remove("respawnTarget");
        d.remove("clutchSince"); // the Ninth Life clutch clock needs you alive the whole 30 seconds
        now.getPersistentData().put("habits_survival", d);
        if (!e.isWasDeath()) { // End exit: health was capped before temporary max-health bonuses came back
            d.putFloat("respawnTarget", old.getHealth());
            return;
        }
        for (Tag t : d.getList("stash", Tag.TAG_COMPOUND))
            ItemStack.parse(now.registryAccess(), t).ifPresent(s -> Fx.give(now, s));
        d.remove("stash");
        int xp = d.getInt("keptXp");
        if (xp > 0) now.giveExperiencePoints(xp);
        d.remove("keptXp");
    }

    /** Start Ticks.respawnTopUp: health is set before late max-health bonuses (ours and other mods') are back. */
    static void topUp(Player p) {
        CompoundTag d = Sk.data(p);
        d.putFloat("respawnHp", p.getHealth());
        d.putLong("respawnUntil", p.level().getGameTime() + 60);
    }

    /** Logout keeps the real health; login loads it capped to max health without the temporary bonuses. */
    static void logout(Player p) {
        if (p.isAlive()) Sk.data(p).putFloat("logoutHp", p.getHealth());
    }

    @SubscribeEvent
    public static void login(PlayerEvent.PlayerLoggedInEvent e) {
        Player p = e.getEntity();
        CompoundTag d = Sk.data(p);
        if (!d.contains("logoutHp")) return;
        if (p.isAlive()) {
            d.putFloat("respawnTarget", d.getFloat("logoutHp"));
            topUp(p);
        }
        d.remove("logoutHp");
    }

    @SubscribeEvent
    public static void respawn(PlayerEvent.PlayerRespawnEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        topUp(p);
        if (e.isEndConquered()) return;
        CompoundTag d = Sk.data(p);
        HabitsApi.PerkHit h;
        if ((h = Sk.PHOENIX.hit(p)) != null) {
            Fx.effect(p, MobEffects.REGENERATION, h.value(), Sk.p(h, "regen_amp"));
            Fx.effect(p, MobEffects.DAMAGE_RESISTANCE, h.value(), Sk.p(h, "resist_amp"));
            Fx.effect(p, MobEffects.MOVEMENT_SPEED, h.value(), Sk.p(h, "speed_amp"));
        }
        if (Sk.GRAVE_MAP.hit(p) != null) {
            Optional<GlobalPos> death = p.getLastDeathLocation();
            death.ifPresent(pos -> {
                ItemStack compass = new ItemStack(Items.COMPASS);
                compass.set(DataComponents.LODESTONE_TRACKER, new LodestoneTracker(Optional.of(pos), false));
                compass.set(DataComponents.CUSTOM_NAME, Component.translatable("habits.survival.grave_compass").withStyle(s -> s.withItalic(false)));
                Fx.give(p, compass);
            });
        }
        if (d.getBoolean("killedByCommand") && (h = Sk.BACK_FOR_MORE.hit(p)) != null)
            Fx.effect(p, MobEffects.DAMAGE_RESISTANCE, h.value(), Sk.p(h, "amp"));
        d.remove("killedByCommand");
        if ((h = Sk.ANCHOR_BOUND.hit(p)) != null && p.getRespawnPosition() != null && Sk.roll(p, h.value())) {
            ServerLevel level = p.server.getLevel(p.getRespawnDimension());
            BlockPos at = p.getRespawnPosition();
            if (level != null && level.isLoaded(at)) {
                BlockState s = level.getBlockState(at);
                if (s.is(Blocks.RESPAWN_ANCHOR) && s.getValue(RespawnAnchorBlock.CHARGE) < RespawnAnchorBlock.MAX_CHARGES)
                    level.setBlockAndUpdate(at, s.setValue(RespawnAnchorBlock.CHARGE, s.getValue(RespawnAnchorBlock.CHARGE) + 1));
            }
        }
    }

    @SubscribeEvent
    public static void setSpawn(PlayerSetSpawnEvent e) {
        Player p = e.getEntity();
        if (p.level().isClientSide || e.getNewSpawn() == null || e.isForced()) return;
        ServerLevel level = p.getServer() == null ? null : p.getServer().getLevel(e.getSpawnLevel());
        if (level != null && level.getBlockState(e.getNewSpawn()).is(Blocks.RESPAWN_ANCHOR)) Fx.fire(p, "anchor_spawn");
    }

    @SubscribeEvent
    public static void totem(LivingUseTotemEvent e) {
        if (!(e.getEntity() instanceof Player p) || p.level().isClientSide) return;
        Sk.data(p).putLong("totemTick", p.level().getGameTime()); // Effects lengthens the totem's buffs this tick
        InteractionHand hand = e.getHandHolding();
        boolean keep = Sk.roll(p, Sk.TOTEM_SAINT.hit(p) == null ? 0 : Sk.p(Sk.TOTEM_SAINT.hit(p), "keep"));
        Fx.later(p.getServer(), () -> {
            if (!p.isAlive()) return;
            Fx.fire(p, "totem_pop");
            if (!keep) return;
            if (p.getItemInHand(hand).isEmpty()) p.setItemInHand(hand, new ItemStack(Items.TOTEM_OF_UNDYING));
            else Fx.give(p, new ItemStack(Items.TOTEM_OF_UNDYING));
        });
    }

    // ================================================================== Thunderstruck storm call (Lv10)

    @SubscribeEvent
    public static void stormCallBlock(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock e) {
        stormCall(e.getEntity(), net.minecraft.world.phys.Vec3.atBottomCenterOf(e.getPos().above()));
    }

    @SubscribeEvent
    public static void stormCallEntity(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.EntityInteract e) {
        stormCall(e.getEntity(), e.getTarget().position());
    }

    /** Sneak + right-click with an empty hand during a thunderstorm calls a real bolt on the spot. */
    static void stormCall(Player p, net.minecraft.world.phys.Vec3 at) {
        if (p.level().isClientSide || !p.isShiftKeyDown() || !p.getMainHandItem().isEmpty() || !p.level().isThundering()) return;
        HabitsApi.PerkHit h = Sk.THUNDERSTRUCK.hit(p);
        if (h == null || Sk.p(h, "storm_call") <= 0) return;
        CompoundTag d = Sk.data(p);
        long now = p.level().getGameTime();
        if (now < d.getLong("stormReady")) return;
        d.putLong("stormReady", now + (long) (Sk.p(h, "storm_cooldown") * 20));
        net.minecraft.world.entity.LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(p.level());
        if (bolt == null) return;
        bolt.moveTo(at);
        if (p instanceof ServerPlayer sp) bolt.setCause(sp);
        p.level().addFreshEntity(bolt);
    }

    // ================================================================== mobs around the player

    @SubscribeEvent
    public static void enderAnger(EnderManAngerEvent e) {
        HabitsApi.PerkHit h = Sk.PUMPKIN_HEAD.hit(e.getPlayer());
        if (h != null && Sk.p(h, "calm") > 0) e.setCanceled(true);
    }

    @SubscribeEvent
    public static void visibility(LivingEvent.LivingVisibilityEvent e) {
        if (!(e.getEntity() instanceof Player p) || p.level().isClientSide || !Sk.anyActive(p)) return;
        if (p.isInvisible()) {
            double v = Sk.VANISHING_ACT.v(p);
            if (v > 0) e.modifyVisibility(1 - v / 100);
        }
        HabitsApi.PerkHit h = Sk.LEATHER_LOVER.hit(p);
        if (h != null && Ticks.fullLeather(p)) e.modifyVisibility(1 - Sk.p(h, "visibility_cut") / 100);
    }

    @SubscribeEvent
    public static void changeTarget(LivingChangeTargetEvent e) {
        if (!(e.getNewAboutToBeSetTarget() instanceof Player p) || p.level().isClientSide) return;
        LivingEntity mob = e.getEntity();
        if ((mob instanceof Slime || mob instanceof Silverfish) && mob.getPersistentData().hasUUID(OWNER)
                && mob.getPersistentData().getUUID(OWNER).equals(p.getUUID())) {
            e.setCanceled(true);
            return;
        }
        if (mob.getType().is(EntityTypeTags.ZOMBIES) && mob.getLastHurtByMob() != p
                && Sk.GHOUL_GUT.hit(p) != null && Sk.data(p).getLong("ghoulUntil") > p.level().getGameTime())
            e.setCanceled(true);
    }

    // ================================================================== allies (Slime Heart, Hive Mind) and barters

    @SubscribeEvent
    public static void join(EntityJoinLevelEvent e) {
        if (e.getLevel().isClientSide || e.loadedFromDisk()) return;
        Entity ent = e.getEntity();
        if (ent instanceof Silverfish fish) {
            for (Player p : e.getLevel().players()) {
                if (p.distanceToSqr(fish) > 9 || !p.hasEffect(MobEffects.INFESTED)) continue;
                HabitsApi.PerkHit h = Sk.HIVE_MIND.hit(p);
                if (h != null) makeAlly(fish, p, h.value());
                return;
            }
        } else if (ent instanceof ItemEntity item && item.getOwner() instanceof Piglin piglin && piglin.isAdult()) {
            barter(item, piglin);
        }
    }

    static void makeAlly(Mob mob, Player owner, double seconds) {
        mob.getPersistentData().putUUID(OWNER, owner.getUUID());
        mob.getPersistentData().putLong(ALLY_UNTIL, mob.level().getGameTime() + (long) (seconds * 20));
        if (mob.getTarget() == owner) mob.setTarget(null);
    }

    /** Slime Heart: while Oozing, a hit can squeeze a small friendly slime out of you (see docs: vanilla only oozes on a mob's death). */
    static void oozeSlime(Player p, HabitsApi.PerkHit h) {
        Slime slime = EntityType.SLIME.create(p.level());
        if (slime == null) return;
        slime.setSize(1, true);
        slime.moveTo(p.getX(), p.getY() + 0.5, p.getZ(), p.getRandom().nextFloat() * 360, 0);
        makeAlly(slime, p, h.value());
        p.level().addFreshEntity(slime);
    }

    /** Ally brains: every 10 ticks pick the nearest monster; slimes deal their touch damage to it themselves. */
    @SubscribeEvent
    public static void allyTick(EntityTickEvent.Post e) {
        Entity ent = e.getEntity();
        if (!(ent instanceof Slime || ent instanceof Silverfish) || ent.level().isClientSide || ent.tickCount % 10 != 0) return;
        Mob mob = (Mob) ent;
        CompoundTag t = mob.getPersistentData();
        if (!t.contains(ALLY_UNTIL)) return;
        if (t.getLong(ALLY_UNTIL) < mob.level().getGameTime()) {
            // Slime Heart's slimes exist only for the ally time; Hive Mind silverfish were wild and just turn back
            if (mob instanceof Slime) unsummon(mob);
            return;
        }
        LivingEntity target = mob.getTarget();
        if (target == null || !target.isAlive() || target instanceof Player || !(target instanceof Enemy) || isAlly(target)) {
            target = mob.level().getNearestEntity(Monster.class, net.minecraft.world.entity.ai.targeting.TargetingConditions.forCombat()
                            .selector(m -> !isAlly(m)), mob, mob.getX(), mob.getY(), mob.getZ(), mob.getBoundingBox().inflate(12));
            if (target != null) mob.setTarget(target);
        }
        if (mob instanceof Slime slime && target != null && slime.getBoundingBox().inflate(0.4).intersects(target.getBoundingBox()))
            target.hurt(slime.damageSources().mobAttack(slime), (float) slime.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE));
    }

    /** The ally time ran out: a puff of smoke and it is gone (no split, no drops). */
    static void unsummon(Mob mob) {
        ((ServerLevel) mob.level()).sendParticles(net.minecraft.core.particles.ParticleTypes.POOF, mob.getX(), mob.getY() + mob.getBbHeight() / 2,
                mob.getZ(), 10, 0.25, 0.25, 0.25, 0.02);
        mob.discard();
    }

    static boolean isAlly(LivingEntity e) {
        return e.getPersistentData().contains(ALLY_UNTIL);
    }

    static void barter(ItemEntity item, Piglin piglin) {
        long now = piglin.level().getGameTime();
        CompoundTag t = piglin.getPersistentData();
        if (t.getLong("habits_barter_tick") != now) {
            t.putLong("habits_barter_tick", now);
            Player p = piglin.level().getNearestPlayer(piglin, 16);
            t.putBoolean("habits_barter_double", p != null && Sk.roll(p, Sk.GOLD_STANDARD.v(p)));
        }
        if (t.getBoolean("habits_barter_double")) {
            ItemEntity copy = new ItemEntity(item.level(), item.getX(), item.getY(), item.getZ(), item.getItem().copy());
            copy.setDeltaMovement(item.getDeltaMovement());
            copy.setDefaultPickUpDelay();
            item.level().addFreshEntity(copy); // no owner: does not recurse into barter()
        }
    }
}
