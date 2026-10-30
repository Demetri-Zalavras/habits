package com.habits.perk.crafting;

import com.habits.Habits;
import com.habits.core.Category;
import com.habits.core.HabitsApi;
import com.habits.core.Rarity;
import com.habits.core.SkillDef;
import com.habits.core.SkillRegistry;
import com.habits.core.Specs;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.ItemLore;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Overgeared-style gear ratings: damageable gear crafted by a player who picked the Crafting profession rolls Normal,
 * Rare, Epic, Unique, Legendary or Myth. Higher ratings add damage/armor and durability; Legendary gets one option
 * and a forged name, Myth two. Odds grow with the crafter's total Crafting skill levels. Gear from anyone else, loot,
 * mobs or villagers stays unrated. Options work for whoever holds or wears the item, so crafters can sell them.
 */
public final class Rating {
    private Rating() {}

    static final String KEY = "habits_rating";
    /** Flavor lines per tier: lang habits.rating.flavor.<tier>.<0..FLAVORS-1>. */
    static final int FLAVORS = 3;

    /** pct = damage/armor bonus, dur = durability bonus (%), opts = option count; color matches the habits rarities. */
    public enum Tier {
        NORMAL(0, 0, 0, 0xC9C9C9), RARE(10, 25, 0, Rarity.RARE.color), EPIC(20, 50, 0, Rarity.EPIC.color),
        UNIQUE(30, 75, 0, 0x3FE0D0), LEGENDARY(45, 100, 1, Rarity.LEGENDARY.color), MYTH(60, 150, 2, Rarity.MYTHICAL.color);

        public final int pct, dur, opts, color;

        Tier(int pct, int dur, int opts, int color) {
            this.pct = pct;
            this.dur = dur;
            this.opts = opts;
            this.color = color;
        }

        public Component label() {
            return Component.translatable("habits.rating." + name().toLowerCase()).withStyle(s -> s.withColor(color).withItalic(false));
        }
    }


    /** % chance of each tier at skill fraction f (0..1), best first; the rest is Normal. */
    static double[] odds(double f) {
        return new double[]{0.8 * f, 0.2 + 2.8 * f, 1 + 7 * f, 5 + 15 * f, 20 + 20 * f};
    }

    static Tier roll(Player p, double f, int focus) {
        double r = p.getRandom().nextDouble() * 100, acc = 0;
        double[] o = odds(f);
        double sum = 0;
        for (double x : o) sum += x;
        double mult = Math.min(1 + CraftCfg.FOCUS_STEP.get() / 100 * focus, 100 / sum); // never squeeze Normal below 0
        for (int i = 0; i < o.length; i++) o[i] *= mult;
        for (int i = 0; i < o.length; i++) if (r < (acc += o[i])) return Tier.values()[Tier.values().length - 1 - i];
        return Tier.NORMAL;
    }

    static double skill(Player p) {
        return skill(p, Category.CRAFTING);
    }

    /** The player's total levels in category {@code c}, as a 0..1 fraction of {@link #FULL}. */
    static double skill(Player p, Category c) {
        int total = 0;
        for (var en : HabitsApi.collection(p).skills().entrySet()) {
            SkillDef d = SkillRegistry.get(en.getKey());
            if (d != null && d.category() == c) total += en.getValue().level();
        }
        return Math.min(1, total / CraftCfg.FULL_LEVELS.get());
    }

    /** A rating roll for non-gear makers (brewing, cooking): Normal unless the player picked {@code c}. */
    public static Tier rollFor(Player p, Category c) {
        return Specs.of(p).picks().contains(c) ? roll(p, skill(p, c), 0) : Tier.NORMAL;
    }

    /** Marks {@code s} as rated: tier, maker, flavor line and {@code kind} (gear, potion, food) for the tooltip. */
    public static CompoundTag stamp(Player p, ItemStack s, Tier t, String kind) {
        CompoundTag tag = new CompoundTag();
        tag.putString("tier", t.name());
        tag.putString("kind", kind);
        tag.putString("maker", p.getGameProfile().getName());
        tag.putUUID("maker_id", p.getUUID());
        tag.putInt("flavor", p.getRandom().nextInt(FLAVORS));
        CustomData.update(DataComponents.CUSTOM_DATA, s, c -> c.put(KEY, tag));
        return tag;
    }

