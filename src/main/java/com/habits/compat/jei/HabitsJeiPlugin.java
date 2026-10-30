package com.habits.compat.jei;

import com.habits.Habits;
import com.habits.core.CoreRegistries;
import com.habits.progression.ProgRegistries;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.ingredients.subtypes.ISubtypeInterpreter;
import mezz.jei.api.ingredients.subtypes.UidContext;
import mezz.jei.api.registration.ISubtypeRegistration;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.function.Function;

/** Optional JEI compat, found by JEI through the annotation; nothing else references this class. Tells JEI which
 * component tells card, sigil and charm stacks apart, so each variant gets its own entry. */
@JeiPlugin
public final class HabitsJeiPlugin implements IModPlugin {
    @Override
    public ResourceLocation getPluginUid() {
        return Habits.id("jei");
    }

    @Override
    public void registerItemSubtypes(ISubtypeRegistration r) {
        r.registerSubtypeInterpreter(CoreRegistries.CARD.get(), by(s -> {
            var d = s.get(CoreRegistries.CARD_DATA);
            return d == null ? null : d.skill().toString();
        }));
        r.registerSubtypeInterpreter(ProgRegistries.SIGIL.get(), by(s -> {
            var d = s.get(ProgRegistries.SIGIL_DATA);
            return d == null ? null : d.type().getSerializedName() + "/" + d.rarity().name();
        }));
        // lines are random rolls, so category + rarity is the variant
        r.registerSubtypeInterpreter(ProgRegistries.CHARM.get(), by(s -> {
            var d = s.get(ProgRegistries.CHARM_DATA);
            return d == null ? null : d.category().name() + "/" + d.rarity().name();
        }));
    }

    private static ISubtypeInterpreter<ItemStack> by(Function<ItemStack, String> key) {
        return new ISubtypeInterpreter<>() {
            @Override
            public Object getSubtypeData(ItemStack stack, UidContext context) {
                return key.apply(stack);
            }

            @Override
            public String getLegacyStringSubtypeInfo(ItemStack stack, UidContext context) {
                String k = key.apply(stack);
                return k == null ? "" : k;
            }
        };
    }
}
