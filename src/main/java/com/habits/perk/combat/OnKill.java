package com.habits.perk.combat;

import com.habits.core.HabitsApi;
import com.habits.core.TriggerType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** Player melee kills: triggers, on-kill perks, drops and XP. */
final class OnKill {
    private OnKill() {}

    static final String MELEE_KILL = "habits_mk", HOE = "habits_hoe", CLEAN = "habits_clean";
    static final Set<EntityType<?>> FARM = Set.of(EntityType.COW, EntityType.PIG, EntityType.SHEEP, EntityType.CHICKEN,
            EntityType.RABBIT, EntityType.MOOSHROOM, EntityType.GOAT);

    static void kill(ServerPlayer p, LivingEntity v) {
        PS s = PS.of(p);
        long now = Hooks.now(p);
        ItemStack hand = p.getMainHandItem();
        boolean main = s.attackTick == now && v.getId() == s.mainTarget;
        boolean crit = main && s.critTick == now && s.critTarget == v.getId();
        boolean boss = M.boss(v);
        v.addTag(MELEE_KILL);

        // ---- triggers
        M.fire(p, "melee_kill", 1, hand, v);
        if (hand.isEnchanted()) for (var en : hand.getEnchantments().entrySet())
            en.getKey().unwrapKey().ifPresent(k -> M.fire(p, "enchanted_kill/" + k.location().getPath(), 1, hand, v));
        if (hand.is(Items.MACE)) M.fire(p, "smash_kill", p.fallDistance, hand, v);
        if (main && s.strength >= 0.95f) M.fire(p, "full_charge_kill", 1, hand, v);
        if (p.getHealth() < p.getMaxHealth() * 0.3f) M.fire(p, "low_hp_kill", 1, hand, v);
        s.killTimes[s.killIdx++ % 5] = now;
        boolean streak = false;
        if (s.killIdx >= 5 && now - min(s.killTimes) <= 200) {
            streak = true;
            M.fire(p, "kill_streak", 5, hand, v);
            s.killIdx = 0;
            java.util.Arrays.fill(s.killTimes, 0);
        }
        if (p.isShiftKeyDown() && !(v instanceof Mob m && m.getTarget() == p)) M.fire(p, "sneak_kill", 1, hand, v);
        if (!v.getMainHandItem().isEmpty()) M.fire(p, "disarm", 1, hand, v);
        if (v.getType() == EntityType.STRAY || v.getTicksFrozen() > 0) M.fire(p, "frost_kill", 1, hand, v);
        if (p.level().isThundering()) M.fire(p, "storm_kill", 1, hand, v);
        if (++s.flawless >= 10) {
            s.flawless = 0;
            M.fire(p, "flawless_streak", 10, hand, v);
        }
        int near16 = 0, near8 = 0;
        for (LivingEntity e : p.level().getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(16), e -> e instanceof Enemy && e != v && e.isAlive())) {
            near16++;
            if (e.distanceToSqr(p) <= 64) near8++;
        }
        if (near16 == 0) M.fire(p, "duel_kill", 1, hand, v);
        if (near8 >= 5) M.fire(p, "crowd_kill", near8, hand, v);
        if (main && s.fullHpHit && s.fullHpTarget == v.getId()) {
            M.fire(p, "one_shot", 1, hand, v);
            v.addTag(CLEAN);
        }
        if (hand.is(Items.TRIDENT) && (v.isInWater() || v.getType().is(EntityTypeTags.AQUATIC))) M.fire(p, "wet_kill", 1, hand, v);
        if (!hand.isEmpty() && !Damage.weapon(hand)) M.fire(p, "improvised_kill", 1, hand, v);
        if (Hooks.isDragon(v)) M.fire(p, TriggerType.CUSTOM_UNITS, "dragonslayer", 200, hand, v);

