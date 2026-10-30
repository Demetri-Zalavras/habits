package com.habits.trigger;

import com.habits.Habits;
import com.habits.core.CoreInit;
import com.habits.core.CoreRegistries;
import com.habits.core.IdMatch;
import com.habits.core.SkillDef;
import com.habits.core.SkillRegistry;
import com.habits.core.TargetKind;
import com.habits.core.TriggerSpec;
import com.habits.core.TriggerContext;
import com.habits.core.TriggerType;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.damagesource.DamageContainer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.StatAwardEvent;
import net.neoforged.neoforge.event.brewing.PlayerBrewedPotionEvent;
import net.neoforged.neoforge.event.entity.living.AnimalTameEvent;
import net.neoforged.neoforge.event.entity.living.BabyEntitySpawnEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;
import net.neoforged.neoforge.event.entity.living.LivingShieldBlockEvent;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.event.entity.player.AnvilRepairEvent;
import net.neoforged.neoforge.event.entity.player.BonemealEvent;
import net.neoforged.neoforge.event.entity.player.ItemFishedEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEnchantItemEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.PlayerWakeUpEvent;
import net.neoforged.neoforge.event.entity.player.PlayerXpEvent;
import net.neoforged.neoforge.event.entity.player.TradeWithVillagerEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Each trigger family reaches the gate with the right context. Real game paths where a fake player can drive them,
 * posted events where it can't (fakes never tick, never take damage, never award stats). */
@GameTestHolder(Habits.MODID)
@PrefixGameTestTemplate(false)
public class TriggerTests {
    private TriggerTests() {}

    record Fired(TriggerType type, TriggerContext ctx) {}

    /** A fake player let through the gate, plus everything fired for it. */
    static final class Rec implements Triggers.Spy {
        final GameTestHelper h;
        final ServerPlayer p;
        final List<Fired> got = new ArrayList<>();

        Rec(GameTestHelper h, boolean allow) {
            this.h = h;
            p = FakePlayerFactory.get(h.getLevel(), new GameProfile(UUID.randomUUID(), "habits-trig"));
            Vec3 at = h.absoluteVec(new Vec3(1.5, 2, 1.5));
            p.moveTo(at.x, at.y, at.z, 0, 0);
            if (allow) Triggers.TEST_PLAYERS.add(p.getUUID());
            Triggers.SPIES.add(this);
        }

        Rec(GameTestHelper h) {
            this(h, true);
        }

        @Override
        public void fired(ServerPlayer player, TriggerType type, TriggerContext ctx) {
            if (player == p) got.add(new Fired(type, ctx));
        }

        @Nullable
        TriggerContext find(TriggerType t) {
            for (Fired f : got) if (f.type == t) return f.ctx;
            return null;
        }

        TriggerContext one(TriggerType t) {
            TriggerContext c = find(t);
            h.assertTrue(c != null, t + " fired");
            return c;
        }

        void target(TriggerType t, String target) {
            h.assertValueEqual(String.valueOf(one(t).target()), target, t + " target");
        }

        void cleanup() {
            Triggers.SPIES.remove(this);
            Triggers.TEST_PLAYERS.remove(p.getUUID());
            TriggerEvents.STATES.remove(p.getUUID());
        }

        void done() {
            cleanup();
            h.succeed();
        }
    }

    static void post(net.neoforged.bus.api.Event e) {
        NeoForge.EVENT_BUS.post(e);
    }

    @GameTest(template = "empty")
    public static void gateDropsFakesAndSpectators(GameTestHelper h) {
        Rec r = new Rec(h, false);
        Triggers.fire(r.p, TriggerType.JUMP, TriggerContext.amount(1));
        post(new PlayerWakeUpEvent(r.p, false, true));
        h.assertTrue(r.got.isEmpty(), "unlisted fake player fired");
        Triggers.TEST_PLAYERS.add(r.p.getUUID());
        r.p.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
        Triggers.fire(r.p, TriggerType.JUMP, TriggerContext.amount(1));
        h.assertTrue(r.got.isEmpty(), "spectator fired");
        r.p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        Triggers.fire(r.p, TriggerType.JUMP, TriggerContext.amount(1));
        r.one(TriggerType.JUMP);
        r.done();
    }

