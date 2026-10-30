package com.habits.perk.combat;

import com.habits.core.HabitsApi;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

import java.util.List;

/** Grave Caller zombies: their target goals are swapped for one that hunts hostiles; they fight for a while, then crumble.
 * LivingChangeTargetEvent still refuses a non-hostile target as a backstop. Never drop loot or XP, discarded if a chunk
 * reloads them after a restart. */
final class Allies {
    private Allies() {}

    static final String TAG = "habits_ally";

    static boolean isAlly(Entity e) {
        return e instanceof Zombie && e.getTags().contains(TAG);
    }

    static void raise(ServerPlayer p, LivingEntity dead, HabitsApi.PerkHit h) {
        ServerLevel sl = (ServerLevel) p.level();
        int max = (int) M.p(h, "max");
        AABB box = p.getBoundingBox().inflate(48);
        List<Zombie> mine = sl.getEntitiesOfClass(Zombie.class, box, z -> z.getTags().contains(TAG) && z.getTags().contains(ownerTag(p)));
        if (mine.size() >= max) return;
        Zombie z = EntityType.ZOMBIE.create(sl);
        if (z == null) return;
        z.moveTo(dead.getX(), dead.getY(), dead.getZ(), dead.getYRot(), 0);
        z.addTag(TAG);
        z.addTag(ownerTag(p));
        z.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET)); // no sun burn
        for (EquipmentSlot s : EquipmentSlot.values()) z.setDropChance(s, 0);
        z.setCanPickUpLoot(false);
        // vanilla's player / villager / golem / turtle target goals out, one goal for hostiles in
        z.targetSelector.removeAllGoals(g -> true);
        z.targetSelector.addGoal(1, new NearestAttackableTargetGoal<>(z, Mob.class, 10, true, false, e -> e instanceof Enemy && !isAlly(e)));
        sl.addFreshEntity(z);
        M.fx(z, ParticleTypes.SOUL, 12, 0.4);
        Tracked.put(z, "ally", new Tracked.Timed(M.ticks(M.p(h, "seconds"))) {
            @Override
            void end(LivingEntity e) {
                M.fx(e, ParticleTypes.ASH, 20, 0.4);
                e.discard();
            }
        });
    }

    static String ownerTag(ServerPlayer p) {
        return "habits_ally_" + p.getUUID();
    }
}
