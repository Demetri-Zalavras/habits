package com.habits.danger;

import com.habits.Habits;
import com.habits.core.Rarity;
import com.habits.progression.Drops;
import com.habits.progression.ProgressionApi;
import com.habits.progression.SigilItem;
import com.mojang.authlib.GameProfile;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/** Danger scaling and elite GameTests. Untagged mobs are never scaled on the test server, so each test opts in. */
@GameTestHolder(Habits.MODID)
@PrefixGameTestTemplate(false)
public class DangerTests {
    private DangerTests() {}

    /** A no-AI mob at a template spot; {@code dist} >= 0 tags it so joining scales it as if that far from spawn. */
    static <T extends Mob> T spawn(GameTestHelper h, EntityType<T> type, double x, double z, double dist) {
        T m = type.create(h.getLevel());
        Vec3 at = h.absoluteVec(new Vec3(x, 2, z));
        m.moveTo(at.x, at.y, at.z, 0, 0);
        m.setNoAi(true);
        if (dist >= 0) {
            m.addTag(Scaling.TEST_TAG);
            m.getPersistentData().putDouble(Scaling.TEST_DIST, dist);
        }
        h.getLevel().addFreshEntity(m);
        return m;
    }

    static void near(GameTestHelper h, double want, double got, String what) {
        h.assertTrue(Math.abs(want - got) < 1e-6, what + ": want " + want + ", got " + got);
    }

