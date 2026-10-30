package com.habits.progression;

import com.habits.Habits;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

/** Progression registry entries: sigil and charm items, their components, the player attachment, the chest loot modifier. */
public final class ProgRegistries {
    private ProgRegistries() {}

    static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Habits.MODID);
    static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(Habits.MODID);
    static final DeferredRegister<AttachmentType<?>> ATTACHMENTS = DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, Habits.MODID);
    static final DeferredRegister<MapCodec<? extends IGlobalLootModifier>> LOOT_MODIFIERS =
            DeferredRegister.create(NeoForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS, Habits.MODID);

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<SigilData>> SIGIL_DATA = COMPONENTS.registerComponentType("sigil",
            b -> b.persistent(SigilData.CODEC).networkSynchronized(SigilData.STREAM_CODEC));
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<CharmData>> CHARM_DATA = COMPONENTS.registerComponentType("charm",
            b -> b.persistent(CharmData.CODEC).networkSynchronized(CharmData.STREAM_CODEC));

    /** {@code habits:sigil}. Always carries {@link #SIGIL_DATA}. */
    public static final DeferredItem<SigilItem> SIGIL = ITEMS.registerItem("sigil", SigilItem::new, new Item.Properties().fireResistant());
    /** {@code habits:charm}. Always carries {@link #CHARM_DATA}. */
    public static final DeferredItem<CharmItem> CHARM = ITEMS.registerItem("charm", CharmItem::new, new Item.Properties().stacksTo(1).fireResistant());

    /** Sockets and equipped charms. Saved, kept on death, synced to its own player. setData syncs. */
    public static final Supplier<AttachmentType<ProgressionData>> DATA = ATTACHMENTS.register("progression",
            () -> AttachmentType.builder(() -> ProgressionData.EMPTY)
                    .serialize(ProgressionData.CODEC)
                    .copyOnDeath()
                    .sync((holder, to) -> holder == to, ProgressionData.STREAM_CODEC)
                    .build());

    static final Supplier<MapCodec<ChestLoot>> CHEST_LOOT = LOOT_MODIFIERS.register("progression_chests", () -> ChestLoot.CODEC);

    static void register(IEventBus modBus) {
        ITEMS.register(modBus);
        COMPONENTS.register(modBus);
        ATTACHMENTS.register(modBus);
        LOOT_MODIFIERS.register(modBus);
    }
}
