package com.habits.perk.building;

import com.habits.core.HabitsApi;
import com.habits.perk.Later;
import com.habits.perk.mining.Work;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.Painting;
import net.minecraft.world.entity.decoration.PaintingVariant;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WeatheringCopper;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.level.Level;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/** Right-click tools: tape and fill wand, painter, chisel, weathering, patina, mud, lights, the builder's stick, doors,
 * seats, glue gun, pools and drying, grader, supply link, mirror plane, gallery and poser. */
public final class Using {
    private Using() {}

    /** Paper corners per player: {a, b} (b null until the second click). */
    record Box(ResourceKey<Level> dim, BlockPos a, BlockPos b) {}
    static final Map<UUID, Box> BOXES = Bld.playerMap();
    /** Builder's stick: selected property name per player. */
    static final Map<UUID, String> STICK = Bld.playerMap();

    @SubscribeEvent
    public static void onUseBlock(PlayerInteractEvent.RightClickBlock e) {
        if (e.getHand() != InteractionHand.MAIN_HAND || Work.busy || !(e.getEntity() instanceof ServerPlayer p) || !Bld.allowed(p)) return;
        ServerLevel level = p.serverLevel();
        BlockPos pos = e.getPos();
        BlockState s = level.getBlockState(pos);
        ItemStack held = e.getItemStack();
        boolean sneak = p.isShiftKeyDown();
        HabitsApi.PerkHit h;
        boolean done = false;
        if (held.is(Items.PAPER) && (Bld.hit(p, "measuring_tape") != null || Bld.hit(p, "fill_wand") != null)) done = tape(p, level, pos);
        else if (sneak && held.is(Items.COMPASS) && (h = Bld.hit(p, "supply_line")) != null && level.getBlockEntity(pos) instanceof Container) {
            p.getPersistentData().putLong(Placing.SUPPLY, pos.asLong());
            p.getPersistentData().putString(Placing.SUPPLY_DIM, level.dimension().location().toString());
            Bld.say(p, "supply_linked");
            done = true;
        } else if (sneak && held.is(Items.AMETHYST_SHARD) && Bld.hit(p, "symmetry") != null) {
            Direction.Axis ax = p.getDirection().getClockWise().getAxis();
            Placing.PLANES.put(p.getUUID(), new Placing.Plane(level.dimension(), ax == Direction.Axis.X ? 0 : 1, ax == Direction.Axis.X ? pos.getX() : pos.getZ()));
            Bld.say(p, "mirror_set", ax.getName());
            done = true;
        } else if (sneak && held.getItem() instanceof DyeItem dye && (h = Bld.hit(p, "painter")) != null) done = paint(p, level, pos, s, dye.getDyeColor(), held, h);
        else if (sneak && held.is(ItemTags.PICKAXES) && (h = Bld.hit(p, "chisel")) != null) done = chisel(p, level, pos, s, held, h);
        else if (sneak && (held.is(Items.VINE) || held.is(Items.MOSS_BLOCK) || held.is(Items.FLINT)) && (h = Bld.hit(p, "weathering")) != null)
            done = convert(p, level, pos, s, h, b -> aged(b, held.is(Items.FLINT) ? "cracked_" : "mossy_"), held);
        else if (sneak && waterBottle(held) && s.getBlock() instanceof WeatheringCopper && (h = Bld.hit(p, "patina")) != null)
            done = convert(p, level, pos, s, h, b -> WeatheringCopper.getNext(b).orElse(null), held);
        else if (sneak && waterBottle(held) && s.is(BlockTags.CONVERTABLE_TO_MUD) && (h = Bld.hit(p, "mud_mixer")) != null)
            done = convert(p, level, pos, s, h, b -> Blocks.MUD, held);
        else if (sneak && held.is(Items.GLOWSTONE_DUST) && (h = Bld.hit(p, "hidden_light")) != null) done = hiddenLight(p, level, pos.relative(e.getFace()), held, h);
        else if (held.is(Items.STICK) && (h = Bld.hit(p, "builders_stick")) != null) done = stick(p, level, pos, s, sneak, h);
        else if (sneak && held.is(Items.SLIME_BALL) && s.is(Blocks.PISTON) && !s.getValue(PistonBaseBlock.EXTENDED) && (h = Bld.hit(p, "glue_gun")) != null) {
            done = Bld.changeAs(p, pos, Blocks.STICKY_PISTON.withPropertiesOf(s));
            if (done && !Bld.roll(p, h.value()) && !p.getAbilities().instabuild) held.shrink(1);
        } else if (sneak && held.is(Items.SHEARS) && s.is(Blocks.STICKY_PISTON) && !s.getValue(PistonBaseBlock.EXTENDED) && Bld.hit(p, "glue_gun") != null) {
            done = Bld.changeAs(p, pos, Blocks.PISTON.withPropertiesOf(s));
            if (done) { Bld.give(p, new ItemStack(Items.SLIME_BALL)); held.hurtAndBreak(1, p, EquipmentSlot.MAINHAND); }
        } else if (!sneak && held.isEmpty() && (h = Bld.hit(p, "take_a_seat")) != null && seatable(s) && level.getBlockState(pos.above()).isAir())
            done = Moving.sit(p, level, pos, s);
        else if (!sneak && held.isEmpty() && (s.is(Blocks.IRON_DOOR) || s.is(Blocks.IRON_TRAPDOOR)) && (h = Bld.hit(p, "iron_hands")) != null)
            done = ironHands(p, level, pos, s, h);
        else if (sneak && held.is(Items.WATER_BUCKET) && (h = Bld.hit(p, "pool_builder")) != null) {
            BlockPos at = s.canBeReplaced() ? pos : pos.relative(e.getFace());
            HabitsApi.PerkHit ph = h;
            Later.run(level, 0, () -> pool(p, level, at, ph));
        } else if (sneak && held.is(ItemTags.SHOVELS) && e.getFace() == Direction.UP && (h = Bld.hit(p, "grader")) != null) done = grade(p, level, pos, h);
        if (!sneak && (s.getBlock() instanceof DoorBlock || s.getBlock() instanceof FenceGateBlock) && !s.is(Blocks.IRON_DOOR) && (h = Bld.hit(p, "double_doors")) != null) {
            HabitsApi.PerkHit dh = h;
            Later.run(level, 0, () -> doors(p, level, pos, dh));
        }
        if (done) {
            e.setCanceled(true);
            e.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    @SubscribeEvent
    public static void onUseItem(PlayerInteractEvent.RightClickItem e) {
        if (e.getHand() != InteractionHand.MAIN_HAND || !(e.getEntity() instanceof ServerPlayer p) || !Bld.allowed(p) || !p.isShiftKeyDown()) return;
        ItemStack held = e.getItemStack();
        HabitsApi.PerkHit h;
        if (held.is(Items.AMETHYST_SHARD) && Placing.PLANES.remove(p.getUUID()) != null) Bld.say(p, "mirror_cleared");
        else if (held.is(Items.BUCKET) && (h = Bld.hit(p, "dry_up")) != null) {
            HitResult r = p.level().clip(new ClipContext(p.getEyePosition(), p.getEyePosition().add(p.getLookAngle().scale(p.blockInteractionRange())),
                    ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, p));
            if (r instanceof BlockHitResult b && p.level().getFluidState(b.getBlockPos()).is(net.minecraft.tags.FluidTags.WATER)) {
                BlockPos at = b.getBlockPos();
                HabitsApi.PerkHit dh = h;
                Later.run(p.serverLevel(), 0, () -> dryUp(p, p.serverLevel(), at, dh)); // after vanilla scooped the source
            }
        } else if (held.getItem() instanceof BlockItem bi && (h = Bld.hit(p, "fill_wand")) != null) {
            if (fillBox(p, p.serverLevel(), bi, h)) {
                e.setCanceled(true);
                e.setCancellationResult(InteractionResult.SUCCESS);
            }
        }
    }

    @SubscribeEvent
    public static void onUseEntity(PlayerInteractEvent.EntityInteract e) {
        if (e.getHand() != InteractionHand.MAIN_HAND || !(e.getEntity() instanceof ServerPlayer p) || !Bld.allowed(p) || !p.isShiftKeyDown()) return;
        HabitsApi.PerkHit h;
        if (e.getTarget() instanceof Painting painting && e.getItemStack().isEmpty() && (h = Bld.hit(p, "gallery")) != null && gallery(p, painting, h)) {
            e.setCanceled(true);
            e.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    @SubscribeEvent
    public static void onUseEntityAt(PlayerInteractEvent.EntityInteractSpecific e) {
        if (e.getHand() != InteractionHand.MAIN_HAND || !(e.getEntity() instanceof ServerPlayer p) || !Bld.allowed(p) || !p.isShiftKeyDown()) return;
        HabitsApi.PerkHit h;
        if (e.getTarget() instanceof ArmorStand stand && e.getItemStack().is(Items.STICK) && (h = Bld.hit(p, "poser")) != null) {
            pose(stand, (int) h.value());
            e.setCanceled(true);
            e.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    // ---------------------------------------------------------------- tape and fill wand

    static boolean tape(ServerPlayer p, ServerLevel level, BlockPos pos) {
        Box b = BOXES.get(p.getUUID());
        if (b == null || b.b() != null || b.dim() != level.dimension()) {
            BOXES.put(p.getUUID(), new Box(level.dimension(), pos.immutable(), null));
            Bld.say(p, "tape_first", pos.getX(), pos.getY(), pos.getZ());
            return true;
        }
        HabitsApi.PerkHit h = Bld.hit(p, "measuring_tape");
        double range = h == null ? Double.MAX_VALUE : h.value(); // fill wand alone: the box size limits it
        if (Math.sqrt(b.a().distSqr(pos)) > range) {
            Bld.say(p, "tape_far", (int) Math.min(range, Integer.MAX_VALUE));
            return true;
        }
        BOXES.put(p.getUUID(), new Box(level.dimension(), b.a(), pos.immutable()));
        int dx = Math.abs(pos.getX() - b.a().getX()) + 1, dy = Math.abs(pos.getY() - b.a().getY()) + 1, dz = Math.abs(pos.getZ() - b.a().getZ()) + 1;
        Bld.say(p, "tape", dx, dy, dz, String.format("%.1f", Math.sqrt(b.a().distSqr(pos))));
        return true;
    }

    static boolean fillBox(ServerPlayer p, ServerLevel level, BlockItem bi, HabitsApi.PerkHit h) {
        Box b = BOXES.get(p.getUUID());
        if (b == null || b.b() == null || b.dim() != level.dimension()) return false;
        List<BlockPos> spots = new ArrayList<>();
        for (BlockPos q : BlockPos.betweenClosed(b.a(), b.b()))
            if (level.isLoaded(q) && level.getBlockState(q).isAir()) spots.add(q.immutable());
        if (spots.size() > h.value()) {
            Bld.say(p, "fill_big", spots.size(), (int) h.value());
            return true;
        }
        spots.sort(Comparator.comparingInt(BlockPos::getY));
        BlockState s = bi.getBlock().defaultBlockState();
        List<Runnable> steps = new ArrayList<>();
        for (BlockPos q : spots) steps.add(() -> {
            if (Bld.allowed(p) && Bld.has(p, bi, 1) && Bld.placeAs(p, q, s)) Bld.take(p, bi);
        });
        Bld.queue(p, steps, (int) Bld.lerp(h, "per_tick", 16, 16));
        BOXES.remove(p.getUUID());
        Bld.say(p, "fill", spots.size());
        return true;
    }

    // ---------------------------------------------------------------- recolor, chisel, age

    private static final List<DyeColor> LONGEST_FIRST = Arrays.stream(DyeColor.values())
            .sorted(Comparator.comparingInt((DyeColor c) -> c.getName().length()).reversed()).toList();

    /** The same block in another color, or null: white_wool -> red_wool, glass -> red_stained_glass. Block entities
     * (beds, banners, shulker boxes) are left alone. */
    static Block recolor(Block b, DyeColor to) {
        if (b instanceof EntityBlock) return null;
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(b);
        String path = id.getPath(), rest = null;
        for (DyeColor c : LONGEST_FIRST) if (path.startsWith(c.getName() + "_")) { rest = path.substring(c.getName().length() + 1); break; }
        if (rest == null) rest = switch (path) {
            case "glass" -> "stained_glass";
            case "glass_pane" -> "stained_glass_pane";
            case "terracotta", "candle" -> path;
            default -> null;
        };
        if (rest == null) return null;
        Block nb = BuiltInRegistries.BLOCK.get(id.withPath(to.getName() + "_" + rest));
        return nb == Blocks.AIR || nb == b ? null : nb;
    }

    static boolean paint(ServerPlayer p, ServerLevel level, BlockPos pos, BlockState s, DyeColor color, ItemStack dye, HabitsApi.PerkHit h) {
        if (recolor(s.getBlock(), color) == null) return false;
        int perDye = Math.max(1, (int) Bld.lerp(h, "per_dye", 4, 16));
        int afford = p.getAbilities().instabuild ? Integer.MAX_VALUE : p.getInventory().countItem(dye.getItem()) * perDye;
        List<BlockPos> spots = Bld.flood(level, pos, Math.min((int) h.value(), afford) - 1, Direction.values(), q -> level.getBlockState(q).is(s.getBlock()));
        int n = 0;
        for (BlockPos q : spots) {
            BlockState at = level.getBlockState(q);
            if (Bld.changeAs(p, q, recolor(at.getBlock(), color).withPropertiesOf(at))) n++;
        }
        for (int i = 0; i < (n + perDye - 1) / perDye; i++) Bld.take(p, dye.getItem());
        return n > 0;
    }

    /** The next full block a stonecutter makes from this block, or null. */
    static Block chiselled(ServerLevel level, Block b) {
        ItemStack in = new ItemStack(b.asItem());
        if (in.isEmpty()) return null;
        List<Block> out = new ArrayList<>();
        for (var r : level.getRecipeManager().getAllRecipesFor(RecipeType.STONECUTTING)) {
            ItemStack res = r.value().getResultItem(level.registryAccess());
            if (res.getCount() != 1 || !(res.getItem() instanceof BlockItem rb) || rb.getBlock() == b) continue;
            if (!r.value().getIngredients().get(0).test(in)) continue;
            BlockState d = rb.getBlock().defaultBlockState();
            if (d.getProperties().isEmpty() || d.hasProperty(BlockStateProperties.AXIS)) out.add(rb.getBlock());
        }
        out.sort(Comparator.comparing(x -> BuiltInRegistries.BLOCK.getKey(x).toString()));
        return out.isEmpty() ? null : out.get(0);
    }

    static boolean chisel(ServerPlayer p, ServerLevel level, BlockPos pos, BlockState s, ItemStack pick, HabitsApi.PerkHit h) {
        Block to = chiselled(level, s.getBlock());
        if (to == null) return false;
        List<BlockPos> spots = Bld.flood(level, pos, (int) h.value() - 1, Direction.values(), q -> level.getBlockState(q).is(s.getBlock()));
        int n = 0;
        for (BlockPos q : spots) if (Bld.changeAs(p, q, to.withPropertiesOf(level.getBlockState(q)))) n++;
        if (n > 0) {
            pick.hurtAndBreak(1, p, EquipmentSlot.MAINHAND);
            level.playSound(null, pos, SoundEvents.UI_STONECUTTER_TAKE_RESULT, SoundSource.BLOCKS, 1, 1);
        }
        return n > 0;
    }

    /** mossy_ or cracked_ variant by name: stone_bricks -> mossy_stone_bricks, cobblestone_wall -> mossy_cobblestone_wall. */
    static Block aged(Block b, String prefix) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(b);
        if (id.getPath().startsWith(prefix)) return null;
        Block nb = BuiltInRegistries.BLOCK.get(id.withPath(prefix + id.getPath()));
        return nb == Blocks.AIR ? null : nb;
    }

    static boolean waterBottle(ItemStack s) {
        PotionContents c = s.get(net.minecraft.core.component.DataComponents.POTION_CONTENTS);
        return s.is(Items.POTION) && c != null && c.is(Potions.WATER);
    }

    /** Turn the clicked block and up to value - 1 connected same blocks into {@code to(block)}; uses one of the held item
     * (a water bottle leaves a glass bottle). */
    static boolean convert(ServerPlayer p, ServerLevel level, BlockPos pos, BlockState s, HabitsApi.PerkHit h, Function<Block, Block> to, ItemStack held) {
        if (to.apply(s.getBlock()) == null) return false;
        List<BlockPos> spots = Bld.flood(level, pos, (int) h.value() - 1, Direction.values(), q -> level.getBlockState(q).is(s.getBlock()));
        int n = 0;
        for (BlockPos q : spots) {
            BlockState at = level.getBlockState(q);
            Block nb = to.apply(at.getBlock());
            if (nb != null && Bld.changeAs(p, q, nb.withPropertiesOf(at))) n++;
        }
        if (n > 0 && !p.getAbilities().instabuild) {
            boolean bottle = held.is(Items.POTION);
            held.shrink(1);
            if (bottle) Bld.give(p, new ItemStack(Items.GLASS_BOTTLE));
        }
        if (n > 0) level.playSound(null, pos, SoundEvents.BRUSH_GENERIC, SoundSource.BLOCKS, 1, 1);
        return n > 0;
    }

    static boolean hiddenLight(ServerPlayer p, ServerLevel level, BlockPos at, ItemStack dust, HabitsApi.PerkHit h) {
        BlockState there = level.getBlockState(at);
        if (there.is(Blocks.LIGHT)) { // a second click takes it back
            if (Bld.changeAs(p, at, Blocks.AIR.defaultBlockState()) && !p.getAbilities().instabuild) Bld.give(p, new ItemStack(Items.GLOWSTONE_DUST));
            return true;
        }
        if (!there.isAir()) return false;
        int lv = Math.max(1, Math.min(15, (int) h.value()));
        if (!Bld.placeAs(p, at, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, lv))) return false;
        if (!p.getAbilities().instabuild) dust.shrink(1);
        level.playSound(null, at, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 1, 1.5f);
        return true;
    }

    // ---------------------------------------------------------------- builder's stick

    static final List<String> TIER1 = List.of("north", "east", "south", "west", "up", "down", "shape");
    static final List<String> TIER2 = List.of("half", "hinge", "open", "attachment", "face", "in_wall", "hanging");
    static final List<String> TIER3 = List.of("facing", "axis", "rotation", "orientation", "inverted");

    static List<Property<?>> editable(BlockState s, HabitsApi.PerkHit h) {
        List<Property<?>> out = new ArrayList<>();
        // two-block and linked blocks would split or dupe
        if (s.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) || s.hasProperty(BlockStateProperties.BED_PART)
                || s.hasProperty(BlockStateProperties.EXTENDED) || s.hasProperty(BlockStateProperties.CHEST_TYPE)) return out;
        int lv = h.level();
        for (Property<?> prop : s.getProperties()) {
            String n = prop.getName();
            if (TIER1.contains(n) || TIER2.contains(n) && lv >= (int) Bld.lerp(h, "tier2_level", 4, 4)
                    || TIER3.contains(n) && lv >= (int) Bld.lerp(h, "tier3_level", 7, 7)) out.add(prop);
        }
        return out;
    }

    static boolean stick(ServerPlayer p, ServerLevel level, BlockPos pos, BlockState s, boolean select, HabitsApi.PerkHit h) {
        List<Property<?>> props = editable(s, h);
        if (props.isEmpty()) return false;
        String cur = STICK.get(p.getUUID());
        int i = 0;
        for (int k = 0; k < props.size(); k++) if (props.get(k).getName().equals(cur)) i = k;
        if (select) {
            Property<?> next = props.get((i + 1) % props.size());
            STICK.put(p.getUUID(), next.getName());
            Bld.say(p, "stick_select", next.getName());
            return true;
        }
        Property<?> prop = props.get(i);
        BlockState ns = s.cycle(prop);
        if (!Bld.set(p, level, pos, ns, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE)) return true;
        Bld.say(p, "stick_set", prop.getName(), String.valueOf(ns.getValue(prop)));
        return true;
    }

    // ---------------------------------------------------------------- doors and seats

    static boolean seatable(BlockState s) {
        return s.getBlock() instanceof StairBlock && s.getValue(StairBlock.HALF) == Half.BOTTOM
                || s.getBlock() instanceof SlabBlock && s.getValue(SlabBlock.TYPE) == SlabType.BOTTOM;
    }

    static boolean ironHands(ServerPlayer p, ServerLevel level, BlockPos pos, BlockState s, HabitsApi.PerkHit h) {
        if (s.getBlock() instanceof DoorBlock door) {
            door.setOpen(p, level, s, pos, !s.getValue(DoorBlock.OPEN));
        } else {
            if (h.level() < (int) Bld.lerp(h, "trapdoor_level", 5, 5)) return false;
            boolean open = !s.getValue(TrapDoorBlock.OPEN);
            level.setBlock(pos, s.setValue(TrapDoorBlock.OPEN, open), 2);
            level.playSound(null, pos, open ? SoundEvents.IRON_TRAPDOOR_OPEN : SoundEvents.IRON_TRAPDOOR_CLOSE, SoundSource.BLOCKS, 1, 1);
        }
        HabitsApi.PerkHit dh = Bld.hit(p, "double_doors");
        if (dh != null) doors(p, level, pos, dh);
        return true;
    }

    /** Open or close the matching doors and gates in a row on both sides, like the one just used. */
    static void doors(ServerPlayer p, ServerLevel level, BlockPos pos, HabitsApi.PerkHit h) {
        BlockState s = level.getBlockState(pos);
        BooleanPropertyHolder open = BooleanPropertyHolder.of(s);
        if (open == null) return;
        boolean to = s.getValue(open.prop());
        Direction facing = s.getValue(BlockStateProperties.HORIZONTAL_FACING);
        BlockPos base = s.getBlock() instanceof DoorBlock && s.getValue(DoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER ? pos.below() : pos;
        for (Direction side : new Direction[]{facing.getClockWise(), facing.getCounterClockWise()})
            for (int i = 1; i <= (int) h.value(); i++) {
                BlockPos q = base.relative(side, i);
                BlockState at = level.getBlockState(q);
                if (at.getBlock() != s.getBlock()) break;
                if (at.getValue(open.prop()) == to) continue;
                if (at.getBlock() instanceof DoorBlock d) d.setOpen(p, level, at, q, to);
                else level.setBlock(q, at.setValue(open.prop(), to), 10);
            }
    }

    record BooleanPropertyHolder(net.minecraft.world.level.block.state.properties.BooleanProperty prop) {
        static BooleanPropertyHolder of(BlockState s) {
            return (s.getBlock() instanceof DoorBlock || s.getBlock() instanceof FenceGateBlock) && s.hasProperty(BlockStateProperties.OPEN)
                    && s.hasProperty(BlockStateProperties.HORIZONTAL_FACING) ? new BooleanPropertyHolder(BlockStateProperties.OPEN) : null;
        }
    }

    // ---------------------------------------------------------------- water, grading

    static void pool(ServerPlayer p, ServerLevel level, BlockPos at, HabitsApi.PerkHit h) {
        if (!level.getFluidState(at).is(Fluids.WATER) || !level.getFluidState(at).isSource()) return;
        int max = (int) h.value();
        List<BlockPos> spots = Bld.flood(level, at, max + 1, Bld.FLAT, q -> q.equals(at)
                || level.getBlockState(q).isAir() && !level.getBlockState(q.below()).isAir());
        spots.remove(at);
        if (spots.size() > max) { Bld.say(p, "too_open"); return; }
        for (BlockPos q : spots) Bld.placeAs(p, q, Blocks.WATER.defaultBlockState());
    }

    static void dryUp(ServerPlayer p, ServerLevel level, BlockPos at, HabitsApi.PerkHit h) {
        List<BlockPos> spots = Bld.flood(level, at, (int) h.value(), Direction.values(), q -> q.equals(at)
                || level.getBlockState(q).getBlock() instanceof LiquidBlock && level.getFluidState(q).is(net.minecraft.tags.FluidTags.WATER));
        int n = 0;
        for (BlockPos q : spots) {
            if (n >= (int) h.value()) break;
            if (level.getBlockState(q).getBlock() instanceof LiquidBlock && Bld.changeAs(p, q, Blocks.AIR.defaultBlockState())) n++;
        }
    }

    static boolean grade(ServerPlayer p, ServerLevel level, BlockPos pos, HabitsApi.PerkHit h) {
        int r = (int) h.value(), height = (int) Math.round(Bld.lerp(h, "height", 3, 6));
        List<Runnable> steps = new ArrayList<>();
        for (int y = pos.getY() + height; y > pos.getY(); y--) // top down, so nothing above falls into the cut
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos q = new BlockPos(pos.getX() + dx, y, pos.getZ() + dz);
                    steps.add(() -> {
                        BlockState at = level.getBlockState(q);
                        if (Bld.allowed(p) && !at.isAir() && at.is(BlockTags.MINEABLE_WITH_SHOVEL) && !at.hasBlockEntity()
                                && p.getMainHandItem().is(ItemTags.SHOVELS)) Bld.breakAs(p, q);
                    });
                }
        Bld.queue(p, steps, (int) Bld.lerp(h, "per_tick", 12, 12));
        return true;
    }

    // ---------------------------------------------------------------- gallery, poser

    static boolean gallery(ServerPlayer p, Painting painting, HabitsApi.PerkHit h) {
        var reg = p.level().registryAccess().registryOrThrow(Registries.PAINTING_VARIANT);
        List<Holder<PaintingVariant>> all = new ArrayList<>();
        reg.getTagOrEmpty(net.minecraft.tags.PaintingVariantTags.PLACEABLE).forEach(all::add);
        if (all.isEmpty()) return false;
        all.sort(Comparator.comparing(v -> v.unwrapKey().map(k -> k.location().toString()).orElse("")));
        Holder<PaintingVariant> cur = painting.getVariant();
        boolean anySize = h.level() >= (int) Bld.lerp(h, "any_size_level", 5, 5);
        int start = Math.max(0, all.indexOf(cur));
        for (int i = 1; i < all.size(); i++) {
            Holder<PaintingVariant> v = all.get((start + i) % all.size());
            if (!anySize && (v.value().width() != cur.value().width() || v.value().height() != cur.value().height())) continue;
            Painting next = new Painting(p.level(), painting.getPos(), painting.getDirection(), v);
            if (!next.survives() && !(v.value().width() == cur.value().width() && v.value().height() == cur.value().height())) continue;
            painting.discard();
            p.level().addFreshEntity(next);
            next.playPlacementSound();
            return true;
        }
        return false;
    }

    private static final float[][] POSES = {
            // head, body, left arm, right arm, left leg, right leg (x, y, z each)
            {0, 0, 0, 0, 0, 0, -10, 0, -10, -15, 0, 10, -1, 0, -1, 1, 0, 1},
            {0, 0, 0, 0, 0, 0, -110, 0, 0, -110, 0, 0, 0, 0, 0, 0, 0, 0},
            {-15, 0, 0, 0, 0, 0, 0, 0, -10, -120, 0, 10, 0, 0, 0, 0, 0, 0},
            {0, 0, 0, 0, 0, 0, -30, 0, 15, 30, 0, -15, 30, 0, 0, -30, 0, 0},
            {20, 0, 0, 10, 0, 0, -60, 20, 0, -60, -20, 0, 0, 0, 0, 0, 0, 0},
            {0, 0, 0, 0, 0, 0, 0, 0, -120, 0, 0, 120, 0, 0, -10, 0, 0, 10},
            {-30, 20, 0, 0, 0, 0, -90, 30, 0, 20, 0, 0, -20, 0, 0, 20, 0, 0},
            {10, 0, 0, 0, 0, 0, -150, 0, 0, 0, 0, 30, 0, 0, 0, 0, 0, 0},
            {0, -30, 0, 0, 15, 0, -45, 0, -45, -45, 0, 45, -15, 0, 0, 15, 0, 0},
            {25, 0, 0, 15, 0, 0, -20, 0, 0, -20, 0, 0, -90, 0, 0, -90, 0, 0},
    };

    static void pose(ArmorStand stand, int count) {
        int n = Math.max(1, Math.min(POSES.length, count));
        int i = (stand.getPersistentData().getInt("habits_pose") + 1) % n;
        stand.getPersistentData().putInt("habits_pose", i);
        float[] v = POSES[i];
        stand.setShowArms(true);
        stand.setHeadPose(new net.minecraft.core.Rotations(v[0], v[1], v[2]));
        stand.setBodyPose(new net.minecraft.core.Rotations(v[3], v[4], v[5]));
        stand.setLeftArmPose(new net.minecraft.core.Rotations(v[6], v[7], v[8]));
        stand.setRightArmPose(new net.minecraft.core.Rotations(v[9], v[10], v[11]));
        stand.setLeftLegPose(new net.minecraft.core.Rotations(v[12], v[13], v[14]));
        stand.setRightLegPose(new net.minecraft.core.Rotations(v[15], v[16], v[17]));
    }

}