    @GameTest(template = "empty")
    public static void zonesAndScalingOnce(GameTestHelper h) {
        var ow = Level.OVERWORLD.location();
        Zones.Zone safe = Zones.of(ow, 400), mid = Zones.of(ow, 2750), far = Zones.of(ow, 9000);
        near(h, 1, safe.health(), "safe health");
        near(h, 2, mid.health(), "halfway health");
        near(h, 1.5, mid.damage(), "halfway damage");
        near(h, 3, far.health(), "far health");
        near(h, 2, far.damage(), "far damage");
        h.assertValueEqual(mid.rank(), 2, "halfway is Frontier II");
        Zones.Zone nether = Zones.of(Level.NETHER.location(), 0), end = Zones.of(Level.END.location(), 0);
        near(h, 5, nether.health(), "nether health");
        near(h, 3, nether.damage(), "nether damage");
        near(h, 8, end.health(), "end health");
        near(h, 4, end.damage(), "end damage");
        h.assertTrue(end.rank() > nether.rank() && nether.rank() > far.rank(), "zone ranks rise");

        Zombie z = spawn(h, EntityType.ZOMBIE, 1.5, 1.5, -1);
        double hp = z.getMaxHealth(), atk = z.getAttributeValue(Attributes.ATTACK_DAMAGE);
        h.assertTrue(Scaling.apply(z, far.health(), far.damage()), "first apply");
        h.assertFalse(Scaling.apply(z, far.health(), far.damage()), "second apply is a no-op");
        near(h, hp * 3, z.getMaxHealth(), "max health x3");
        near(h, hp * 3, z.getHealth(), "healed to the new max");
        near(h, atk * 2, z.getAttributeValue(Attributes.ATTACK_DAMAGE), "attack x2");

        Zombie big = spawn(h, EntityType.ZOMBIE, 2.5, 1.5, -1);
        h.assertTrue(Scaling.apply(big, 100, 1), "huge apply");
        near(h, 2000, big.getMaxHealth(), "max health past vanilla's 1024 cap");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void joinScalesMobsAndBosses(GameTestHelper h) {
        double zombieHp = EntityType.ZOMBIE.create(h.getLevel()).getMaxHealth();
        Zombie z = spawn(h, EntityType.ZOMBIE, 1.5, 1.5, 5000);
        h.assertTrue(z.getPersistentData().contains(Scaling.MARK), "zombie marked");
        double elite = Elites.mask(z) != 0 ? DangerInit.ELITE_HEALTH.get() : 1;
        near(h, zombieHp * 3 * elite, z.getMaxHealth(), "zombie at 5000 blocks");
        var wither = spawn(h, EntityType.WITHER, 2.5, 2.5, 5000);
        double base = wither.getAttributeBaseValue(Attributes.MAX_HEALTH);
        h.assertTrue(Scaling.isBoss(wither), "wither is a boss");
        h.assertValueEqual(Elites.mask(wither), 0, "bosses are never elite");
        near(h, base * 3, wither.getMaxHealth(), "wither at 5000 blocks");
        wither.discard();
        z.discard();
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void eliteAffixesHit(GameTestHelper h) {
        Zombie elite = spawn(h, EntityType.ZOMBIE, 1.5, 1.5, -1);
        Elites.make(elite, Elites.Affix.FIERY.bit() | Elites.Affix.FROZEN.bit() | Elites.Affix.SHOCKING.bit(), 0);
        Component name = elite.getCustomName();
        h.assertTrue(name != null && elite.isCustomNameVisible(), "floating name");
        h.assertFalse(name.getStyle().isItalic(), "name not italic");
        h.assertTrue(name.getStyle().getColor() != null && name.getStyle().getColor().getValue() == Rarity.EPIC.color, "3 affixes = Epic color");
        Pig a = spawn(h, EntityType.PIG, 2.5, 1.5, -1), b = spawn(h, EntityType.PIG, 3.5, 1.5, -1);
        elite.doHurtTarget(a);
        h.assertTrue(a.isOnFire(), "Fiery sets the target on fire");
        h.assertTrue(a.hasEffect(MobEffects.MOVEMENT_SLOWDOWN), "Frozen slows the target");
        h.assertTrue(b.getHealth() < b.getMaxHealth(), "Shocking jumps to a nearby creature");
        h.assertFalse(elite.hurt(h.getLevel().damageSources().inFire(), 5), "Fiery ignores fire");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void shieldedBlocksFirstHitThornyReflects(GameTestHelper h) {
        Zombie elite = spawn(h, EntityType.ZOMBIE, 1.5, 1.5, -1), hitter = spawn(h, EntityType.ZOMBIE, 2.5, 1.5, -1);
        Elites.make(elite, Elites.Affix.SHIELDED.bit() | Elites.Affix.THORNY.bit(), 0);
        float full = elite.getHealth();
        var hit = h.getLevel().damageSources().mobAttack(hitter);
        h.assertFalse(elite.hurt(hit, 4), "first hit blocked");
        near(h, full, elite.getHealth(), "no damage through the shield");
        h.assertTrue(elite.hurt(hit, 4), "second hit lands");
        float dealt = full - elite.getHealth(); // a bit under 4 through the zombie's armor
        h.assertTrue(dealt > 3, "second hit damage: " + dealt);
        h.assertTrue(hitter.getHealth() < hitter.getMaxHealth(), "Thorny reflects a share");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void eliteDropsSigil(GameTestHelper h) {
        ServerPlayer p = FakePlayerFactory.get(h.getLevel(), new GameProfile(UUID.randomUUID(), "habits-danger"));
        Drops.TEST_PLAYERS.add(p.getUUID());
        Zombie elite = spawn(h, EntityType.ZOMBIE, 1.5, 1.5, -1);
        Elites.make(elite, Elites.Affix.FROZEN.bit(), 0);
        elite.hurt(h.getLevel().damageSources().playerAttack(p), 1000);
        Drops.TEST_PLAYERS.remove(p.getUUID());
        h.assertTrue(elite.isDeadOrDying(), "elite died");
        int stored = 0;
        for (var kind : com.habits.progression.SigilData.ALL) stored += ProgressionApi.sigilCount(p, kind);
        h.assertTrue(stored >= 1, "a sigil went into the killer's book");
        AABB box = new AABB(elite.blockPosition()).inflate(3);
        h.assertTrue(h.getLevel().getEntitiesOfClass(ItemEntity.class, box, e -> SigilItem.dataOf(e.getItem()) != null).isEmpty(), "none on the ground");
        h.succeed();
    }
}
