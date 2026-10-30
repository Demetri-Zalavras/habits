package com.habits.perk.building;

import com.habits.Habits;
import com.habits.core.Category;
import com.habits.core.CoreRegistries;
import com.habits.core.HabitsApi;
import com.habits.core.SkillDef;
import com.habits.core.SkillRegistry;
import com.habits.core.Specs;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntSupplier;

/** Sculptor statue ({@code habits:statue}): a Building player's placed statue blesses players around it. One active
 * statue per owner ({@link Data}); the owner's newest placed or re-awakened one wins. */
public final class Statue {
    private Statue() {}

    enum Blessing {
        VITALITY(MobEffects.REGENERATION), FORTUNE(MobEffects.LUCK), LABOR(MobEffects.DIG_SPEED);
        final Holder<MobEffect> effect;
        Blessing(Holder<MobEffect> effect) { this.effect = effect; }
    }

    static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Habits.MODID);
    static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Habits.MODID);
    static final DeferredRegister<BlockEntityType<?>> BES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Habits.MODID);

    public static final DeferredBlock<StatueBlock> BLOCK = BLOCKS.register("statue", () -> new StatueBlock(BlockBehaviour.Properties.of()
            .mapColor(MapColor.STONE).strength(2f, 6f).sound(SoundType.STONE).requiresCorrectToolForDrops().noOcclusion()));
    public static final DeferredItem<BlockItem> ITEM = ITEMS.registerSimpleBlockItem(BLOCK);
    static final DeferredHolder<BlockEntityType<?>, BlockEntityType<StatueBlockEntity>> BE = BES.register("statue",
            () -> BlockEntityType.Builder.of(StatueBlockEntity::new, BLOCK.get()).build(null));

    static IntSupplier RADIUS = () -> 16, BOOSTED_RADIUS = () -> 24, BOOST_LEVELS = () -> 700, INTERVAL = () -> 40;

    static void defineConfig(ModConfigSpec.Builder b) {
        var r = b.comment("Sculptor statue: blessing radius in blocks").defineInRange("statue_radius", 16, 1, 128);
        var br = b.comment("Sculptor statue: radius once the owner's Building levels reach statue_boost_levels (blessing II)")
                .defineInRange("statue_boosted_radius", 24, 1, 128);
        var lv = b.comment("Sculptor statue: total Building skill levels for the stronger blessing").defineInRange("statue_boost_levels", 700, 0, 100000);
        var iv = b.comment("Sculptor statue: ticks between blessings (the effect lasts this plus 60)").defineInRange("statue_interval", 40, 1, 1200);
        RADIUS = r::get;
        BOOSTED_RADIUS = br::get;
        BOOST_LEVELS = lv::get;
        INTERVAL = iv::get;
    }

    static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BES.register(modBus);
        modBus.addListener(BuildCreativeModeTabContentsEvent.class, e -> {
            if (e.getTab() == CoreRegistries.TAB.get()) e.accept(ITEM.get());
        });
    }

    // ---------------------------------------------------------------- rules

    static boolean sculptor(Player p) {
        return Specs.of(p).picks().contains(Category.BUILDING);
    }

    static boolean boosted(Player p) {
        int sum = 0;
        for (var e : HabitsApi.collection(p).skills().entrySet()) {
            SkillDef d = SkillRegistry.get(e.getKey());
            if (d != null && d.category() == Category.BUILDING) sum += e.getValue().level();
        }
        return sum >= BOOST_LEVELS.getAsInt();
    }

    static int radius(StatueBlockEntity be) {
        return (be.boosted ? BOOSTED_RADIUS : RADIUS).getAsInt();
    }

    static boolean active(ServerLevel l, BlockPos pos, UUID owner) {
        return GlobalPos.of(l.dimension(), pos).equals(Data.of(l).active.get(owner));
    }

    static void activate(ServerLevel l, BlockPos pos, UUID owner) {
        Data d = Data.of(l);
        d.active.put(owner, GlobalPos.of(l.dimension(), pos));
        d.setDirty();
    }

    /** The statue at {@code pos} is gone: if it was its owner's active one, the owner has none until they awaken another. */
    static void removed(ServerLevel l, BlockPos pos, UUID owner) {
        Data d = Data.of(l);
        if (d.active.remove(owner, GlobalPos.of(l.dimension(), pos))) d.setDirty();
    }

    /** One blessing pulse to {@code players} (the level's players; tests pass fake ones). */
    static void pulse(ServerLevel l, BlockPos pos, StatueBlockEntity be, List<? extends Player> players) {
        if (be.owner == null || !active(l, pos, be.owner)) return;
        double r = radius(be);
        Vec3 c = Vec3.atCenterOf(pos);
        for (Player p : players) {
            if (!Bld.allowed(p) || p.distanceToSqr(c) > r * r) continue;
            if (p.getUUID().equals(be.owner)) be.setBoosted(boosted(p));
            p.addEffect(new MobEffectInstance(be.blessing().effect, INTERVAL.getAsInt() + 60, be.boosted ? 1 : 0, true, false, true));
        }
    }

    static void show(Player p, StatueBlockEntity be, String key) {
        Blessing b = be.blessing();
        Component effect = Component.translatable(b.effect.value().getDescriptionId());
        if (be.boosted) effect = effect.copy().append(" ").append(Component.translatable("enchantment.level.2"));
        Component line = Component.translatable("habits.building.statue.blessing",
                Component.translatable("habits.building.statue." + b.name().toLowerCase(java.util.Locale.ROOT)), effect, radius(be));
        Bld.say(p, key, line);
    }

    // ---------------------------------------------------------------- saved: owner -> active statue

    static final class Data extends SavedData {
        private static final Codec<Map<UUID, GlobalPos>> CODEC = Codec.unboundedMap(UUIDUtil.STRING_CODEC, GlobalPos.CODEC);
        final Map<UUID, GlobalPos> active = new HashMap<>();

        static Data of(ServerLevel l) {
            return l.getServer().overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(Data::new, (t, reg) -> {
                Data d = new Data();
                CODEC.parse(NbtOps.INSTANCE, t.get("active")).result().ifPresent(d.active::putAll);
                return d;
            }, null), "habits_statues");
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider reg) {
            CODEC.encodeStart(NbtOps.INSTANCE, active).ifSuccess(t -> tag.put("active", t));
            return tag;
        }
    }
}
