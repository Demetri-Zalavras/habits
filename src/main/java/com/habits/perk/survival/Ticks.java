package com.habits.perk.survival;

import com.habits.core.HabitsApi;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.EvokerFangs;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterials;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CampfireCookingRecipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * The one player tick for survival. Cost per player per tick when nothing is owned: one alive check, one weak-map read and
 * a few field reads; the once-a-second block (card triggers for everyone) is spread across ticks by player id. All entity
 * queries are AABB lookups no bigger than the perk's radius, run at most once a second and only while their condition holds.
 */
public final class Ticks {
    private Ticks() {}

    @SubscribeEvent
    public static void tick(PlayerTickEvent.Post e) {
        Player p = e.getEntity();
        if (!Fx.acts(p)) return;
        if (p.level().isClientSide) {
            if (p.isLocalPlayer() && Sk.anyActive(p)) Moves.client(p);
            return;
        }
        boolean owner = Sk.anyActive(p);
        if (owner) {
            everyTick(p);
        }
        if ((p.tickCount + p.getId()) % 20 == 0) everySecond(p, owner);
    }

    // ------------------------------------------------------------------ every tick, owners only (field reads, no queries)

    static void everyTick(Player p) {
        var d = Sk.data(p);
        if ((p.tickCount & 3) == 0 && d.getInt("phyShields") > 0 && Sk.PHYLACTERY.hit(p) != null) lichOrbit(p, d.getInt("phyShields"));
        var fd = p.getFoodData();
        float refund = d.getFloat("healRefund");
        if (refund > 0) {
            fd.setExhaustion(Math.max(0, fd.getExhaustionLevel() - refund));
            d.remove("healRefund");
        }
        double burn = Sk.SLOW_BURN.v(p);
        float ex = fd.getExhaustionLevel();
        if (burn > 0) {
            float last = d.getFloat("lastEx");
            // FoodData took 4 off only if it spent a point; any other drop (a refund, another mod) gained nothing
            boolean spent = fd.getFoodLevel() < d.getInt("lastFl") || fd.getSaturationLevel() < d.getFloat("lastSat");
            float gained = ex >= last ? ex - last : spent ? ex + 4 - last : 0;
            if (gained > 0 && gained < 4) {
                ex = Math.max(0, ex - gained * (float) (burn / 100));
                fd.setExhaustion(ex);
            }
        }
        d.putFloat("lastEx", ex);
        d.putInt("lastFl", fd.getFoodLevel());
        d.putFloat("lastSat", fd.getSaturationLevel());
        if (p.isOnFire()) {
            double cut = Sk.EMBER_SKIN.v(p);
            // burning lasts (1 - cut) as long: take extra ticks off, fractional part by chance
            if (cut > 0 && cut < 100) {
                double extra = 1 / (1 - cut / 100) - 1;
                int whole = (int) extra + (p.getRandom().nextDouble() < extra - (int) extra ? 1 : 0);
                p.setRemainingFireTicks(Math.max(0, p.getRemainingFireTicks() - whole));
            }
        }
        if (p.isInPowderSnow && p.getTicksFrozen() > 0 && Sk.roll(p, Sk.SNOW_BORN.v(p))) p.setTicksFrozen(p.getTicksFrozen() - 1);
        // ponytail: only owners tick here, so losing every survival skill while hidden leaves armor hidden until the next gear change
        boolean hide = SurvivalHooks.armorHidden(p);
        if (hide != d.getBoolean("armorHidden")) {
            d.putBoolean("armorHidden", hide);
            SurvivalHooks.resendArmor(p);
        }
        if (p.hasEffect(MobEffects.LEVITATION) && p.isShiftKeyDown()) {
            HabitsApi.PerkHit h = Sk.GRAVITY_ANCHOR.hit(p);
            if (h != null && Sk.p(h, "sneak_clear") > 0) p.removeEffect(MobEffects.LEVITATION);
        }
        HabitsApi.PerkHit abs = Sk.ABSORBER.hit(p);
        if (abs != null) {
            boolean has = p.getAbsorptionAmount() > 0;
            if (!has && d.getBoolean("hadAbs") && p.hurtTime > 0) {
                double r = Sk.p(abs, "shock_radius");
                for (LivingEntity m : p.level().getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(r), m -> m != p && m instanceof Enemy))
                    Fx.knockAway(p, m, Sk.p(abs, "shock_push"));
                particles(p, ParticleTypes.EXPLOSION, p.position(), 1);
            }
            d.putBoolean("hadAbs", has);
        }
        HabitsApi.PerkHit sea = Sk.SEA_BLESSED.hit(p);
        if (sea != null) {
            boolean wet = p.isInWater();
            if (!wet && d.getBoolean("wasWet")) {
                for (var eff : java.util.List.of(MobEffects.CONDUIT_POWER, MobEffects.DOLPHINS_GRACE)) {
                    MobEffectInstance i = p.getEffect(eff);
                    if (i != null && !i.isInfiniteDuration())
                        p.addEffect(new MobEffectInstance(i.getEffect(), i.getDuration() + (int) (sea.value() * 20), i.getAmplifier(), i.isAmbient(), i.isVisible(), i.showIcon()));
                }
            }
            d.putBoolean("wasWet", wet);
        }
        HabitsApi.PerkHit guard = Sk.QUICK_GUARD.hit(p);
        Fx.attr(p, Attributes.MOVEMENT_SPEED, Sk.QUICK_GUARD.id, guard != null && p.isBlocking() ? Sk.p(guard, "speed") / 100 : 0,
                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
        if (p.tickCount % 2 == 0) lavaWalk(p);
        if (p.tickCount % 4 == 0 && p.isSprinting()) fireTrail(p);
    }