    /** Rolls a rating onto freshly crafted gear when the crafter picked Crafting. */
    static void onCrafted(Player p, ItemStack s) {
        if (!s.isDamageableItem() || s.has(DataComponents.CUSTOM_DATA) && s.get(DataComponents.CUSTOM_DATA).contains(KEY)) return;
        if (!Specs.of(p).picks().contains(Category.CRAFTING)) return;
        int focus = focus(p, s);
        Tier t = roll(p, skill(p), focus);
        if (t != Tier.NORMAL) apply(p, s, t);
        else stamp(p, s, t, "gear"); // rolled: never rolls again, and shows its maker
        if (t.opts > 0) {
            FOCUS.remove(p.getUUID()); // a Legendary spends the focus
            announce(p, s, t);
        } else if (focus > 0) {
            p.displayClientMessage(Component.translatable("habits.rating.focus", focus, Math.round(focus * CraftCfg.FOCUS_STEP.get()))
                    .withStyle(st -> st.withColor(Tier.UNIQUE.color)), true);
        }
    }

    // ------------------------------------------------------------------ blacksmith's focus

    /** Each craft of the same item in a row (up to the focus_max config) adds focus_step % to the odds. */
    record Streak(net.minecraft.world.item.Item item, int count) {}
    static final Map<UUID, Streak> FOCUS = C.playerMap();

    /** Streak before this craft, then counts this one. A different item starts over. */
    static int focus(Player p, ItemStack s) {
        Streak old = FOCUS.get(p.getUUID());
        int n = old != null && old.item() == s.getItem() ? old.count() : 0;
        FOCUS.put(p.getUUID(), new Streak(s.getItem(), n + 1));
        return Math.min(CraftCfg.MAX_FOCUS.get(), n);
    }

    // ------------------------------------------------------------------ world message

    public static void announce(Player p, ItemStack s, Tier t) {
        if (!(p instanceof net.minecraft.server.level.ServerPlayer sp)) return;
        Component item = s.getHoverName().copy().withStyle(st -> st.withColor(t.color).withBold(true)
                .withHoverEvent(new net.minecraft.network.chat.HoverEvent(net.minecraft.network.chat.HoverEvent.Action.SHOW_ITEM,
                        new net.minecraft.network.chat.HoverEvent.ItemStackInfo(s.copy()))));
        Component msg = Component.translatable("habits.rating.announce." + t.name().toLowerCase(), item, p.getDisplayName())
                .withStyle(st -> st.withColor(0xE8E0C8));
        var server = sp.getServer();
        server.getPlayerList().broadcastSystemMessage(msg, false);
        for (var other : server.getPlayerList().getPlayers())
            other.playNotifySound(t == Tier.MYTH ? net.minecraft.sounds.SoundEvents.UI_TOAST_CHALLENGE_COMPLETE
                    : net.minecraft.sounds.SoundEvents.PLAYER_LEVELUP, net.minecraft.sounds.SoundSource.MASTER, 0.8f, 1);
    }

    // ------------------------------------------------------------------ options

    enum Kind { WEAPON, ARMOR, TOOL }

    /** Attribute options: modifier on the item's own slot. Proc options (no attribute) are read in {@link #onHurt}. */
    enum Opt {
        VAMPIRIC(Kind.WEAPON), BLAZING(Kind.WEAPON), EXECUTION(Kind.WEAPON), WITHERING(Kind.WEAPON), FROSTBITE(Kind.WEAPON),
        FRENZY(Kind.WEAPON, Attributes.ATTACK_SPEED, 0.15, AttributeModifier.Operation.ADD_MULTIPLIED_BASE),
        THORNS(Kind.ARMOR), FIREWARD(Kind.ARMOR), FEATHERFALL(Kind.ARMOR),
        SWIFT(Kind.ARMOR, Attributes.MOVEMENT_SPEED, 0.05, AttributeModifier.Operation.ADD_MULTIPLIED_BASE),
        VITAL(Kind.ARMOR, Attributes.MAX_HEALTH, 2, AttributeModifier.Operation.ADD_VALUE),
        STEADFAST(Kind.ARMOR, Attributes.KNOCKBACK_RESISTANCE, 0.15, AttributeModifier.Operation.ADD_VALUE),
        QUARRY(Kind.TOOL, Attributes.BLOCK_BREAK_SPEED, 0.2, AttributeModifier.Operation.ADD_MULTIPLIED_BASE),
        LONGREACH(Kind.TOOL, Attributes.BLOCK_INTERACTION_RANGE, 1, AttributeModifier.Operation.ADD_VALUE),
        LUCKY(Kind.TOOL, Attributes.LUCK, 1, AttributeModifier.Operation.ADD_VALUE);

