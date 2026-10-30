package com.habits.perk.crafting;

import com.habits.Habits;
import com.mojang.datafixers.util.Either;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientTooltipComponentFactoriesEvent;
import net.neoforged.neoforge.client.event.RenderTooltipEvent;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Overgeared-style item window for rated gear: tier-colored name and border, a "Myth · Netherite Sword" subtitle,
 * aligned stat rows, the options, a flavor line and the maker, with gaps between sections. Built from the rating tag
 * each time, so it follows the language and current durability.
 */
@EventBusSubscriber(modid = Habits.MODID, value = Dist.CLIENT)
public final class RatingClient {
    private RatingClient() {}

    private static final int LABEL = 0xA8A8A8, VALUE = 0xFFFFFF, DIM = 0x6E6E6E;
    /** Lines onTooltip puts before the stats: name, subtitle, gap. */
    private static final int STATS_AT = 3;

    /** Everything but the stats: name, subtitle, gap | stats (onGather) | gap, options, gap, flavor, maker. */
    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent e) {
        ItemStack s = e.getItemStack();
        int plus = Enhance.level(s);
        if (plus > 0 && !e.getToolTip().isEmpty())
            e.getToolTip().set(0, Component.literal("+" + plus + " ").append(e.getToolTip().get(0)).withStyle(e.getToolTip().get(0).getStyle()));
        CompoundTag d = view(s);
        if (d == null || e.getToolTip().isEmpty()) return;
        // F3+H's "Durability: x / y" repeats the Durability row
        e.getToolTip().removeIf(c -> c.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents tc && tc.getKey().equals("item.durability"));
        Rating.Tier t = Rating.tier(s);
        List<Component> tip = e.getToolTip();
        if (t != Rating.Tier.NORMAL) { // Normal keeps the item's own name color (enchanted, rarity)
            MutableComponent name = tip.get(0).copy().withStyle(st -> st.withColor(t.color));
            tip.set(0, t.opts > 0 ? name.withStyle(ChatFormatting.BOLD) : name);
        }
        String kind = d.getString("kind");
        if (kind.equals("potion") || kind.equals("food")) {
            consumable(tip, s, d, t, kind);
            return;
        }

        List<Component> block = new ArrayList<>();
        MutableComponent sub = t.label().copy();
        if (s.has(DataComponents.CUSTOM_NAME)) // a forged name hides what the item is
            sub.append(Component.literal("  ·  ").withStyle(st -> st.withColor(DIM)))
                    .append(s.getItem().getDescription().copy().withStyle(st -> st.withColor(LABEL)));
        block.add(sub);
        block.add(Component.empty());
        var opts = d.getList("opts", Tag.TAG_STRING);
        if (!opts.isEmpty()) {
            block.add(Component.empty());
            for (Tag o : opts) {
                String k = "habits.rating.opt." + o.getAsString().toLowerCase();
                block.add(Component.literal("◆ ").append(Component.translatable(k)).withStyle(st -> st.withColor(t.color))
                        .append(Component.literal("  ").append(Component.translatable(k + ".desc")).withStyle(st -> st.withColor(LABEL))));
            }
        }
        java.util.UUID maker = Rating.makerId(s);
        if (maker != null && Rating.kind(s) == Rating.Kind.ARMOR) {
            int worn = 0;
            var me = net.minecraft.client.Minecraft.getInstance().player;
            if (me != null) for (var slot : net.minecraft.world.entity.EquipmentSlot.values())
                if (slot.isArmor() && maker.equals(Rating.makerId(me.getItemBySlot(slot)))) worn++;
            int n = worn;
            block.add(Component.empty());
            block.add(Component.translatable("habits.rating.set", d.getString("maker"), n).withStyle(st -> st.withColor(LABEL)));
            for (int need : new int[]{2, 4})
                block.add((need == 2 ? Component.translatable("habits.rating.set.2", num(CraftCfg.SET2.get()))
                        : Component.translatable("habits.rating.set.4", num(CraftCfg.SET4.get()), num(CraftCfg.SET4_DEALT.get())))
                        .withStyle(st -> st.withColor(n >= need ? t.color : DIM)));
        }
        if (t != Rating.Tier.NORMAL) {
            block.add(Component.empty());
            block.add(Component.translatable("habits.rating.flavor." + t.name().toLowerCase() + "." + d.getInt("flavor"))
                    .withStyle(st -> st.withColor(DIM)));
        }
        if (!d.getString("maker").isEmpty())
            block.add(Component.translatable("habits.rating.maker", d.getString("maker")).withStyle(st -> st.withColor(DIM)));
        tip.addAll(1, block);
    }

    /** Potions and dishes: subtitle, gap, (food: stat rows, buffs), the vanilla lines, then flavor and maker at the end. */
    private static void consumable(List<Component> tip, ItemStack s, CompoundTag d, Rating.Tier t, String kind) {
        List<Component> top = new ArrayList<>();
        top.add(t.label().copy());
        top.add(Component.empty());
        var food = s.get(DataComponents.FOOD);
        if (kind.equals("food") && food != null) for (var pe : food.effects()) {
            var inst = pe.effect();
            top.add(Component.literal("◆ ").append(inst.getEffect().value().getDisplayName()).withStyle(st -> st.withColor(t.color))
                    .append(Component.literal("  ").append(net.minecraft.world.effect.MobEffectUtil.formatDuration(inst, 1, 20))
                            .withStyle(st -> st.withColor(LABEL))));
        }
        tip.addAll(1, top);
        if (t == Rating.Tier.NORMAL) return;
        tip.add(Component.empty());
        tip.add(Component.translatable("habits.rating.flavor." + kind + "." + t.name().toLowerCase() + "." + d.getInt("flavor"))
                .withStyle(st -> st.withColor(DIM)));
        if (!d.getString("maker").isEmpty())
            tip.add(Component.translatable("habits.rating.maker." + kind, d.getString("maker")).withStyle(st -> st.withColor(DIM)));
    }

    /** Slots the aligned stat rows in after the name, subtitle and gap. */
    @SubscribeEvent
    public static void onGather(RenderTooltipEvent.GatherComponents e) {
        ItemStack s = e.getItemStack();
        CompoundTag d = view(s);
        if (d == null || e.getTooltipElements().size() < STATS_AT || d.getString("kind").equals("potion")) return;
        List<Row> rows = new ArrayList<>();
        var food = s.get(DataComponents.FOOD);
        if (d.getString("kind").equals("food")) {
            if (food == null) return;
            rows.add(new Row("nutrition", String.valueOf(food.nutrition())));
            rows.add(new Row("saturation", num(food.saturation())));
            e.getTooltipElements().add(STATS_AT, Either.right(new Stats(rows)));
            return;
        }
        ItemAttributeModifiers mods = Smith.base(s);
        if (Rating.kind(s) == Rating.Kind.ARMOR) {
            rows.add(new Row("defense", num(sum(mods, Attributes.ARMOR, 0))));
            double tough = sum(mods, Attributes.ARMOR_TOUGHNESS, 0);
            if (tough > 0) rows.add(new Row("toughness", num(tough)));
            double kb = sum(mods, Attributes.KNOCKBACK_RESISTANCE, 0);
            if (kb > 0) rows.add(new Row("knockback_resistance", num(Math.round(kb * 1000) / 10.0) + "%"));
        } else if (mods.modifiers().stream().anyMatch(m -> m.attribute().is(Attributes.ATTACK_DAMAGE))) { // bows, shields: durability only
            rows.add(new Row("attack_power", num(sum(mods, Attributes.ATTACK_DAMAGE, 1))));
            rows.add(new Row("attack_speed", num(sum(mods, Attributes.ATTACK_SPEED, 4) * (1 + mult(mods)))));
        }
        rows.add(new Row("durability", (s.getMaxDamage() - s.getDamageValue()) + " / " + s.getMaxDamage()));
        e.getTooltipElements().add(STATS_AT, Either.right(new Stats(rows)));
    }

    /** The rating tag, or a Normal stand-in for unrated gear, food and potions so every one gets the same window. */
    @org.jetbrains.annotations.Nullable
    static CompoundTag view(ItemStack s) {
        CompoundTag d = Rating.data(s);
        if (d != null || s.isEmpty()) return d;
        var pc = s.get(DataComponents.POTION_CONTENTS);
        String kind = s.isDamageableItem() ? "gear" : s.has(DataComponents.FOOD) ? "food" : pc != null && pc.hasEffects() ? "potion" : null;
        if (kind == null) return null;
        CompoundTag v = new CompoundTag();
        v.putString("tier", Rating.Tier.NORMAL.name());
        v.putString("kind", kind);
        return v;
    }

    /** Hides vanilla's lines for the stats the window shows (and rating/enhance bonuses, shown as rows and options);
     * other attributes, like netherite's knockback resistance or other mods', still show. */
    @SubscribeEvent
    public static void onSkipAttributes(net.neoforged.neoforge.client.event.GatherSkippedAttributeTooltipsEvent e) {
        ItemStack s = e.getStack();
        CompoundTag d = view(s);
        if (d == null || !s.isDamageableItem()) return;
        for (var m : Smith.base(s).modifiers()) {
            String path = m.modifier().id().getPath();
            if (m.attribute().is(Attributes.ATTACK_DAMAGE) || m.attribute().is(Attributes.ATTACK_SPEED) || m.attribute().is(Attributes.ARMOR)
                    || m.attribute().is(Attributes.ARMOR_TOUGHNESS) || m.attribute().is(Attributes.KNOCKBACK_RESISTANCE)
                    || m.attribute().is(Attributes.ENTITY_INTERACTION_RANGE) // attack range lines (Better Combat reformats these)
                    || path.startsWith("rating/") || path.startsWith("enhance/"))
                e.skipId(m.modifier().id());
        }
    }

    @SubscribeEvent
    public static void onColor(RenderTooltipEvent.Color e) {
        if (view(e.getItemStack()) == null) return;
        int c = Rating.tier(e.getItemStack()).color;
        e.setBackground(0xF00C0A10);
        e.setBorderStart(0xFF000000 | c);
        e.setBorderEnd(0xFF000000 | (c >> 2 & 0x3F3F3F)); // same hue, fading down
    }

    record Row(String key, String value) {}

    record Stats(List<Row> rows) implements TooltipComponent {}

    /** Label/value rows with the values in one column. */
    record StatsClient(Stats stats) implements ClientTooltipComponent {
        private int labelWidth(Font f) {
            int w = 0;
            for (Row r : stats.rows) w = Math.max(w, f.width(Component.translatable("habits.rating.stat." + r.key)));
            return w + 12;
        }

        @Override
        public int getHeight() {
            return stats.rows.size() * 10;
        }

        @Override
        public int getWidth(Font f) {
            int w = 0;
            for (Row r : stats.rows) w = Math.max(w, f.width(r.value));
            return labelWidth(f) + w;
        }

        @Override
        public void renderText(Font f, int x, int y, Matrix4f m, MultiBufferSource.BufferSource buf) {
            int col = x + labelWidth(f);
            for (Row r : stats.rows) {
                f.drawInBatch(Component.translatable("habits.rating.stat." + r.key), x, y, LABEL, true, m, buf, Font.DisplayMode.NORMAL, 0, 0xF000F0);
                f.drawInBatch(r.value, col, y, VALUE, true, m, buf, Font.DisplayMode.NORMAL, 0, 0xF000F0);
                y += 10;
            }
        }
    }

    @EventBusSubscriber(modid = Habits.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    static final class Factories {
        private Factories() {}

        @SubscribeEvent
        static void register(RegisterClientTooltipComponentFactoriesEvent e) {
            e.register(Stats.class, StatsClient::new);
        }
    }

    /** Player base plus the stack's flat modifiers on that attribute. */
    private static double sum(ItemAttributeModifiers mods, Holder<Attribute> a, double base) {
        for (var m : mods.modifiers())
            if (m.attribute().is(a) && m.modifier().operation() == AttributeModifier.Operation.ADD_VALUE) base += m.modifier().amount();
        return base;
    }

    private static double mult(ItemAttributeModifiers mods) {
        double x = 0;
        for (var m : mods.modifiers())
            if (m.attribute().is(Attributes.ATTACK_SPEED) && m.modifier().operation() == AttributeModifier.Operation.ADD_MULTIPLIED_BASE) x += m.modifier().amount();
        return x;
    }

    private static String num(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format("%.1f", v);
    }

    /** Dev shot ({@code HABITS_SHOTS=rating}, second shot): plain items in the Normal window. */
    public static Screen shotNormal() {
        ItemStack ench = new ItemStack(Items.DIAMOND_SWORD);
        ench.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        List<ItemStack> items = List.of(new ItemStack(Items.IRON_SWORD), new ItemStack(Items.NETHERITE_CHESTPLATE), new ItemStack(Items.BOW),
                new ItemStack(Items.BREAD), net.minecraft.world.item.alchemy.PotionContents.createItemStack(Items.POTION,
                        net.minecraft.world.item.alchemy.Potions.SWIFTNESS), new ItemStack(Items.STONE_PICKAXE));
        return new Screen(Component.empty()) {
            @Override
            public void render(GuiGraphics g, int mx, int my, float pt) {
                super.render(g, mx, my, pt);
                for (int i = 0; i < items.size(); i++) g.renderTooltip(font, items.get(i), 10 + (i % 3) * 215, 20 + (i / 3) * 175);
            }
        };
    }

    /** Dev shot ({@code HABITS_SHOTS=rating}): three rated items' tooltips. */
    public static Screen shotScreen(Player p) {
        List<ItemStack> items = new ArrayList<>();
        Rating.Tier[] tiers = {Rating.Tier.MYTH, Rating.Tier.LEGENDARY, Rating.Tier.EPIC};
        Item[] base = {Items.NETHERITE_SWORD, Items.DIAMOND_CHESTPLATE, Items.IRON_PICKAXE};
        for (int i = 0; i < 3; i++) {
            ItemStack st = new ItemStack(base[i]);
            Rating.apply(p, st, tiers[i]);
            items.add(st);
        }
        Enhance.setLevel(items.get(0), 7);
        ItemStack potion = net.minecraft.world.item.alchemy.PotionContents.createItemStack(Items.POTION, net.minecraft.world.item.alchemy.Potions.STRENGTH);
        Brew.apply(p, potion, Rating.Tier.LEGENDARY);
        items.add(potion);
        ItemStack stew = new ItemStack(Items.RABBIT_STEW);
        com.habits.perk.farming.Dish.apply(p, stew, Rating.Tier.MYTH);
        items.add(stew);
        // wear a 2-piece set (client side only) so the chestplate shows its set lines
        ItemStack helm = new ItemStack(Items.DIAMOND_HELMET);
        Rating.apply(p, helm, Rating.Tier.RARE);
        p.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, helm);
        p.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, items.get(1));
        return new Screen(Component.empty()) {
            @Override
            public void render(GuiGraphics g, int mx, int my, float pt) {
                super.render(g, mx, my, pt);
                for (int i = 0; i < items.size(); i++) g.renderTooltip(font, items.get(i), 10 + (i % 3) * 215, 20 + (i / 3) * 175);
            }
        };
    }
}