    static void lavaWalk(Player p) {
        HabitsApi.PerkHit h = Sk.LAVA_WALKER.hit(p);
        if (h == null || !p.onGround()) return;
        int r = (int) Sk.p(h, "radius");
        if (r <= 0) return;
        ServerLevel level = (ServerLevel) p.level();
        int budget = (int) Sk.p(h, "max_blocks_per_tick");
        long ticks = (long) (Sk.p(h, "melt_seconds") * 20);
        BlockPos feet = p.blockPosition().below();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r && budget > 0; dx++) for (int dz = -r; dz <= r && budget > 0; dz++) {
            if (dx * dx + dz * dz > r * r + 1) continue;
            m.set(feet.getX() + dx, feet.getY(), feet.getZ() + dz);
            BlockState s = level.getBlockState(m);
            if (!s.is(Blocks.LAVA) || !s.getFluidState().isSourceOfType(Fluids.LAVA) || !level.getBlockState(m.above()).isAir()) continue;
            if (TempBlocks.place(level, m.immutable(), Blocks.BASALT.defaultBlockState(), ticks)) budget--;
        }
    }

    static void fireTrail(Player p) {
        HabitsApi.PerkHit h = Sk.FLAME_DANCER.hit(p);
        if (h == null || Sk.p(h, "trail") <= 0 || !p.onGround()) return;
        ServerLevel level = (ServerLevel) p.level();
        Vec3 back = p.position().subtract(p.getLookAngle().multiply(1.5, 0, 1.5));
        BlockPos at = BlockPos.containing(back.x, p.getY(), back.z);
        BlockPos below = at.below();
        if (!level.getBlockState(at).isAir() || !level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) return;
        for (Direction dir : Direction.values()) {
            BlockPos n = at.relative(dir);
            if (level.getBlockState(n).isFlammable(level, n, dir.getOpposite())) return; // never next to anything that burns
        }
        TempBlocks.place(level, at, BaseFireBlock.getState(level, at), (long) (Sk.p(h, "trail_seconds") * 20));
    }

    // ------------------------------------------------------------------ once a second

    /** A respawn, End exit or login sets health before max-health bonuses (ours and other mods') are back; fill the gap
     * if still unhurt: to max after a death, back to the saved health ({@code respawnTarget}) after an End exit or login. */
    static void respawnTopUp(Player p, net.minecraft.nbt.CompoundTag d, long now) {
        if (!d.contains("respawnUntil")) return;
        if (now <= d.getLong("respawnUntil") && p.getHealth() >= d.getFloat("respawnHp")) {
            p.setHealth(Math.max(p.getHealth(), d.contains("respawnTarget") ? d.getFloat("respawnTarget") : p.getMaxHealth()));
            d.putFloat("respawnHp", p.getHealth());
            return;
        }
        d.remove("respawnUntil");
        d.remove("respawnHp");
        d.remove("respawnTarget");
    }

    static void everySecond(Player p, boolean owner) {
        var d = Sk.data(p);
        long now = p.level().getGameTime();
        // safe ground for Void Bounce and the void card's drop spot
        if (p.onGround() && !p.isInLiquid() && p.getY() > p.level().getMinBuildHeight()) {
            d.putDouble("safeX", p.getX());
            d.putDouble("safeY", p.getY());
            d.putDouble("safeZ", p.getZ());
            String dim = p.level().dimension().location().toString();
            if (!dim.equals(d.getString("safeDim"))) d.putString("safeDim", dim);
            d.putBoolean("voidFired", false);
        }
        int food = p.getFoodData().getFoodLevel();
        if (d.contains("lastFood")) Fx.fireUnits(p, "hunger_point_lost", d.getInt("lastFood") - food);
        d.putInt("lastFood", food);
        if (p.getHealth() <= 4) {
            long since = d.getLong("lowSince");
            if (since == 0) d.putLong("lowSince", now);
            else if (since > 0 && now - since >= 200) { Fx.fire(p, "low_hp_survived"); d.putLong("lowSince", -1); }
        } else d.putLong("lowSince", 0);
        if (d.contains("clutchSince") || p.getHealth() <= 1) clutch(p, d, now, p.getHealth() <= 1 && deepDanger(p));
        armorClock(p, d, "leatherSec", fullLeather(p), 300, "full_leather_5min");
        armorClock(p, d, "chainSec", anyMaterial(p, ArmorMaterials.CHAIN), 300, "chainmail_5min");
        armorClock(p, d, "goldSec", anyMaterial(p, ArmorMaterials.GOLD), 300, "gold_armor_5min");
        ItemStack head = p.getItemBySlot(EquipmentSlot.HEAD);
        armorClock(p, d, "turtleSec", head.is(Items.TURTLE_HELMET) && p.isEyeInFluid(FluidTags.WATER), 60, "turtle_underwater_minute");
        armorClock(p, d, "pumpkinSec", head.is(Items.CARVED_PUMPKIN)
                && !p.level().getEntitiesOfClass(EnderMan.class, p.getBoundingBox().inflate(16)).isEmpty(), 60, "pumpkin_enderman_minute");

        // stat-like perks live as attribute modifiers; set every second so toggling a perk off removes them
        Fx.attr(p, Attributes.MAX_HEALTH, Sk.THICK_BLOOD.id, Sk.THICK_BLOOD.v(p) * 2, AttributeModifier.Operation.ADD_VALUE);
        Fx.attr(p, Attributes.KNOCKBACK_RESISTANCE, Sk.DIAMOND_SPINE.id, Sk.DIAMOND_SPINE.v(p) / 100, AttributeModifier.Operation.ADD_VALUE);
        Fx.attr(p, Attributes.ARMOR, Sk.LEATHER_LOVER.id, fullLeather(p) ? Sk.LEATHER_LOVER.v(p) : 0, AttributeModifier.Operation.ADD_VALUE);
        Fx.attr(p, Attributes.SUBMERGED_MINING_SPEED, Sk.DEEP_DIVER.id,
                p.hasEffect(MobEffects.WATER_BREATHING) ? 0.8 * Sk.DEEP_DIVER.v(p) / 100 : 0, AttributeModifier.Operation.ADD_VALUE);
        respawnTopUp(p, d, now);
        if (!owner) return;
        ownerSecond(p, d, now);
        HabitsApi.PerkHit lich = Sk.PHYLACTERY.hit(p);
        if (lich != null) phylactery(d, now, lich);
    }

    /** Phylactery: one shield grows back every {@code regen} seconds, up to the perk value. */
    static void phylactery(net.minecraft.nbt.CompoundTag d, long now, HabitsApi.PerkHit h) {
        int max = (int) h.value(), n = Math.min(d.getInt("phyShields"), max);
        if (n < max && now >= d.getLong("phyNext")) {
            n++;
            d.putLong("phyNext", now + (long) (Sk.p(h, "regen") * 20));
        }
        d.putInt("phyShields", n);
    }

    /** The shields circle you like the Lich's: one particle per shield every 4 ticks, seen by everyone nearby. */
    static void lichOrbit(Player p, int n) {
        if (!(p.level() instanceof ServerLevel l)) return;
        for (int i = 0; i < n; i++) {
            double a = p.tickCount * 0.2 + i * Math.PI * 2 / n;
            l.sendParticles(ParticleTypes.WITCH, p.getX() + Math.cos(a) * 1.1, p.getY() + 1.1, p.getZ() + Math.sin(a) * 1.1, 1, 0, 0, 0, 0);
        }
    }

    /** Ninth Life's card event: at half a heart or less in deep danger, then still alive 30 seconds later. */
    static void clutch(Player p, net.minecraft.nbt.CompoundTag d, long now, boolean start) {
        if (!d.contains("clutchSince")) {
            if (start) d.putLong("clutchSince", now);
        } else if (now - d.getLong("clutchSince") >= 600) {
            Fx.fire(p, "clutch_frontier");
            d.remove("clutchSince");
        }
    }

    /** Frontier III or past it, or the Nether or the End. */
    static boolean deepDanger(Player p) {
        var dim = p.level().dimension();
        return dim == net.minecraft.world.level.Level.NETHER || dim == net.minecraft.world.level.Level.END
                || p.level() instanceof ServerLevel l && com.habits.danger.Zones.at(l, p.getX(), p.getZ()).rank() >= 3;
    }

    static void armorClock(Player p, net.minecraft.nbt.CompoundTag d, String key, boolean wearing, int need, String event) {
        if (!wearing) { if (d.contains(key)) d.remove(key); return; }
        int s = d.getInt(key) + 1;
        if (s >= need) { Fx.fire(p, event); s = 0; }
        d.putInt(key, s);
    }

    static boolean fullLeather(Player p) {
        for (ItemStack s : p.getArmorSlots()) if (!(s.getItem() instanceof ArmorItem a) || !a.getMaterial().is(ArmorMaterials.LEATHER)) return false;
        return true;
    }

    static boolean anyMaterial(Player p, net.minecraft.core.Holder<net.minecraft.world.item.ArmorMaterial> mat) {
        for (ItemStack s : p.getArmorSlots()) if (s.getItem() instanceof ArmorItem a && a.getMaterial().is(mat)) return true;
        return false;
    }

    static void ownerSecond(Player p, net.minecraft.nbt.CompoundTag d, long now) {
        ServerLevel level = (ServerLevel) p.level();
        HabitsApi.PerkHit h;
        int sec = (int) (now / 20);
        // Big Eater reserve drains before the bar
        int reserve = d.getInt("reserve");
        if (reserve > 0 && p.getFoodData().getFoodLevel() < 20) {
            p.getFoodData().setFoodLevel(p.getFoodData().getFoodLevel() + 1);
            d.putInt("reserve", reserve - 1);
        }
        if (p.hasEffect(MobEffects.GLOWING) && (h = Sk.SPOTLIGHT.hit(p)) != null) glowEnemies(p, h.value(), 30);
        if ((h = Sk.SPIDER_SENSE.hit(p)) != null && d.getLong("senseUntil") > now) glowEnemies(p, h.value(), 30);
        if ((p.hasEffect(MobEffects.BLINDNESS) || p.hasEffect(MobEffects.DARKNESS)) && (h = Sk.BAT_EARS.hit(p)) != null) {
            int n = 0;
            for (LivingEntity m : level.getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(h.value()), m -> m != p))
                if (n++ < 16) particles(p, ParticleTypes.SCULK_CHARGE_POP, m.getEyePosition(), 4);
        }
        if ((h = Sk.HEX_WARD.hit(p)) != null)
            glow(p, level.getEntitiesOfClass(EvokerFangs.class, p.getBoundingBox().inflate(Sk.p(h, "glow_range"))), 30);
        if ((h = Sk.DRIP_DODGER.hit(p)) != null) dripWarn(p, (int) Sk.p(h, "warn"));
        BlockPos feet = p.blockPosition();
        BlockState on = level.getBlockState(feet), under = level.getBlockState(feet.below());
        if ((h = Sk.CAMP_COOK.hit(p)) != null && (lit(on) || lit(under))) {
            if (sec % Math.max(1, (int) Sk.p(h, "heal_seconds")) == 0) p.heal((float) Sk.p(h, "heal"));
            if (sec % Math.max(1, (int) Sk.p(h, "cook_interval")) == 0) cookHeld(p, level);
        }
        if ((h = Sk.MAGMA_SOLES.hit(p)) != null && (under.is(Blocks.MAGMA_BLOCK) || lit(under) || lit(on))) {
            Fx.effect(p, MobEffects.MOVEMENT_SPEED, 1.5, Sk.p(h, "speed"));
            Fx.effect(p, MobEffects.REGENERATION, 3, Sk.p(h, "regen"));
        }
        if ((h = Sk.BRAMBLE_WALKER.hit(p)) != null && on.is(Blocks.SWEET_BERRY_BUSH) && on.getValue(SweetBerryBushBlock.AGE) > 1
                && sec % Math.max(1, (int) Sk.p(h, "pick_interval")) == 0)
            on.useWithoutItem(level, p, new BlockHitResult(Vec3.atCenterOf(feet), Direction.UP, feet, false));
        if ((h = Sk.BREATH_BOTTLER.hit(p)) != null && sec % Math.max(1, (int) Sk.p(h, "bottle_interval")) == 0) bottleBreath(p, level);
        if ((h = Sk.ELBOW_ROOM.hit(p)) != null && sec % Math.max(1, (int) Sk.p(h, "seconds")) == 0) elbow(p, h);
        if ((h = Sk.TURTLE_SHELL.hit(p)) != null && Sk.p(h, "crouch_resist") > 0 && p.isShiftKeyDown() && !p.isInWater()
                && p.getItemBySlot(EquipmentSlot.HEAD).is(Items.TURTLE_HELMET))
            Fx.effect(p, MobEffects.DAMAGE_RESISTANCE, 1.5, 0);
        if ((h = Sk.EDGE_WALKER.hit(p)) != null && Sk.p(h, "home") > 0 && !level.getWorldBorder().isWithinBounds(p.getBoundingBox())) homeArrow(p, level);
        if (Sk.OMEN_READER.hit(p) != null) {
            Raid raid = level.getRaidAt(feet);
            if (raid != null && raid.isActive())
                p.displayClientMessage(Component.translatable(raid.getTotalRaidersAlive() == 1 ? "habits.survival.raid.one" : "habits.survival.raid",
                        raid.getGroupsSpawned(), raid.getNumGroups(level.getDifficulty()), raid.getTotalRaidersAlive()), true);
        }
    }

    static boolean lit(BlockState s) {
        return s.getBlock() instanceof CampfireBlock && s.getValue(CampfireBlock.LIT);
    }

    /** Outlines hostile mobs for this player only (not the Glowing effect, which everyone would see). */
    static void glowEnemies(Player p, double r, int ticks) {
        glow(p, p.level().getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(r), m -> m instanceof Enemy), ticks);
    }

    static void glow(Player p, java.util.List<? extends net.minecraft.world.entity.Entity> list, int ticks) {
        java.util.List<Integer> ids = new java.util.ArrayList<>(list.size());
        for (var m : list) ids.add(m.getId());
        SurvivalNet.glow(p, ids, ticks);
    }

    /** Server particles to this player only, capped. */
    static void particles(Player p, SimpleParticleType type, Vec3 at, int count) {
        if (p instanceof ServerPlayer sp) sp.serverLevel().sendParticles(sp, type, false, at.x, at.y, at.z, Math.min(count, 8), 0.2, 0.2, 0.2, 0);
    }

    static void dripWarn(Player p, int warn) {
        BlockPos base = p.blockPosition().above(2);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int shown = 0;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int dy = 0; dy < warn && shown < 4; dy++) {
            m.set(base.getX() + dx, base.getY() + dy, base.getZ() + dz);
            BlockState s = p.level().getBlockState(m);
            if (!s.isAir()) {
                if (s.is(Blocks.POINTED_DRIPSTONE) && s.getValue(PointedDripstoneBlock.TIP_DIRECTION) == Direction.DOWN) {
                    particles(p, ParticleTypes.ELECTRIC_SPARK, Vec3.atCenterOf(m), 3);
                    shown++;
                }
                break; // only the first block above you in this column
            }
        }
    }

    static void cookHeld(Player p, ServerLevel level) {
        ItemStack held = p.getMainHandItem();
        if (held.isEmpty()) return;
        var recipe = level.getRecipeManager().getRecipeFor(RecipeType.CAMPFIRE_COOKING, new SingleRecipeInput(held), level);
        if (recipe.isEmpty()) return;
        ItemStack out = recipe.get().value().getResultItem(level.registryAccess()).copy();
        held.shrink(1);
        Fx.give(p, out);
    }

    static void bottleBreath(Player p, ServerLevel level) {
        for (AreaEffectCloud c : level.getEntitiesOfClass(AreaEffectCloud.class, p.getBoundingBox().inflate(0.5))) {
            if (c.getParticle() != ParticleTypes.DRAGON_BREATH || c.getRadius() < 0.5f) continue;
            int slot = p.getInventory().findSlotMatchingItem(new ItemStack(Items.GLASS_BOTTLE));
            if (slot < 0) return;
            p.getInventory().getItem(slot).shrink(1);
            Fx.give(p, new ItemStack(Items.DRAGON_BREATH));
            c.setRadius(c.getRadius() - 0.5f); // same price as vanilla scooping
            return;
        }
    }

    static void elbow(Player p, HabitsApi.PerkHit h) {
        double range = Sk.p(h, "range");
        var crowd = p.level().getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(range), m -> m != p && m.isPushable());
        if (crowd.size() < Sk.p(h, "crowd")) return;
        double push = Sk.p(h, "push");
        for (LivingEntity m : crowd) Fx.knockAway(p, m, push);
    }

    static void homeArrow(Player p, ServerLevel level) {
        BlockPos spawn = level.getSharedSpawnPos();
        double dx = spawn.getX() - p.getX(), dz = spawn.getZ() - p.getZ();
        double yaw = Math.toDegrees(Math.atan2(-dx, dz)); // MC yaw of the direction home
        int dir = (int) Math.floorMod(Math.round((yaw - p.getYRot()) / 45), 8);
        int dist = (int) Math.sqrt(dx * dx + dz * dz);
        p.displayClientMessage(Component.translatable(dist == 1 ? "habits.survival.home.one" : "habits.survival.home", dist,
                Component.translatable("habits.survival.dir." + dir)), true);
    }
}