        final Kind kind;
        final Holder<Attribute> attr;
        final double amount;
        final AttributeModifier.Operation op;

        Opt(Kind kind) {
            this(kind, null, 0, null);
        }

        Opt(Kind kind, Holder<Attribute> attr, double amount, AttributeModifier.Operation op) {
            this.kind = kind;
            this.attr = attr;
            this.amount = amount;
            this.op = op;
        }
    }

    static Kind kind(ItemStack s) {
        if (s.getItem() instanceof ArmorItem) return Kind.ARMOR;
        if (s.getItem() instanceof DiggerItem && !(s.getItem() instanceof AxeItem)) return Kind.TOOL;
        return Smith.base(s).modifiers().stream().anyMatch(m -> m.attribute().is(Attributes.ATTACK_DAMAGE)) ? Kind.WEAPON : Kind.TOOL;
    }

    private static final String[] FIRST = {"Dawn", "Ember", "Storm", "Grim", "Star", "Iron", "Night", "Frost", "Blood", "Sun", "Void", "Thorn", "Ash", "Moon"};
    private static final String[] SECOND = {"fang", "brand", "heart", "guard", "bane", "song", "fall", "reaver", "ward", "caller", "breaker", "veil"};

    // ------------------------------------------------------------------ apply

    static void apply(Player p, ItemStack s, Tier t) {
        ItemAttributeModifiers base = Smith.base(s), mods = base;
        ResourceLocation dmg = Habits.id("rating/damage"), arm = Habits.id("rating/armor"), tough = Habits.id("rating/toughness");
        double pct = t.pct / 100.0;
        for (var m : base.modifiers()) {
            if (m.modifier().operation() != AttributeModifier.Operation.ADD_VALUE || m.modifier().amount() <= 0) continue;
            if (m.attribute().is(Attributes.ATTACK_DAMAGE)) mods = Smith.add(mods, m, Attributes.ATTACK_DAMAGE, dmg, pct);
            else if (m.attribute().is(Attributes.ARMOR)) mods = Smith.add(mods, m, Attributes.ARMOR, arm, pct);
            else if (m.attribute().is(Attributes.ARMOR_TOUGHNESS)) mods = Smith.add(mods, m, Attributes.ARMOR_TOUGHNESS, tough, pct);
        }
        Kind kind = kind(s);
        List<Opt> pool = new ArrayList<>();
        for (Opt o : Opt.values()) if (o.kind == kind) pool.add(o);
        List<Opt> opts = new ArrayList<>();
        for (int i = 0; i < t.opts && !pool.isEmpty(); i++) opts.add(pool.remove(p.getRandom().nextInt(pool.size())));
        EquipmentSlotGroup group = s.getItem() instanceof ArmorItem a ? EquipmentSlotGroup.bySlot(a.getEquipmentSlot()) : EquipmentSlotGroup.MAINHAND;
        for (Opt o : opts)
            if (o.attr != null) mods = mods.withModifierAdded(o.attr, new AttributeModifier(Habits.id("rating/" + o.name().toLowerCase()), o.amount, o.op), group);
        // RatingClient hides vanilla's lines for the stats its window shows
        s.set(DataComponents.ATTRIBUTE_MODIFIERS, mods);
        s.set(DataComponents.MAX_DAMAGE, (int) Math.round(s.getMaxDamage() * (1 + t.dur / 100.0)));
        if (t.opts > 0) {
            String name = FIRST[p.getRandom().nextInt(FIRST.length)] + SECOND[p.getRandom().nextInt(SECOND.length)];
            s.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle(st -> st.withColor(TextColor.fromRgb(t.color)).withItalic(false)));
        }