        // ---- perks
        HabitsApi.PerkHit h;
        if (hand.isDamageableItem() && hand.getDamageValue() > 0 && (h = M.hit(p, Mech.TEMPERED_EDGE)) != null)
            hand.setDamageValue(Math.max(0, hand.getDamageValue() - (int) h.value()));
        if (hand.is(ItemTags.HOES) && (h = M.hit(p, Mech.GRIM_REAPER)) != null) {
            v.addTag(HOE);
            p.heal((float) h.value() * 2);
            M.line((ServerLevel) p.level(), ParticleTypes.SOUL, v.position().add(0, 1, 0), p.position().add(0, 1, 0));
        }
        if ((h = M.hit(p, Mech.BLOODLUST)) != null) {
            s.bloodStacks = now < s.bloodUntil ? Math.min((int) M.p(h, "stacks"), s.bloodStacks + 1) : 1;
            s.bloodUntil = now + M.ticks(M.p(h, "seconds"));
        }
        if (main && (h = M.hit(p, Mech.MOMENTUM)) != null) momentum(p, s, h, now);
        if ((h = M.hit(p, Mech.RAMPAGE)) != null) {
            s.rampStacks = now - s.rampLast <= 100 ? Math.min((int) M.p(h, "stacks"), s.rampStacks + 1) : 1;
            s.rampLast = now;
            Ticks.refresh(p, s, now);
        }
        if (M.hit(p, Mech.FLOW) != null) s.flowStacks++;
        if (p.isShiftKeyDown() && (h = M.hit(p, Mech.ASSASSIN)) != null) {
            p.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, M.ticks(h.value()), 0, false, false, true));
            for (Mob m : p.level().getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(M.p(h, "radius")), m -> m.getTarget() == p))
                OnHit.lose(m, 40);
        }
        if (v instanceof Enemy && !boss && !Allies.isAlly(v) && (h = M.hit(p, Mech.GRAVE_CALLER)) != null && M.roll(p, h.value())) {
            HabitsApi.PerkHit gh = h;
            Tracked.later(() -> Allies.raise(p, v, gh));
        }
        if ((h = M.hit(p, Mech.BONEBREAKER)) != null) burst(p, v, M.p(h, "radius"), (float) h.value(), false);
        if (v.getType().is(EntityTypeTags.ARTHROPOD) && (h = M.hit(p, Mech.EXTERMINATOR)) != null)
            burst(p, v, M.p(h, "radius"), (float) M.p(h, "damage"), true);
        if (s.overflowTarget == v.getId() && s.overflow > 0 && (h = M.hit(p, Mech.OVERKILL)) != null) overkill(p, v, h, s.overflow);
        if (v.getType().is(EntityTypeTags.RAIDERS) && (h = M.hit(p, Mech.ROUT)) != null && M.roll(p, h.value())) rout(p, v, h);
        if ((v.getType() == EntityType.ZOMBIFIED_PIGLIN || v instanceof AbstractPiglin) && (h = M.hit(p, Mech.HUSH)) != null
                && M.roll(p, h.value())) hush(p, v, M.p(h, "brutes") >= 1);
        if (v.getType() == EntityType.BLAZE && (h = M.hit(p, Mech.EMBER_HEART)) != null) {
            p.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, M.ticks(h.value())));
            s.emberHits = (int) M.p(h, "hits");
        }
        if ((h = M.hit(p, Mech.HOLY_STRIKE)) != null && v.getType().is(EntityTypeTags.UNDEAD)) p.heal((float) M.p(h, "heal") * 2);
        if ((h = M.hit(p, Mech.SOUL_HARVEST)) != null) soul(p, s, v, h, now);
        if (crit && (h = M.hit(p, Mech.VOLATILE)) != null && M.roll(p, h.value())) pop(p, v, (float) M.p(h, "damage"), M.p(h, "radius"));
        Warrior.kill(p, s, v, main, boss, streak, now);
    }

    static long min(long[] a) {
        long m = Long.MAX_VALUE;
        for (long x : a) m = Math.min(m, x);
        return m;
    }

    static void momentum(ServerPlayer p, PS s, HabitsApi.PerkHit h, long now) {
        double r = Math.min(0.95, h.value() / 100);
        Ticks.mod(p, Attributes.ATTACK_SPEED, Ticks.MOMENTUM, 1 / (1 - r) - 1);
        s.momentumUntil = now + 60;
        s.momentumSet = now;
        double secs = M.p(h, "speed_seconds");
        if (secs > 0) p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, M.ticks(secs), (int) M.p(h, "speed_level")));
    }

    /** Shrapnel / bug splat: damage foes around the dead mob (arthropods only for the splat). */
    static void burst(ServerPlayer p, LivingEntity v, double r, float dmg, boolean bugsOnly) {
        Vec3 c = v.position();
        Tracked.later(() -> {
            ServerLevel sl = (ServerLevel) p.level();
            M.fx(sl, bugsOnly ? ParticleTypes.ITEM_SLIME : new ItemParticleOption(ParticleTypes.ITEM, new ItemStack(Items.BONE)), c.add(0, 0.6, 0), 16, 0.4);
            for (LivingEntity f : M.foesNear(p, c, r, v))
                if (!bugsOnly || f.getType().is(EntityTypeTags.ARTHROPOD)) M.hurt(f, M.src(p, DamageTypes.GENERIC), dmg, false);
        });
    }

    static void overkill(ServerPlayer p, LivingEntity v, HabitsApi.PerkHit h, float overflow) {
        float carry = (float) (overflow * h.value() / 100);
        int chains = (int) M.p(h, "chains");
        double r = M.p(h, "radius");
        Vec3 c0 = v.position();
        Tracked.later(() -> {
            Vec3 c = c0;
            float left = carry;
            LivingEntity from = v;
            for (int i = 0; i < chains && left > 0.5f; i++) {
                LivingEntity best = null;
                double bd = Double.MAX_VALUE;
                for (LivingEntity f : M.foesNear(p, c, r, from)) {
                    double d = f.distanceToSqr(c);
                    if (d < bd) {
                        bd = d;
                        best = f;
                    }
                }
                if (best == null) return;
                float hp = best.getHealth();
                M.line((ServerLevel) p.level(), ParticleTypes.CRIT, c.add(0, 1, 0), best.position().add(0, 1, 0));
                M.hurt(best, p.damageSources().playerAttack(p), left, true);
                if (best.isAlive()) return;
                left -= hp;
                c = best.position();
                from = best;
            }
        });
    }

    static void rout(ServerPlayer p, LivingEntity v, HabitsApi.PerkHit h) {
        int t = M.ticks(M.p(h, "seconds"));
        for (Mob m : p.level().getEntitiesOfClass(Mob.class, v.getBoundingBox().inflate(M.p(h, "radius")),
                m -> m != v && m.getType().is(EntityTypeTags.RAIDERS) && m.isAlive())) {
            OnHit.lose(m, t);
            Tracked.put(m, "flee", new Tracked.Timed(t) {
                @Override
                void run(LivingEntity e) {
                    if (ticks % 10 != 0 || !(e instanceof Mob mob)) return;
                    Vec3 away = e.position().subtract(p.position());
                    Vec3 to = e.position().add(away.normalize().scale(8));
                    mob.getNavigation().moveTo(to.x, to.y, to.z, 1.4);
                }
            });
        }
    }

    static void hush(ServerPlayer p, LivingEntity v, boolean brutes) {
        Tracked.later(() -> {
            for (Mob m : p.level().getEntitiesOfClass(Mob.class, v.getBoundingBox().inflate(16), m -> m != v && m.isAlive()
                    && (m.getType() == EntityType.ZOMBIFIED_PIGLIN || m.getType() == EntityType.PIGLIN || (brutes && m.getType() == EntityType.PIGLIN_BRUTE)))) {
                if (m instanceof NeutralMob n) n.stopBeingAngry();
                if (m instanceof AbstractPiglin) {
                    m.getBrain().eraseMemory(MemoryModuleType.ANGRY_AT);
                    m.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
                }
                OnHit.lose(m, 60);
            }
        });
    }

    static final net.minecraft.resources.ResourceLocation SOUL = com.habits.Habits.id("melee/soul_harvest");

    static void soul(ServerPlayer p, PS s, LivingEntity v, HabitsApi.PerkHit h, long now) {
        M.line((ServerLevel) p.level(), ParticleTypes.SOUL, v.position().add(0, 1, 0), p.position().add(0, 1, 0));
        p.heal((float) h.value() * 2);
        float abs = (float) M.p(h, "absorption") * 2;
        if (abs > 0) Warrior.roomFor(p, SOUL, abs);
        if (abs > 0 && p.getAbsorptionAmount() < abs) p.setAbsorptionAmount(Math.min(abs, p.getAbsorptionAmount() + 2));
        s.soulOrbs = now - s.soulTime <= 200 ? s.soulOrbs + 1 : 1;
        s.soulTime = now;
        int maxAmp = (int) M.p(h, "strength");
        if (maxAmp >= 0 && s.soulOrbs >= 5)
            p.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, 200, Math.min(maxAmp, s.soulOrbs / 5 - 1)));
    }

    static void pop(ServerPlayer p, LivingEntity v, float dmg, double r) {
        Vec3 c = v.position();
        Tracked.later(() -> {
            ServerLevel sl = (ServerLevel) p.level();
            sl.sendParticles(ParticleTypes.EXPLOSION, c.x, c.y + 0.5, c.z, 1, 0, 0, 0, 0);
            sl.playSound(null, c.x, c.y, c.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 0.8f, 1.2f);
            for (LivingEntity f : M.foesNear(p, c, r, v)) M.hurt(f, p.damageSources().explosion(p, p), dmg, false);
        });
    }

    // ---------------------------------------------------------------- drops and XP

    static void drops(ServerPlayer p, LivingEntity v, LivingDropsEvent e) {
        HabitsApi.PerkHit h;
        long now = Hooks.now(p);
        if ((h = M.hit(p, Mech.HEADHUNTER)) != null) {
            ItemStack head = head(v);
            double chance = v.getType() == EntityType.WITHER_SKELETON ? M.p(h, "wither") : h.value();
            if (!head.isEmpty() && M.roll(p, chance)) add(e, v, head);
        }
        if ((h = M.hit(p, Mech.MIDAS_TOUCH)) != null && M.roll(p, h.value())) {
            int n = 1 + p.getRandom().nextInt(Math.max(1, (int) Math.round(M.p(h, "nuggets"))));
            add(e, v, new ItemStack(Items.GOLD_NUGGET, n));
            if (M.roll(p, M.p(h, "ingot"))) add(e, v, new ItemStack(Items.GOLD_INGOT));
            M.fx(v, ParticleTypes.WAX_ON, 8, 0.4);
        }
        if (FARM.contains(v.getType()) && (h = M.hit(p, Mech.BUTCHER)) != null) butcher(p, v, e, h);
        if (v.getTags().contains(CLEAN) && (h = M.hit(p, Mech.CLEAN_KILL)) != null && M.roll(p, M.p(h, "loot")))
            rollLoot(p, v, 8, st -> add(e, v, st));
        if (v.getType() == EntityType.ENDERMAN && (h = M.hit(p, Mech.PINNED)) != null && M.roll(p, M.p(h, "pearl")))
            add(e, v, new ItemStack(Items.ENDER_PEARL));
        Warrior.drops(p, v, e);
    }

    static void add(LivingDropsEvent e, LivingEntity v, ItemStack st) {
        ItemEntity it = new ItemEntity(v.level(), v.getX(), v.getY() + 0.5, v.getZ(), st);
        it.setDefaultPickUpDelay();
        e.getDrops().add(it);
    }

    static ItemStack head(LivingEntity v) {
        EntityType<?> t = v.getType();
        if (t == EntityType.ZOMBIE) return new ItemStack(Items.ZOMBIE_HEAD);
        if (t == EntityType.SKELETON) return new ItemStack(Items.SKELETON_SKULL);
        if (t == EntityType.CREEPER) return new ItemStack(Items.CREEPER_HEAD);
        if (t == EntityType.PIGLIN) return new ItemStack(Items.PIGLIN_HEAD);
        if (t == EntityType.WITHER_SKELETON) return new ItemStack(Items.WITHER_SKELETON_SKULL);
        if (v instanceof Player pl) {
            ItemStack s = new ItemStack(Items.PLAYER_HEAD);
            s.set(DataComponents.PROFILE, new ResolvableProfile(pl.getGameProfile()));
            return s;
        }
        return ItemStack.EMPTY;
    }

    static void butcher(ServerPlayer p, LivingEntity v, LivingDropsEvent e, HabitsApi.PerkHit h) {
        ServerLevel sl = (ServerLevel) v.level();
        boolean added = false;
        boolean hide = M.p(h, "hide") >= 1;
        for (ItemEntity ie : List.copyOf(e.getDrops())) {
            ItemStack st = ie.getItem();
            if (st.has(DataComponents.FOOD)) {
                if (!added) {
                    st.grow((int) h.value());
                    added = true;
                }
                if (M.roll(p, M.p(h, "cooked"))) {
                    var cooked = sl.getRecipeManager().getRecipeFor(RecipeType.SMELTING, new SingleRecipeInput(st), sl);
                    if (cooked.isPresent()) {
                        ItemStack out = cooked.get().value().getResultItem(sl.registryAccess()).copyWithCount(st.getCount());
                        ie.setItem(out);
                    }
                }
            } else if (hide && (st.is(Items.LEATHER) || st.is(Items.FEATHER) || st.is(Items.RABBIT_HIDE) || st.is(ItemTags.WOOL))) {
                hide = false;
                st.grow(1);
            }
        }
    }

    /** Roll the mob's loot table once more and hand out up to {@code max} stacks. */
    static void rollLoot(ServerPlayer p, LivingEntity v, int max, Consumer<ItemStack> out) {
        if (!(v.level() instanceof ServerLevel sl)) return;
        LootTable t = sl.getServer().reloadableRegistries().getLootTable(v.getLootTable());
        LootParams lp = new LootParams.Builder(sl)
                .withParameter(LootContextParams.THIS_ENTITY, v)
                .withParameter(LootContextParams.ORIGIN, v.position())
                .withParameter(LootContextParams.DAMAGE_SOURCE, p.damageSources().playerAttack(p))
                .withOptionalParameter(LootContextParams.ATTACKING_ENTITY, p)
                .withOptionalParameter(LootContextParams.DIRECT_ATTACKING_ENTITY, p)
                .withParameter(LootContextParams.LAST_DAMAGE_PLAYER, p)
                .withLuck(p.getLuck())
                .create(LootContextParamSets.ENTITY);
        int n = 0;
        for (ItemStack st : t.getRandomItems(lp)) {
            if (st.isEmpty()) continue;
            out.accept(st);
            if (++n >= max) break;
        }
    }

    static int xp(ServerPlayer p, LivingEntity v, int xp) {
        double mult = 1;
        HabitsApi.PerkHit h;
        if ((h = M.hit(p, Mech.MIDAS_TOUCH)) != null) mult += M.p(h, "xp") / 100;
        if (v.getTags().contains(HOE) && (h = M.hit(p, Mech.GRIM_REAPER)) != null) mult += M.p(h, "xp") / 100;
        if (v.getTags().contains(CLEAN) && (h = M.hit(p, Mech.CLEAN_KILL)) != null) mult += h.value() / 100;
        return (int) Math.round(xp * mult);
    }
}
