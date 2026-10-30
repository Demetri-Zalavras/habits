package com.habits.trigger;

import com.habits.core.CoreRegistries;
import com.habits.core.TriggerContext;
import com.habits.core.TriggerType;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stat;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Shearable;
import net.minecraft.world.entity.animal.Bucketable;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.goat.Goat;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ItemSupplier;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MilkBucketItem;
import net.minecraft.world.item.SolidBucketItem;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.BambooSaplingBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.CaveVines;
import net.minecraft.world.level.block.ChorusFlowerBlock;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.KelpBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.PitcherCropBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.StatAwardEvent;
import net.neoforged.neoforge.event.brewing.PlayerBrewedPotionEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
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
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Every game hook that turns into a trigger. Listeners run at LOWEST so cancelled events (skipped) and other mods'
 * edits are already settled. Per-unit stats are batched per player and flushed once a second with the world checks. */
public final class TriggerEvents {
    private TriggerEvents() {}

    static final int POLL_TICKS = 20;

    /** Per-player batch + last-seen world state. Cleared on logout and server stop. */
    static final class State {
        final Object2IntOpenHashMap<ResourceLocation> stats = new Object2IntOpenHashMap<>();
        int xp;
        @Nullable ResourceLocation mount, biome;
        @Nullable Set<ResourceLocation> structures;
        /** Server tick each biome / structure was last seen: an entry counts only after {@link #AWAY_TICKS} outside. */
        final Object2LongOpenHashMap<ResourceLocation> biomeSeen = new Object2LongOpenHashMap<>(),
                structureSeen = new Object2LongOpenHashMap<>();
    }

    /** One minute: walking back and forth over a biome or structure border is not a new entry. */
    static final long AWAY_TICKS = 1200;

    static boolean away(Object2LongOpenHashMap<ResourceLocation> seen, ResourceLocation id, long tick) {
        return !seen.containsKey(id) || tick - seen.getLong(id) >= AWAY_TICKS;
    }

    static final Map<UUID, State> STATES = new HashMap<>();

    /** Checks that must wait until vanilla finished the action, run at the end of the tick. Not server.tell: a server
     * that is behind skips its task queue between ticks, which starved these for seconds under load. */
    static final List<Runnable> LATER = new ArrayList<>();

    static State state(Player p) {
        return STATES.computeIfAbsent(p.getUUID(), u -> new State());
    }

    /** minecraft:custom cm/count stats -> movement trigger. Stats not listed only fire STAT_INCREMENT. */
    static final Map<ResourceLocation, TriggerType> MOVES = new HashMap<>();
    static final Set<ResourceLocation> RIDES = Set.of(Stats.HORSE_ONE_CM, Stats.PIG_ONE_CM, Stats.STRIDER_ONE_CM, Stats.MINECART_ONE_CM);

    static {
        MOVES.put(Stats.WALK_ONE_CM, TriggerType.WALK_DISTANCE);
        MOVES.put(Stats.SPRINT_ONE_CM, TriggerType.SPRINT_DISTANCE);
        MOVES.put(Stats.CROUCH_ONE_CM, TriggerType.SNEAK_DISTANCE);
        MOVES.put(Stats.SWIM_ONE_CM, TriggerType.SWIM_DISTANCE);
        MOVES.put(Stats.WALK_UNDER_WATER_ONE_CM, TriggerType.SWIM_DISTANCE);
        MOVES.put(Stats.WALK_ON_WATER_ONE_CM, TriggerType.SWIM_DISTANCE);
        MOVES.put(Stats.CLIMB_ONE_CM, TriggerType.CLIMB_DISTANCE);
        MOVES.put(Stats.AVIATE_ONE_CM, TriggerType.ELYTRA_DISTANCE);
        MOVES.put(Stats.BOAT_ONE_CM, TriggerType.BOAT_DISTANCE);
        for (ResourceLocation r : RIDES) MOVES.put(r, TriggerType.RIDE_DISTANCE);
        MOVES.put(Stats.JUMP, TriggerType.JUMP);
    }

    static ResourceLocation id(Item item) {
        return BuiltInRegistries.ITEM.getKey(item);
    }