        ListTag ol = new ListTag();
        for (Opt o : opts) ol.add(StringTag.valueOf(o.name()));
        CompoundTag tag = stamp(p, s, t, "gear");
        tag.put("opts", ol);
        CustomData.update(DataComponents.CUSTOM_DATA, s, c -> c.put(KEY, tag));
    }

    /** Appends lines after the stack's existing lore. */
    static void lore(ItemStack s, List<Component> lines) {
        List<Component> all = new ArrayList<>(s.getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines());
        all.addAll(lines);
        s.set(DataComponents.LORE, new ItemLore(all));
    }

    /** The rating tag, or null for unrated gear. */
    @org.jetbrains.annotations.Nullable
    public static CompoundTag data(ItemStack s) {
        CustomData d = s.get(DataComponents.CUSTOM_DATA);
        return d == null || !d.contains(KEY) ? null : d.copyTag().getCompound(KEY);
    }

    public static Tier tier(ItemStack s) {
        CustomData d = s.get(DataComponents.CUSTOM_DATA);
        if (d == null || !d.contains(KEY)) return Tier.NORMAL;
        try {
            return Tier.valueOf(d.copyTag().getCompound(KEY).getString("tier"));
        } catch (IllegalArgumentException ex) {
            return Tier.NORMAL;
        }
    }

    static boolean has(ItemStack s, Opt o) {
        CustomData d = s.get(DataComponents.CUSTOM_DATA);
        if (d == null || !d.contains(KEY)) return false;
        for (Tag t : d.copyTag().getCompound(KEY).getList("opts", Tag.TAG_STRING)) if (t.getAsString().equals(o.name())) return true;
        return false;
    }

    // ------------------------------------------------------------------ forged sets

    /** Most worn rated armor pieces by one maker (0..4). */
    static int setPieces(LivingEntity e) {
        Map<UUID, Integer> by = new java.util.HashMap<>();
        int best = 0;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (!slot.isArmor()) continue;
            UUID m = makerId(e.getItemBySlot(slot));
            if (m != null) best = Math.max(best, by.merge(m, 1, Integer::sum));
        }
        return best;
    }

    @org.jetbrains.annotations.Nullable
    static UUID makerId(ItemStack s) {
        CompoundTag d = data(s);
        return d != null && d.hasUUID("maker_id") ? d.getUUID("maker_id") : null;
    }

    /** 2 pieces: less damage taken. 4 pieces: less taken and more melee dealt (CraftCfg set values). */
    static float setTaken(int pieces) {
        return (float) (1 - (pieces >= 4 ? CraftCfg.SET4.get() : pieces >= 2 ? CraftCfg.SET2.get() : 0) / 100);
    }

    static float setDealt(int pieces) {
        return (float) (1 + (pieces >= 4 ? CraftCfg.SET4_DEALT.get() : 0) / 100);
    }

    static int armor(LivingEntity e, Opt o) {
        int n = 0;
        for (EquipmentSlot slot : EquipmentSlot.values()) if (slot.isArmor() && has(e.getItemBySlot(slot), o)) n++;
        return n;
    }

    // ------------------------------------------------------------------ procs (anyone holding or wearing the item)

    @SubscribeEvent(priority = EventPriority.LOW)
    static void onHurt(LivingIncomingDamageEvent e) {
        LivingEntity v = e.getEntity();
        if (v.level().isClientSide) return;
        var src = e.getSource();
        e.setAmount(e.getAmount() * setTaken(setPieces(v)));
        if (src.getEntity() instanceof LivingEntity a && src.getDirectEntity() == a) {
            e.setAmount(e.getAmount() * setDealt(setPieces(a)));
            ItemStack w = a.getMainHandItem();
            if (has(w, Opt.EXECUTION) && v.getHealth() < v.getMaxHealth() * 0.3f) e.setAmount(e.getAmount() * 1.4f);
            if (has(w, Opt.BLAZING)) v.igniteForSeconds(4);
            if (has(w, Opt.WITHERING)) v.addEffect(new MobEffectInstance(MobEffects.WITHER, 60, 0), a);
            if (has(w, Opt.FROSTBITE)) v.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, 1), a);
            if (has(w, Opt.VAMPIRIC)) a.heal(e.getAmount() * 0.1f);
        }
        int n;
        if (src.is(DamageTypeTags.IS_FIRE) && (n = armor(v, Opt.FIREWARD)) > 0) e.setAmount(e.getAmount() * (1 - Math.min(0.8f, 0.2f * n)));
        if (src.is(DamageTypeTags.IS_FALL) && (n = armor(v, Opt.FEATHERFALL)) > 0) e.setAmount(e.getAmount() * (1 - Math.min(0.9f, 0.3f * n)));
        if (src.getEntity() instanceof LivingEntity a && src.getDirectEntity() == a && a != v && (n = armor(v, Opt.THORNS)) > 0
                && !src.is(DamageTypeTags.IS_PROJECTILE) && !src.is(net.minecraft.world.damagesource.DamageTypes.THORNS)) // no thorns ping-pong
            a.hurt(v.damageSources().thorns(v), e.getAmount() * 0.15f * n);
    }
}
