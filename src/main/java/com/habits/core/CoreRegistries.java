package com.habits.core;

import com.habits.Habits;
import com.mojang.serialization.Codec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.ResourceLocation;
import java.util.List;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

/** Every core registry entry. Owner: core. Other packages read these, never add here (no sound registry: see CONTRACT). */
public final class CoreRegistries {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Habits.MODID);
    public static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(Habits.MODID);
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENTS = DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, Habits.MODID);
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Habits.MODID);

    /** {@code habits:card}. Always carries {@link #CARD_DATA}. */
    public static final DeferredItem<CardItem> CARD = ITEMS.registerItem("card", CardItem::new, new Item.Properties().fireResistant());
    /** {@code habits:habits_book}, the binder. */
    public static final DeferredItem<HabitsBookItem> BOOK = ITEMS.registerItem("habits_book", HabitsBookItem::new, new Item.Properties().stacksTo(1));
    /** {@code habits:wild_card}: right-click to pick an owned skill below Lv10; counts as one card for it (Wild Card skill). */
    public static final DeferredItem<WildCardItem> WILD_CARD = ITEMS.registerItem("wild_card", WildCardItem::new, new Item.Properties().fireResistant());

    /** {@code habits:respec_scroll}: read to pick specializations again (boss and elite drop). */
    public static final DeferredItem<RespecScrollItem> RESPEC_SCROLL = ITEMS.registerItem("respec_scroll", RespecScrollItem::new,
            new Item.Properties().stacksTo(16).fireResistant());

    /** {@code habits:class_book}: become a rarer class (resets every skill). Carries {@link #CLASS_BOOK}. */
    public static final DeferredItem<ClassBookItem> CLASS_BOOK_ITEM = ITEMS.registerItem("class_book", ClassBookItem::new,
            new Item.Properties().stacksTo(1).fireResistant());

    /** The class a class book teaches (a {@code class_passive} skill id). */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ResourceLocation>> CLASS_BOOK = COMPONENTS.registerComponentType("class_book",
            b -> b.persistent(ResourceLocation.CODEC).networkSynchronized(ResourceLocation.STREAM_CODEC));

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<CardData>> CARD_DATA = COMPONENTS.registerComponentType("card",
            b -> b.persistent(CardData.CODEC).networkSynchronized(CardData.STREAM_CODEC));

    /** The player's collection. Saved, kept on death, synced to its own player. setData syncs; never also syncData. */
    public static final Supplier<AttachmentType<HabitsCollection>> COLLECTION = ATTACHMENTS.register("collection",
            () -> AttachmentType.builder(() -> HabitsCollection.EMPTY)
                    .serialize(HabitsCollection.CODEC)
                    .copyOnDeath()
                    .sync((holder, to) -> holder == to, HabitsCollection.STREAM_CODEC)
                    .build());

    /** Unlocked skills the player has not hovered in the binder yet, oldest first (newest last). Saved, kept on death,
     * synced to its own player. */
    public static final Supplier<AttachmentType<List<ResourceLocation>>> UNSEEN = ATTACHMENTS.register("unseen",
            () -> AttachmentType.<List<ResourceLocation>>builder(() -> List.of())
                    .serialize(ResourceLocation.CODEC.listOf())
                    .copyOnDeath()
                    .sync((holder, to) -> holder == to, ResourceLocation.STREAM_CODEC.apply(ByteBufCodecs.list()))
                    .build());

    /** Won cards add themselves to the collection (feed row) instead of landing as items. Book toggle; read through
     * {@link HabitsApi#autoAdd}. Saved, kept on death, synced to its own player. */
    public static final Supplier<AttachmentType<Boolean>> AUTO_ADD = ATTACHMENTS.register("auto_add",
            () -> AttachmentType.builder(() -> true).serialize(Codec.BOOL).copyOnDeath()
                    .sync((holder, to) -> holder == to, ByteBufCodecs.BOOL)
                    .build());

    /** The retired "Maxed drops" book toggle (maxed cards are now always a miss). Unused; kept registered so saves that
     * have it load without an unknown-attachment error. */
    public static final Supplier<AttachmentType<Boolean>> DROP_MAXED = ATTACHMENTS.register("drop_maxed",
            () -> AttachmentType.builder(() -> false).serialize(Codec.BOOL).copyOnDeath()
                    .sync((holder, to) -> holder == to, ByteBufCodecs.BOOL)
                    .build());

    /** The player's combat style and profession ({@link Specs}). Saved, kept on death, synced to its own player. */
    public static final Supplier<AttachmentType<Specs>> SPECS = ATTACHMENTS.register("specs",
            () -> AttachmentType.builder(() -> Specs.NONE).serialize(Specs.CODEC).copyOnDeath()
                    .sync((holder, to) -> holder == to, Specs.STREAM_CODEC)
                    .build());

    /** The title shown before the player's name ({@link Titles}). Saved, kept on death, synced to everyone tracking
     * the player (their nameplate). */
    public static final Supplier<AttachmentType<java.util.Optional<ResourceLocation>>> SHOWN_TITLE = ATTACHMENTS.register("shown_title",
            () -> AttachmentType.<java.util.Optional<ResourceLocation>>builder(java.util.Optional::empty)
                    .serialize(ResourceLocation.CODEC.optionalFieldOf("id").codec())
                    .copyOnDeath()
                    .sync((holder, to) -> true, ByteBufCodecs.optional(ResourceLocation.STREAM_CODEC))
                    .build());

    /** Chosen skill fork options ({@link Forks}): "skill@level" -> option id. Saved, kept on death, synced to its own player. */
    public static final Supplier<AttachmentType<java.util.Map<String, String>>> FORKS = ATTACHMENTS.register("forks",
            () -> AttachmentType.<java.util.Map<String, String>>builder(() -> java.util.Map.of())
                    .serialize(Codec.unboundedMap(Codec.STRING, Codec.STRING))
                    .copyOnDeath()
                    .sync((holder, to) -> holder == to, ByteBufCodecs.<io.netty.buffer.ByteBuf, String, String, java.util.Map<String, String>>map(
                            java.util.HashMap::new, ByteBufCodecs.STRING_UTF8, ByteBufCodecs.STRING_UTF8))
                    .build());

    /** True once the player's card-era levels were reset for XP leveling ({@link SkillXp#resetOnce}). Saved, kept on death. */
    public static final Supplier<AttachmentType<Boolean>> XP_ERA = ATTACHMENTS.register("xp_era",
            () -> AttachmentType.builder(() -> false).serialize(Codec.BOOL).copyOnDeath().build());

    /** True once the player got their free book on first join. Saved, kept on death, server only. */
    public static final Supplier<AttachmentType<Boolean>> GOT_BOOK = ATTACHMENTS.register("got_book",
            () -> AttachmentType.builder(() -> false).serialize(Codec.BOOL).copyOnDeath().build());

    public static final Supplier<CreativeModeTab> TAB = TABS.register("habits", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.habits"))
            .icon(() -> new ItemStack(BOOK.get()))
            .displayItems((params, out) -> {
                out.accept(BOOK.get());
                out.accept(WILD_CARD.get());
                out.accept(RESPEC_SCROLL.get());
                SkillRegistry.all().stream().filter(SkillDef::classPassive).forEach(d -> out.accept(ClassBookItem.create(d.id())));
                SkillRegistry.all().forEach(def -> out.accept(CardItems.create(def)));
            })
            .build());

    static void register(IEventBus modBus) {
        ITEMS.register(modBus);
        COMPONENTS.register(modBus);
        ATTACHMENTS.register(modBus);
        TABS.register(modBus);
    }

    private CoreRegistries() {}
}