    static ResourceLocation id(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block);
    }

    static ResourceLocation id(Entity e) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
    }

    static ResourceLocation id(DamageSource s) {
        return s.typeHolder().unwrapKey().map(ResourceKey::location).orElse(null);
    }

    @Nullable
    static ResourceLocation potion(ItemStack stack) {
        PotionContents pc = stack.get(DataComponents.POTION_CONTENTS);
        return pc == null ? null : pc.potion().flatMap(Holder::unwrapKey).map(ResourceKey::location).orElse(null);
    }

    @Nullable
    public static ResourceLocation itemOrNull(@Nullable ItemStack s) {
        return s == null || s.isEmpty() ? null : id(s.getItem());
    }

    static void fire(Player p, TriggerType t, @Nullable ResourceLocation target, double amount, @Nullable ResourceLocation item,
                     @Nullable ResourceLocation entity) {
        fire(p, t, target, amount, item, entity, null);
    }

    /** {@code pos}: where a won card drops (null = at the player). */
    static void fire(Player p, TriggerType t, @Nullable ResourceLocation target, double amount, @Nullable ResourceLocation item,
                     @Nullable ResourceLocation entity, @Nullable Vec3 pos) {
        Triggers.fire(p, t, new TriggerContext(target, amount, item, entity).withPos(pos));
    }

    // ---------------------------------------------------------------- blocks

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBreak(BlockEvent.BreakEvent e) {
        rollBreak(e.getPlayer(), e.getState(), e.getPos(), itemOrNull(e.getPlayer().getMainHandItem()));
    }

    /** The rolls of one broken block. Perks that break extra blocks call this after each real break (their own
     * BreakEvent is muted by the busy flags). {@code tool}: what was held before the break. */
    public static void rollBreak(Player p, BlockState s, BlockPos pos, @Nullable ResourceLocation tool) {
        fire(p, TriggerType.BREAK_BLOCK, id(s.getBlock()), 1, tool, null, Vec3.atCenterOf(pos));
        if (mature(s)) fire(p, TriggerType.HARVEST_CROP, id(s.getBlock()), 1, tool, null, Vec3.atCenterOf(pos));
    }

    static boolean mature(BlockState s) {
        Block b = s.getBlock();
        if (b instanceof CropBlock c) return c.isMaxAge(s);
        if (b == Blocks.MELON || b == Blocks.PUMPKIN) return true;
        if (b instanceof CocoaBlock) return s.getValue(CocoaBlock.AGE) >= CocoaBlock.MAX_AGE;
        if (b instanceof NetherWartBlock) return s.getValue(NetherWartBlock.AGE) >= NetherWartBlock.MAX_AGE;
        if (b instanceof SweetBerryBushBlock) return s.getValue(SweetBerryBushBlock.AGE) >= 2;
        if (b instanceof PitcherCropBlock) return s.getValue(PitcherCropBlock.AGE) >= PitcherCropBlock.MAX_AGE;
        return CaveVines.hasGlowBerries(s);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlace(BlockEvent.EntityPlaceEvent e) {
        // tilling, stripping and pathing also run through the place event; only a block item really places
        if (!(e.getEntity() instanceof Player p) || !(p.getMainHandItem().getItem() instanceof BlockItem
                || p.getOffhandItem().getItem() instanceof BlockItem)) return;
        Block b = e.getPlacedBlock().getBlock();
        ResourceLocation placed = itemOrNull(p.getMainHandItem().getItem() instanceof BlockItem ? p.getMainHandItem() : p.getOffhandItem());
        fire(p, TriggerType.PLACE_BLOCK, id(b), 1, placed, null, Vec3.atCenterOf(e.getPos()));
        if (b instanceof BushBlock || b instanceof CocoaBlock || b instanceof SugarCaneBlock || b instanceof CactusBlock
                || b instanceof BambooSaplingBlock || b instanceof KelpBlock || b instanceof ChorusFlowerBlock)
            fire(p, TriggerType.PLANT, id(b), 1, null, null, Vec3.atCenterOf(e.getPos()));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onToolModify(BlockEvent.BlockToolModificationEvent e) {
        // vanilla decides the new state after this event, so check at the end of the tick that the block really changed
        if (e.isSimulated() || !(e.getPlayer() instanceof ServerPlayer p)) return;
        BlockState before = e.getState();
        BlockPos pos = e.getPos();
        ResourceLocation tool = itemOrNull(e.getHeldItemStack());
        LATER.add(() -> {
            if (p.level().getBlockState(pos) != before) fire(p, TriggerType.TILL, id(before.getBlock()), 1, tool, null, Vec3.atCenterOf(pos));
        });
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBonemeal(BonemealEvent e) {
        if (e.getPlayer() != null && e.isValidBonemealTarget())
            fire(e.getPlayer(), TriggerType.BONEMEAL, id(e.getState().getBlock()), 1, null, null, Vec3.atCenterOf(e.getPos()));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock e) {
        Player p = e.getEntity();
        BlockState s = e.getLevel().getBlockState(e.getPos());
        // berries picked by hand are a harvest; the bush stays
        if (e.getHand() == InteractionHand.MAIN_HAND && (s.getBlock() instanceof SweetBerryBushBlock || CaveVines.hasGlowBerries(s)) && mature(s))
            fire(p, TriggerType.HARVEST_CROP, id(s.getBlock()), 1, itemOrNull(p.getMainHandItem()), null, Vec3.atCenterOf(e.getPos()));
        if (e.getHand() != InteractionHand.MAIN_HAND || (p.isSecondaryUseActive() && !p.getMainHandItem().isEmpty()) || s.isAir()) return;
        // the event can't see the result: at the end of the tick, count it only if the block changed, a menu opened or
        // the held item was used (placing a block item against it is not using it). Vanilla gives the fletching table no
        // use, so clicking it is the whole action.
        BlockPos pos = e.getPos().immutable();
        ItemStack held = p.getMainHandItem().copy();
        var menu = p.containerMenu;
        ResourceLocation item = itemOrNull(e.getItemStack());
        LATER.add(() -> {
            if (p.level().getBlockState(pos) != s || p.containerMenu != menu || !(held.getItem() instanceof BlockItem) && !ItemStack.matches(held, p.getMainHandItem())
                    || s.is(Blocks.FLETCHING_TABLE))
                fire(p, TriggerType.INTERACT_BLOCK, id(s.getBlock()), 1, item, null, Vec3.atCenterOf(pos));
        });
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onOpen(PlayerContainerEvent.Open e) {
        Player p = e.getEntity();
        ResourceLocation target = null;
        Vec3 at = null;
        if (p.pick(p.blockInteractionRange(), 1, false) instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = hit.getBlockPos();
            BlockState s = p.level().getBlockState(pos);
            if (s.getMenuProvider(p.level(), pos) != null || p.level().getBlockEntity(pos) instanceof MenuProvider) {
                target = id(s.getBlock());
                at = Vec3.atCenterOf(pos);
            }
        }
        fire(p, TriggerType.OPEN_CONTAINER, target, 1, null, null, at);
    }

    // ---------------------------------------------------------------- combat

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDamage(LivingDamageEvent.Post e) {
        DamageSource src = e.getSource();
        LivingEntity victim = e.getEntity();
        if (src.getEntity() instanceof Player p && p != victim)
            fire(p, TriggerType.DAMAGE_DEALT, id(src), e.getNewDamage(), itemOrNull(src.getWeaponItem()), id(victim), victim.position());
        if (victim instanceof Player p) {
            Entity attacker = src.getEntity() != null ? src.getEntity() : src.getDirectEntity();
            fire(p, TriggerType.DAMAGE_TAKEN, id(src), e.getNewDamage(),
                    attacker instanceof LivingEntity l ? itemOrNull(l.getMainHandItem()) : null, attacker == null ? null : id(attacker));
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDeath(LivingDeathEvent e) {
        DamageSource src = e.getSource();
        if (src.getEntity() instanceof Player p && p != e.getEntity()) {
            ItemStack weapon = src.getWeaponItem();
            fire(p, TriggerType.KILL_ENTITY, id(e.getEntity()), 1, itemOrNull(weapon != null ? weapon : p.getMainHandItem()), null, e.getEntity().position());
        }
        if (e.getEntity() instanceof Player p) {
            Entity killer = src.getEntity() != null ? src.getEntity() : src.getDirectEntity();
            fire(p, TriggerType.DIE, id(src), 1, null, killer == null ? null : id(killer));
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onShield(LivingShieldBlockEvent e) {
        if (!e.getBlocked() || !(e.getEntity() instanceof Player p)) return;
        Entity attacker = e.getDamageSource().getEntity() != null ? e.getDamageSource().getEntity() : e.getDamageSource().getDirectEntity();
        fire(p, TriggerType.SHIELD_BLOCK, id(e.getDamageSource()), e.getBlockedDamage(), null, attacker == null ? null : id(attacker));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onProjectile(EntityJoinLevelEvent e) {
        if (e.loadedFromDisk() || !(e.getEntity() instanceof Projectile pr) || !(pr.getOwner() instanceof Player p)) return;
        ItemStack used = ItemStack.EMPTY;
        if (pr instanceof AbstractArrow a && a.getWeaponItem() != null) used = a.getWeaponItem();
        if (used.isEmpty() && pr instanceof ItemSupplier s) used = s.getItem();
        if (used.isEmpty()) used = p.getMainHandItem();
        fire(p, TriggerType.SHOOT_PROJECTILE, itemOrNull(used), 1, null, null);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent e) {
        if (!e.isEndConquered()) fire(e.getEntity(), TriggerType.RESPAWN, null, 1, null, null);
    }

    // ---------------------------------------------------------------- items and food

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onUseFinish(LivingEntityUseItemEvent.Finish e) {
        if (!(e.getEntity() instanceof Player p)) return;
        ItemStack s = e.getItem();
        if (s.has(DataComponents.FOOD)) fire(p, TriggerType.EAT, id(s.getItem()), 1, null, null);
        // water, awkward, mundane and thick bottles have no effect: not a potion
        else if (s.is(Items.POTION) && s.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY).hasEffects())
            fire(p, TriggerType.DRINK_POTION, potion(s), 1, null, null);
    }

    /** Filling a bucket from a fluid or powder snow: predicted from the same ray the bucket uses (the stat lands after
     * the block is gone, so it can't tell what was picked up). */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem e) {
        Player p = e.getEntity();
        if (!e.getItemStack().is(Items.BUCKET)) return;
        Vec3 eye = p.getEyePosition();
        Vec3 end = eye.add(p.getViewVector(1).scale(p.blockInteractionRange()));
        BlockHitResult hit = p.level().clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, p));
        if (hit.getType() != HitResult.Type.BLOCK) return;
        BlockState s = p.level().getBlockState(hit.getBlockPos());
        if (!(s.getBlock() instanceof BucketPickup)) return;
        Item filled = s.is(Blocks.POWDER_SNOW) ? Items.POWDER_SNOW_BUCKET
                : s.getFluidState().isSource() ? s.getFluidState().getType().getBucket() : Items.AIR;
        if (filled != Items.AIR) fire(p, TriggerType.BUCKET_FILL, id(filled), 1, null, null, Vec3.atCenterOf(hit.getBlockPos()));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract e) {
        Player p = e.getEntity();
        Entity t = e.getTarget();
        ItemStack s = e.getItemStack();
        if (e.getHand() == InteractionHand.MAIN_HAND) fire(p, TriggerType.INTERACT_ENTITY, id(t), 1, itemOrNull(s), null, t.position());
        if (s.is(Tags.Items.TOOLS_SHEAR) && t instanceof Shearable sh && sh.readyForShearing())
            fire(p, TriggerType.SHEAR, id(t), 1, null, null, t.position());
        boolean adult = !(t instanceof LivingEntity l) || !l.isBaby();
        if (s.is(Items.BUCKET) && (t instanceof Cow || t instanceof Goat) && adult)
            fire(p, TriggerType.BUCKET_FILL, id(Items.MILK_BUCKET), 1, null, null, t.position());
        if (s.is(Items.WATER_BUCKET) && t instanceof Bucketable b && t.isAlive())
            fire(p, TriggerType.BUCKET_FILL, id(b.getBucketItemStack().getItem()), 1, null, null, t.position());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onXpPickup(PlayerXpEvent.PickupXp e) {
        if (Triggers.allowed(e.getEntity())) state(e.getEntity()).xp += e.getOrb().getValue();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLevel(PlayerXpEvent.LevelChange e) {
        if (e.getLevels() > 0) fire(e.getEntity(), TriggerType.LEVEL_UP_XP, null, e.getEntity().experienceLevel + e.getLevels(), null, null);
    }

    /** Stats cover item use, breaks and pickups exactly (vanilla only awards them on success), plus every custom stat. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onStat(StatAwardEvent e) {
        Player p = e.getEntity();
        Stat<?> stat = e.getStat();
        int delta = Triggers.statDelta(e);
        if (!Triggers.allowed(p) || delta <= 0) return;
        if (stat.getType() == Stats.CUSTOM && stat.getValue() instanceof ResourceLocation r) {
            State st = state(p);
            st.stats.addTo(r, delta);
            if (RIDES.contains(r) && p.getVehicle() != null) st.mount = id(p.getVehicle());
        } else if (stat.getValue() instanceof Item item) {
            if (stat.getType() == Stats.ITEM_USED) {
                fire(p, TriggerType.USE_ITEM, id(item), 1, null, null);
                if (item != Items.BUCKET && (item instanceof BucketItem || item instanceof SolidBucketItem || item instanceof MilkBucketItem))
                    fire(p, TriggerType.BUCKET_EMPTY, id(item), 1, null, null);
            } else if (stat.getType() == Stats.ITEM_BROKEN) {
                fire(p, TriggerType.ITEM_BREAK, id(item), 1, null, null);
            } else if (stat.getType() == Stats.ITEM_PICKED_UP && item != CoreRegistries.CARD.get()) {
                fire(p, TriggerType.PICKUP_ITEM, id(item), delta, null, null);
            }
        }
    }

    // ---------------------------------------------------------------- crafting

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onCraft(PlayerEvent.ItemCraftedEvent e) {
        ItemStack s = e.getCrafting();
        if (!s.isEmpty()) fire(e.getEntity(), TriggerType.CRAFT, id(s.getItem()), s.getCount(), null, null);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onSmelt(PlayerEvent.ItemSmeltedEvent e) {
        ItemStack s = e.getSmelting();
        if (!s.isEmpty()) fire(e.getEntity(), TriggerType.SMELT, id(s.getItem()), s.getCount(), null, null);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBrew(PlayerBrewedPotionEvent e) {
        fire(e.getEntity(), TriggerType.BREW, potion(e.getStack()), Math.max(1, e.getStack().getCount()), null, null);
    }

    /** Levels spent = lapis consumed, which the menu takes right after this event; read it at the end of the tick. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onEnchant(PlayerEnchantItemEvent e) {
        Player p = e.getEntity();
        if (e.getEnchantments().isEmpty() || !(p instanceof ServerPlayer)) return;
        ResourceLocation ench = e.getEnchantments().get(0).enchantment.unwrapKey().map(ResourceKey::location).orElse(null);
        ItemStack lapis = p.containerMenu instanceof EnchantmentMenu m ? m.getSlot(1).getItem() : ItemStack.EMPTY;
        int before = lapis.getCount();
        LATER.add(() -> fire(p, TriggerType.ENCHANT, ench, Math.max(1, before - lapis.getCount()), null, null));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onAnvil(AnvilRepairEvent e) {
        int cost = e.getEntity().containerMenu instanceof AnvilMenu m ? m.getCost() : 0;
        fire(e.getEntity(), TriggerType.ANVIL, itemOrNull(e.getOutput()), cost, null, null);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onTrade(TradeWithVillagerEvent e) {
        ItemStack s = e.getMerchantOffer().getResult();
        fire(e.getEntity(), TriggerType.TRADE, itemOrNull(s), s.getCount(), null, null);
    }

    // ---------------------------------------------------------------- nature

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onFish(ItemFishedEvent e) {
        for (ItemStack s : e.getDrops()) if (!s.isEmpty()) fire(e.getEntity(), TriggerType.FISH, id(s.getItem()), 1, null, null, e.getHookEntity().position());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBreed(BabyEntitySpawnEvent e) {
        if (e.getCausedByPlayer() != null && e.getChild() != null)
            fire(e.getCausedByPlayer(), TriggerType.BREED, id(e.getChild()), 1, null, null, e.getParentA().position()); // the child is not placed yet
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onTame(AnimalTameEvent e) {
        fire(e.getTamer(), TriggerType.TAME, id(e.getAnimal()), 1, null, null, e.getAnimal().position());
    }

    // ---------------------------------------------------------------- movement + world

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onFall(LivingFallEvent e) {
        // ponytail: under 1 block is a step down, not a fall
        if (e.getEntity() instanceof Player p && e.getDistance() >= 1) fire(p, TriggerType.FALL, null, e.getDistance(), null, null);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onWake(PlayerWakeUpEvent e) {
        if (!e.wakeImmediately()) fire(e.getEntity(), TriggerType.SLEEP, null, 1, null, null);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDimension(PlayerEvent.PlayerChangedDimensionEvent e) {
        fire(e.getEntity(), TriggerType.ENTER_DIMENSION, e.getTo().location(), 1, null, null);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onAdvancement(AdvancementEvent.AdvancementEarnEvent e) {
        ResourceLocation a = e.getAdvancement().id();
        if (!a.getPath().startsWith("recipes/")) fire(e.getEntity(), TriggerType.ADVANCEMENT, a, 1, null, null);
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post e) {
        if (!LATER.isEmpty()) {
            List<Runnable> now = List.copyOf(LATER);
            LATER.clear();
            now.forEach(Runnable::run);
        }
        if (e.getServer().getTickCount() % POLL_TICKS != 0) return;
        for (ServerPlayer p : e.getServer().getPlayerList().getPlayers()) poll(p);
    }

    /** Once a second per player: flush batched stats + xp, then biome and structure entry. Tests call it directly (fakes never tick). */
    static void poll(ServerPlayer p) {
        if (!Triggers.allowed(p)) return;
        State st = state(p);
        Object2IntOpenHashMap<TriggerType> moves = null;
        for (Object2IntMap.Entry<ResourceLocation> en : st.stats.object2IntEntrySet()) {
            ResourceLocation r = en.getKey();
            int v = en.getIntValue();
            fire(p, TriggerType.STAT_INCREMENT, r, v, null, null);
            TriggerType t = MOVES.get(r);
            if (t != null) (moves == null ? moves = new Object2IntOpenHashMap<>() : moves).addTo(t, v);
        }
        st.stats.clear();
        if (moves != null) for (Object2IntMap.Entry<TriggerType> en : moves.object2IntEntrySet()) {
            TriggerType t = en.getKey();
            double amount = t == TriggerType.JUMP ? en.getIntValue() : en.getIntValue() / 100.0;
            fire(p, t, t == TriggerType.RIDE_DISTANCE ? st.mount : null, amount, null, null);
        }
        if (st.xp > 0) fire(p, TriggerType.XP_PICKUP, null, st.xp, null, null);
        st.xp = 0;

        ServerLevel level = p.serverLevel();
        BlockPos pos = p.blockPosition();
        ResourceLocation biome = level.getBiome(pos).unwrapKey().map(ResourceKey::location).orElse(null);
        long tick = p.server.getTickCount();
        if (st.biome != null && biome != null && !biome.equals(st.biome) && away(st.biomeSeen, biome, tick))
            fire(p, TriggerType.ENTER_BIOME, biome, 1, null, null);
        st.biome = biome;
        if (biome != null) st.biomeSeen.put(biome, tick);

        Set<ResourceLocation> now = new HashSet<>();
        var reg = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        for (Structure s : level.structureManager().getAllStructuresAt(pos).keySet())
            if (level.structureManager().getStructureWithPieceAt(pos, s).isValid()) now.add(reg.getKey(s));
        if (st.structures != null) for (ResourceLocation r : now)
            if (!st.structures.contains(r) && away(st.structureSeen, r, tick)) fire(p, TriggerType.ENTER_STRUCTURE, r, 1, null, null);
        st.structures = now;
        for (ResourceLocation r : now) st.structureSeen.put(r, tick);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        STATES.remove(e.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onStopped(ServerStoppedEvent e) {
        STATES.clear();
        LATER.clear();
        Triggers.TEST_PLAYERS.clear();
    }
}
