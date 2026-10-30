package com.habits.perk.crafting;

import com.habits.core.Category;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.brewing.PlayerBrewedPotionEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Rated potions: a player who picked Crafting rolls a rating on every potion they take from a brewing stand. Each tier
 * above Normal lengthens the effects ({@link CraftCfg#POTION_DURATION} per tier); Legendary and Myth also raise their
 * level by one and add one or two bonus effects. Water and other effectless bottles stay plain.
 */
final class Brew {
    private Brew() {}

    @SuppressWarnings("unchecked")
    static final Holder<MobEffect>[] BONUS = new Holder[]{MobEffects.REGENERATION, MobEffects.DAMAGE_RESISTANCE, MobEffects.MOVEMENT_SPEED,
            MobEffects.DAMAGE_BOOST, MobEffects.FIRE_RESISTANCE, MobEffects.ABSORPTION};

    @SubscribeEvent
    static void onBrewed(PlayerBrewedPotionEvent e) {
        Player p = e.getEntity();
        if (!C.ok(p)) return;
        ItemStack s = Anvil.real(p, e.getStack());
        if (s == null || Rating.data(s) != null) return;
        PotionContents pc = s.get(DataComponents.POTION_CONTENTS);
        if (pc == null || !pc.hasEffects()) return;
        Rating.Tier t = Rating.rollFor(p, Category.CRAFTING);
        if (t == Rating.Tier.NORMAL) return;
        apply(p, s, t);
        if (t.opts > 0) Rating.announce(p, s, t);
    }

    static void apply(Player p, ItemStack s, Rating.Tier t) {
        PotionContents pc = s.get(DataComponents.POTION_CONTENTS);
        Component name = s.getHoverName();
        double len = 1 + CraftCfg.POTION_DURATION.get() / 100 * t.ordinal();
        int up = t.opts > 0 ? 1 : 0;
        List<MobEffectInstance> effects = new ArrayList<>();
        for (MobEffectInstance m : pc.getAllEffects())
            effects.add(new MobEffectInstance(m.getEffect(), m.getEffect().value().isInstantenous() ? m.getDuration() : (int) (m.getDuration() * len),
                    m.getAmplifier() + up, m.isAmbient(), m.isVisible(), m.showIcon()));
        List<Holder<MobEffect>> pool = new ArrayList<>(List.of(BONUS));
        pool.removeIf(b -> effects.stream().anyMatch(m -> m.getEffect().equals(b)));
        int bonusTicks = (int) (CraftCfg.POTION_BONUS_SECONDS.get() * 20 * t.opts);
        for (int i = 0; i < t.opts && !pool.isEmpty(); i++)
            effects.add(new MobEffectInstance(pool.remove(p.getRandom().nextInt(pool.size())), bonusTicks, 0));
        // the potion id is dropped (its effects would apply twice), so keep its color and name by hand
        s.set(DataComponents.POTION_CONTENTS, new PotionContents(Optional.empty(), Optional.of(pc.getColor()), effects));
        s.set(DataComponents.CUSTOM_NAME, name.copy().withStyle(st -> st.withColor(TextColor.fromRgb(t.color)).withItalic(false)));
        Rating.stamp(p, s, t, "potion");
    }
}