    @GameTest(template = "empty")
    public static void blocks(GameTestHelper h) {
        Rec r = new Rec(h);
        BlockPos stone = h.absolutePos(new BlockPos(0, 1, 0));
        h.getLevel().setBlockAndUpdate(stone, Blocks.STONE.defaultBlockState());
        r.p.gameMode.destroyBlock(stone);
        r.target(TriggerType.BREAK_BLOCK, "minecraft:stone");
        h.assertValueEqual(r.one(TriggerType.BREAK_BLOCK).pos(), Vec3.atCenterOf(stone), "card drops at the block");
        h.assertTrue(r.find(TriggerType.HARVEST_CROP) == null, "stone is no crop");

        BlockPos wheat = h.absolutePos(new BlockPos(2, 2, 2));
        h.getLevel().setBlockAndUpdate(wheat.below(), Blocks.FARMLAND.defaultBlockState());
        h.getLevel().setBlockAndUpdate(wheat, ((CropBlock) Blocks.WHEAT).getStateForAge(7));
        r.p.gameMode.destroyBlock(wheat);
        r.target(TriggerType.HARVEST_CROP, "minecraft:wheat");

        // planting a seed: place + plant
        r.got.clear();
        r.p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WHEAT_SEEDS));
        BlockHitResult onFarm = new BlockHitResult(Vec3.atCenterOf(wheat.below()), Direction.UP, wheat.below(), false);
        r.p.getMainHandItem().useOn(new UseOnContext(r.p, InteractionHand.MAIN_HAND, onFarm));
        r.target(TriggerType.PLACE_BLOCK, "minecraft:wheat");
        r.target(TriggerType.PLANT, "minecraft:wheat");
        r.got.clear();

        BlockPos dirt = h.absolutePos(new BlockPos(0, 1, 2));
        h.getLevel().setBlockAndUpdate(dirt, Blocks.DIRT.defaultBlockState());
        r.p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_HOE));
        r.p.getMainHandItem().useOn(new UseOnContext(r.p, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(dirt), Direction.UP, dirt, false)));

        post(new BonemealEvent(r.p, h.getLevel(), wheat, h.getLevel().getBlockState(wheat), new ItemStack(Items.BONE_MEAL)));
        r.target(TriggerType.BONEMEAL, "minecraft:wheat");

        // a right-click counts only if it did something: the lever flips, the bare stone does nothing
        BlockPos lever = h.absolutePos(new BlockPos(1, 2, 3));
        h.getLevel().setBlockAndUpdate(lever.below(), Blocks.STONE.defaultBlockState());
        h.getLevel().setBlockAndUpdate(lever, Blocks.LEVER.defaultBlockState().setValue(net.minecraft.world.level.block.LeverBlock.FACE,
                net.minecraft.world.level.block.state.properties.AttachFace.FLOOR));
        r.p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        r.p.gameMode.useItemOn(r.p, h.getLevel(), ItemStack.EMPTY, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(lever), Direction.UP, lever, false));
        r.p.gameMode.useItemOn(r.p, h.getLevel(), ItemStack.EMPTY, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(lever.below()), Direction.NORTH, lever.below(), false));
        // placing cobblestone against the stone uses the cobblestone, not the stone
        ItemStack cobble = new ItemStack(Items.COBBLESTONE, 4);
        r.p.setItemInHand(InteractionHand.MAIN_HAND, cobble);
        r.p.gameMode.useItemOn(r.p, h.getLevel(), cobble, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(lever.below()).add(0, 0, -0.5), Direction.NORTH, lever.below(), false));
        h.assertTrue(h.getLevel().getBlockState(lever.below().north()).is(Blocks.COBBLESTONE), "cobblestone placed");
        r.p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        post(new PlayerContainerEvent.Open(r.p, r.p.inventoryMenu));
        r.one(TriggerType.OPEN_CONTAINER);
        // till and interact fire at the end of the tick, once the block really changed
        h.succeedWhen(() -> {
            List<String> clicked = new ArrayList<>();
            for (Fired f : r.got) if (f.type == TriggerType.INTERACT_BLOCK) clicked.add(String.valueOf(f.ctx.target()));
            h.assertValueEqual(clicked, List.of("minecraft:lever"), "only the click that did something");
            h.assertTrue(r.one(TriggerType.INTERACT_BLOCK).item() == null, "empty hand item is null");
            r.target(TriggerType.TILL, "minecraft:dirt");
            h.assertValueEqual(String.valueOf(r.one(TriggerType.TILL).item()), "minecraft:iron_hoe", "till tool");
            for (Fired f : r.got)
                h.assertFalse(f.type == TriggerType.PLACE_BLOCK && "minecraft:farmland".equals(String.valueOf(f.ctx.target())), "tilling is no placement");
            r.cleanup();
        });
    }

    /** Block triggers carry the main-hand tool, so a shovel-only skill matches a shovel break and not a bare-hand one
     * (the same IdMatch test core runs on a skill's {@code item}). */
    @GameTest(template = "empty")
    public static void blockTriggersCarryTheTool(GameTestHelper h) {
        Rec r = new Rec(h);
        IdMatch shovels = IdMatch.parse("#minecraft:shovels");
        var access = h.getLevel().registryAccess();
        BlockPos dirt = h.absolutePos(new BlockPos(1, 1, 1));
        h.getLevel().setBlockAndUpdate(dirt, Blocks.DIRT.defaultBlockState());
        r.p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SHOVEL));
        r.p.gameMode.destroyBlock(dirt);
        TriggerContext dug = r.one(TriggerType.BREAK_BLOCK);
        h.assertValueEqual(String.valueOf(dug.item()), "minecraft:iron_shovel", "break tool");
        h.assertTrue(shovels.test(dug.item(), TargetKind.ITEM, access), "shovel skill rolls on a shovel break");
        r.got.clear();
        h.getLevel().setBlockAndUpdate(dirt, Blocks.DIRT.defaultBlockState());
        r.p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        r.p.gameMode.destroyBlock(dirt);
        TriggerContext hand = r.one(TriggerType.BREAK_BLOCK);
        h.assertTrue(hand.item() == null, "bare hand has no tool");
        h.assertFalse(shovels.test(hand.item(), TargetKind.ITEM, access), "shovel skill skips a hand break");

        r.got.clear();
        BlockPos at = h.absolutePos(new BlockPos(1, 2, 1));
        r.p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.OAK_PLANKS));
        r.p.getMainHandItem().useOn(new UseOnContext(r.p, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(at.below()), Direction.UP, at.below(), false)));
        h.assertValueEqual(String.valueOf(r.one(TriggerType.PLACE_BLOCK).item()), "minecraft:oak_planks", "placed item");
        r.done();
    }

    /** lucky_charm ("finding cards"): every skill unlocked or levelled fires habits:card_dropped once, and one from that
     * event does not fire it again. */
    @GameTest(template = "empty")
    public static void unlockFiresCardDropped(GameTestHelper h) {
        Rec r = new Rec(h);
        com.habits.core.SkillXp.TEST_PLAYERS.add(r.p.getUUID());
        Triggers.fire(r.p, TriggerType.BREAK_BLOCK, TriggerContext.of(Blocks.STONE).withItem(Items.IRON_PICKAXE));
        int events = 0;
        for (Fired f : r.got) if (f.type == TriggerType.CUSTOM && Triggers.CARD_WON.equals(f.ctx.target())) events++;
        // every unlock counts (a Legendary one chains legendary_card, which can unlock wild_card), except those from card_dropped
        var fromEvent = SkillRegistry.candidates(TriggerType.CUSTOM, Triggers.CARD_WON);
        int unlocked = 0;
        for (var id : com.habits.core.HabitsApi.collection(r.p).skills().keySet()) if (!fromEvent.contains(SkillRegistry.get(id))) unlocked++;
        h.assertTrue(unlocked > 0, "stone mined with a pickaxe unlocks some skill");
        h.assertValueEqual(events, unlocked, "one card_dropped per unlock");
        h.assertValueEqual(com.habits.core.HabitsApi.level(r.p, Habits.id("lucky_charm")), 1, "lucky_charm unlocks from it, no event of its own");
        com.habits.core.SkillXp.TEST_PLAYERS.remove(r.p.getUUID());
        r.done();
    }

    /** overcharge's min is in levels spent (1 to 3 at a table); only the top slot unlocks it. */
    @GameTest(template = "empty")
    public static void enchantTopSlotUnlocksOvercharge(GameTestHelper h) {
        Rec r = new Rec(h);
        com.habits.core.SkillXp.TEST_PLAYERS.add(r.p.getUUID());
        var sharp = ResourceLocation.withDefaultNamespace("sharpness");
        Triggers.fire(r.p, TriggerType.ENCHANT, new TriggerContext(sharp, 2, null, null));
        h.assertValueEqual(com.habits.core.HabitsApi.level(r.p, Habits.id("overcharge")), 0, "a 2-level enchant is not the top slot");
        Triggers.fire(r.p, TriggerType.ENCHANT, new TriggerContext(sharp, 3, null, null));
        h.assertValueEqual(com.habits.core.HabitsApi.level(r.p, Habits.id("overcharge")), 1, "top-slot enchant (3 levels) unlocks overcharge");
        com.habits.core.SkillXp.TEST_PLAYERS.remove(r.p.getUUID());
        r.done();
    }

    @GameTest(template = "empty")
    public static void combat(GameTestHelper h) {
        Rec r = new Rec(h);
        r.p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        Zombie z1 = h.spawn(EntityType.ZOMBIE, new BlockPos(1, 2, 1));
        z1.hurt(r.p.damageSources().playerAttack(r.p), 2);
        TriggerContext melee = r.one(TriggerType.DAMAGE_DEALT);
        h.assertValueEqual(String.valueOf(melee.target()), "minecraft:player_attack", "melee type");
        h.assertValueEqual(String.valueOf(melee.item()), "minecraft:iron_sword", "melee weapon");
        h.assertValueEqual(String.valueOf(melee.entity()), "minecraft:zombie", "victim");
        h.assertTrue(melee.amount() > 0, "damage amount");

        // an arrow's damage belongs to the shooter, with the bow as the weapon
        r.got.clear();
        Arrow arrow = new Arrow(h.getLevel(), r.p, new ItemStack(Items.ARROW), new ItemStack(Items.BOW));
        h.getLevel().addFreshEntity(arrow);
        r.target(TriggerType.SHOOT_PROJECTILE, "minecraft:bow");
        Zombie z2 = h.spawn(EntityType.ZOMBIE, new BlockPos(3, 2, 3));
        z2.hurt(r.p.damageSources().arrow(arrow, r.p), 3);
        TriggerContext ranged = r.one(TriggerType.DAMAGE_DEALT);
        h.assertValueEqual(String.valueOf(ranged.target()), "minecraft:arrow", "arrow type");
        h.assertValueEqual(String.valueOf(ranged.item()), "minecraft:bow", "arrow weapon");
        arrow.discard();

        Zombie z3 = h.spawn(EntityType.ZOMBIE, new BlockPos(2, 2, 1));
        z3.hurt(r.p.damageSources().playerAttack(r.p), 1000);
        TriggerContext kill = r.one(TriggerType.KILL_ENTITY);
        h.assertValueEqual(String.valueOf(kill.target()), "minecraft:zombie", "kill target");
        h.assertValueEqual(String.valueOf(kill.item()), "minecraft:iron_sword", "kill weapon");
        h.assertValueEqual(kill.pos(), z3.position(), "card drops at the victim");

        // fakes are invulnerable and never die: post what vanilla would
        Zombie z4 = h.spawn(EntityType.ZOMBIE, new BlockPos(1, 2, 3));
        z4.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE_AXE));
        var hit = r.p.damageSources().mobAttack(z4);
        post(new LivingDamageEvent.Post(r.p, new DamageContainer(hit, 4)));
        TriggerContext taken = r.one(TriggerType.DAMAGE_TAKEN);
        h.assertValueEqual(String.valueOf(taken.target()), "minecraft:mob_attack", "taken type");
        h.assertValueEqual(String.valueOf(taken.entity()), "minecraft:zombie", "attacker");
        h.assertValueEqual(String.valueOf(taken.item()), "minecraft:stone_axe", "attacker weapon");
        post(new LivingShieldBlockEvent(r.p, new DamageContainer(hit, 4), true));
        r.target(TriggerType.SHIELD_BLOCK, "minecraft:mob_attack");
        post(new LivingDeathEvent(r.p, r.p.damageSources().lava()));
        r.target(TriggerType.DIE, "minecraft:lava");
        h.assertTrue(r.one(TriggerType.DIE).entity() == null, "environment killer is null");
        post(new PlayerEvent.PlayerRespawnEvent(r.p, false));
        r.one(TriggerType.RESPAWN);
        r.done();
    }

    /** Regression: StatAwardEvent carries the stat's new TOTAL. A player who already walked 4 km and walks 2.5 more
     * blocks must roll 2.5 blocks, not 4002.5 (cards dropped every few steps in the first playtest). */
    @GameTest(template = "empty")
    public static void statTriggersUseTheIncrementNotTheTotal(GameTestHelper h) {
        Rec r = new Rec(h);
        TriggerEvents.poll(r.p);
        var walk = Stats.CUSTOM.get(Stats.WALK_ONE_CM);
        var jump = Stats.CUSTOM.get(Stats.JUMP);
        r.p.getStats().increment(r.p, walk, 400_000);
        r.p.getStats().increment(r.p, jump, 500);
        TriggerEvents.poll(r.p);
        r.got.clear();
        r.p.getStats().increment(r.p, walk, 250); // the real vanilla path: increment -> setValue(total) -> event
        r.p.getStats().increment(r.p, jump, 1);
        TriggerEvents.state(r.p).biome = null;
        TriggerEvents.poll(r.p);
        h.assertValueEqual(r.one(TriggerType.WALK_DISTANCE).amount(), 2.5, "walk blocks this second, not lifetime");
        h.assertValueEqual(r.one(TriggerType.JUMP).amount(), 1.0, "one jump, not 501");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void statsAndMovement(GameTestHelper h) {
        Rec r = new Rec(h);
        TriggerEvents.poll(r.p); // first poll only records biome/structures
        post(new StatAwardEvent(r.p, Stats.CUSTOM.get(Stats.WALK_ONE_CM), 250));
        post(new StatAwardEvent(r.p, Stats.CUSTOM.get(Stats.WALK_ONE_CM), 50));
        post(new StatAwardEvent(r.p, Stats.CUSTOM.get(Stats.SWIM_ONE_CM), 100));
        post(new StatAwardEvent(r.p, Stats.CUSTOM.get(Stats.JUMP), 3));
        post(new StatAwardEvent(r.p, Stats.CUSTOM.get(Stats.BELL_RING), 1));
        post(new PlayerXpEvent.PickupXp(r.p, new ExperienceOrb(h.getLevel(), 0, 0, 0, 7)));
        h.assertTrue(r.got.isEmpty(), "per-unit triggers wait for the flush");
        TriggerEvents.state(r.p).biome = ResourceLocation.withDefaultNamespace("not_here");
        TriggerEvents.state(r.p).biomeSeen.clear(); // never been here
        TriggerEvents.poll(r.p);
        h.assertValueEqual(r.one(TriggerType.WALK_DISTANCE).amount(), 3.0, "walk blocks");
        h.assertValueEqual(r.one(TriggerType.SWIM_DISTANCE).amount(), 1.0, "swim blocks");
        h.assertValueEqual(r.one(TriggerType.JUMP).amount(), 3.0, "jumps");
        h.assertValueEqual(r.one(TriggerType.XP_PICKUP).amount(), 7.0, "xp");
        int stats = 0;
        for (Fired f : r.got) if (f.type == TriggerType.STAT_INCREMENT) stats++;
        h.assertValueEqual(stats, 4, "one STAT_INCREMENT per changed stat");
        String biome = h.getLevel().getBiome(r.p.blockPosition()).unwrapKey().orElseThrow().location().toString();
        r.target(TriggerType.ENTER_BIOME, biome);
        r.got.clear();
        TriggerEvents.poll(r.p);
        h.assertTrue(r.got.isEmpty(), "nothing new, nothing fired");

        post(new LivingFallEvent(r.p, 5, 1));
        h.assertValueEqual(r.one(TriggerType.FALL).amount(), 5.0, "fall blocks");
        post(new PlayerXpEvent.LevelChange(r.p, 1));
        h.assertValueEqual(r.one(TriggerType.LEVEL_UP_XP).amount(), (double) r.p.experienceLevel + 1, "new level");
        r.done();
    }

    /** Bouncing over a biome border is not a new entry: the biome counts again only after a minute outside it. */
    @GameTest(template = "empty")
    public static void biomeEntryNeedsAMinuteAway(GameTestHelper h) {
        Rec r = new Rec(h);
        TriggerEvents.State st = TriggerEvents.state(r.p);
        ResourceLocation other = ResourceLocation.withDefaultNamespace("not_here");
        TriggerEvents.poll(r.p); // records the biome the player stands in
        ResourceLocation here = st.biome;
        st.biome = other; // stepped out and straight back in
        TriggerEvents.poll(r.p);
        h.assertTrue(r.find(TriggerType.ENTER_BIOME) == null, "back within a minute: no entry");
        st.biomeSeen.put(here, st.biomeSeen.getLong(here) - TriggerEvents.AWAY_TICKS); // a minute ago
        st.biome = other;
        TriggerEvents.poll(r.p);
        r.target(TriggerType.ENTER_BIOME, here.toString());
        r.done();
    }

    @GameTest(template = "empty")
    public static void itemsAndFood(GameTestHelper h) {
        Rec r = new Rec(h);
        post(new StatAwardEvent(r.p, Stats.ITEM_USED.get(Items.WATER_BUCKET), 1));
        r.target(TriggerType.USE_ITEM, "minecraft:water_bucket");
        r.target(TriggerType.BUCKET_EMPTY, "minecraft:water_bucket");
        post(new StatAwardEvent(r.p, Stats.ITEM_BROKEN.get(Items.IRON_PICKAXE), 1));
        r.target(TriggerType.ITEM_BREAK, "minecraft:iron_pickaxe");
        post(new StatAwardEvent(r.p, Stats.ITEM_PICKED_UP.get(CoreRegistries.CARD.get()), 1));
        h.assertTrue(r.find(TriggerType.PICKUP_ITEM) == null, "cards never fire pickup");
        post(new StatAwardEvent(r.p, Stats.ITEM_PICKED_UP.get(Items.COBBLESTONE), 5));
        h.assertValueEqual(r.one(TriggerType.PICKUP_ITEM).amount(), 5.0, "pickup count");

        post(new LivingEntityUseItemEvent.Finish(r.p, new ItemStack(Items.BREAD), 0, ItemStack.EMPTY));
        r.target(TriggerType.EAT, "minecraft:bread");
        post(new LivingEntityUseItemEvent.Finish(r.p, PotionContents.createItemStack(Items.POTION, Potions.WATER), 0, ItemStack.EMPTY));
        h.assertTrue(r.find(TriggerType.DRINK_POTION) == null, "a water bottle is not a potion");
        post(new LivingEntityUseItemEvent.Finish(r.p, PotionContents.createItemStack(Items.POTION, Potions.SWIFTNESS), 0, ItemStack.EMPTY));
        r.target(TriggerType.DRINK_POTION, "minecraft:swiftness");

        // fill from a water source under the player's feet, looking down
        BlockPos water = h.absolutePos(new BlockPos(1, 1, 1));
        h.getLevel().setBlockAndUpdate(water, Blocks.WATER.defaultBlockState());
        r.p.moveTo(water.getX() + 0.5, water.getY() + 1, water.getZ() + 0.5, 0, 90);
        r.p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BUCKET));
        post(new PlayerInteractEvent.RightClickItem(r.p, InteractionHand.MAIN_HAND));
        r.target(TriggerType.BUCKET_FILL, "minecraft:water_bucket");
        r.done();
    }

    @GameTest(template = "empty")
    public static void crafting(GameTestHelper h) {
        Rec r = new Rec(h);
        post(new PlayerEvent.ItemCraftedEvent(r.p, new ItemStack(Items.STICK, 4), new SimpleContainer(1)));
        h.assertValueEqual(r.one(TriggerType.CRAFT).amount(), 4.0, "craft count");
        r.target(TriggerType.CRAFT, "minecraft:stick");
        post(new PlayerEvent.ItemSmeltedEvent(r.p, new ItemStack(Items.IRON_INGOT, 2)));
        r.target(TriggerType.SMELT, "minecraft:iron_ingot");
        post(new PlayerBrewedPotionEvent(r.p, PotionContents.createItemStack(Items.POTION, Potions.SWIFTNESS)));
        r.target(TriggerType.BREW, "minecraft:swiftness");
        post(new AnvilRepairEvent(r.p, new ItemStack(Items.IRON_SWORD), ItemStack.EMPTY, new ItemStack(Items.IRON_SWORD)));
        r.target(TriggerType.ANVIL, "minecraft:iron_sword");
        Villager v = h.spawn(EntityType.VILLAGER, new BlockPos(2, 2, 2));
        post(new TradeWithVillagerEvent(r.p, new MerchantOffer(new ItemCost(Items.EMERALD, 1), new ItemStack(Items.BREAD, 6), 1, 1, 0), v));
        h.assertValueEqual(r.one(TriggerType.TRADE).amount(), 6.0, "trade count");
        var sharp = h.getLevel().registryAccess().registryOrThrow(Registries.ENCHANTMENT).getHolderOrThrow(Enchantments.SHARPNESS);
        post(new PlayerEnchantItemEvent(r.p, new ItemStack(Items.IRON_SWORD), List.of(new EnchantmentInstance(sharp, 1))));
        // the enchant fires at the end of the tick (it reads the lapis the menu takes after the event)
        h.succeedWhen(() -> {
            r.target(TriggerType.ENCHANT, "minecraft:sharpness");
            r.cleanup();
        });
    }

    @GameTest(template = "empty")
    public static void nature(GameTestHelper h) {
        Rec r = new Rec(h);
        FishingHook hook = new FishingHook(r.p, h.getLevel(), 0, 0);
        post(new ItemFishedEvent(List.of(new ItemStack(Items.COD)), 1, hook));
        r.target(TriggerType.FISH, "minecraft:cod");
        Cow a = h.spawn(EntityType.COW, new BlockPos(1, 2, 1));
        Cow b = h.spawn(EntityType.COW, new BlockPos(2, 2, 1));
        // the love cause is looked up in the level's player list, which never holds a fake
        post(new BabyEntitySpawnEvent(a, b, EntityType.COW.create(h.getLevel())) {
            @Override
            public net.minecraft.world.entity.player.Player getCausedByPlayer() {
                return r.p;
            }
        });
        r.target(TriggerType.BREED, "minecraft:cow");
        Wolf w = h.spawn(EntityType.WOLF, new BlockPos(3, 2, 1));
        post(new AnimalTameEvent(w, r.p));
        r.target(TriggerType.TAME, "minecraft:wolf");

        r.p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BUCKET));
        post(new PlayerInteractEvent.EntityInteract(r.p, InteractionHand.MAIN_HAND, a));
        TriggerContext c = r.one(TriggerType.INTERACT_ENTITY);
        h.assertValueEqual(String.valueOf(c.target()), "minecraft:cow", "interact target");
        h.assertValueEqual(String.valueOf(c.item()), "minecraft:bucket", "interact item");
        r.target(TriggerType.BUCKET_FILL, "minecraft:milk_bucket");
        Sheep s = h.spawn(EntityType.SHEEP, new BlockPos(1, 2, 3));
        r.p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.SHEARS));
        post(new PlayerInteractEvent.EntityInteract(r.p, InteractionHand.MAIN_HAND, s));
        r.target(TriggerType.SHEAR, "minecraft:sheep");
        r.done();
    }

    @GameTest(template = "empty")
    public static void world(GameTestHelper h) {
        Rec r = new Rec(h);
        post(new PlayerEvent.PlayerChangedDimensionEvent(r.p, Level.OVERWORLD, Level.NETHER));
        r.target(TriggerType.ENTER_DIMENSION, "minecraft:the_nether");
        post(new PlayerWakeUpEvent(r.p, false, true));
        r.one(TriggerType.SLEEP);
        r.got.clear();
        post(new PlayerWakeUpEvent(r.p, true, true));
        h.assertTrue(r.find(TriggerType.SLEEP) == null, "leaving the bed early is no sleep");
        var adv = h.getLevel().getServer().getAdvancements().get(ResourceLocation.withDefaultNamespace("story/mine_stone"));
        h.assertTrue(adv != null, "vanilla advancement loaded");
        post(new AdvancementEvent.AdvancementEarnEvent(r.p, adv));
        r.target(TriggerType.ADVANCEMENT, "minecraft:story/mine_stone");
        r.done();
    }
}
