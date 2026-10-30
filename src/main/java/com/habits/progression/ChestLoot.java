package com.habits.progression;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
import net.neoforged.neoforge.common.loot.LootModifier;

/** Global loot modifier: every {@code chests/...} loot table may add a sigil and a charm (config chances). Data:
 * {@code data/habits/loot_modifiers/progression_chests.json}, listed in neoforge's global_loot_modifiers.json. */
public class ChestLoot extends LootModifier {
    public static final MapCodec<ChestLoot> CODEC = RecordCodecBuilder.mapCodec(i -> codecStart(i).apply(i, ChestLoot::new));

    public ChestLoot(LootItemCondition[] conditions) {
        super(conditions);
    }

    @Override
    protected ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> loot, LootContext ctx) {
        String path = ctx.getQueriedLootTableId().getPath();
        // vaults roll chests/trial_chambers/reward* once per ejected item; they are not chests to the player
        if (!path.startsWith("chests/") || path.startsWith("chests/trial_chambers/reward")) return loot;
        RandomSource r = ctx.getRandom();
        if (r.nextDouble() < ProgressionInit.CHEST_SIGIL_CHANCE.get())
            loot.add(SigilItem.create(Drops.rollSigil(r, ProgressionInit.LOOT_WEIGHTS)));
        if (r.nextDouble() < ProgressionInit.CHEST_CHARM_CHANCE.get())
            loot.add(CharmItem.create(Drops.rollCharm(ctx.getParamOrNull(net.minecraft.world.level.storage.loot.parameters.LootContextParams.THIS_ENTITY)
                    instanceof net.minecraft.world.entity.player.Player opener ? opener : null, r, Drops.rollRarity(r, ProgressionInit.LOOT_WEIGHTS))));
        if (r.nextDouble() < com.habits.core.CoreInit.CLASS_BOOK_CHEST_CHANCE.get()) {
            ItemStack book = com.habits.core.ClassBookItem.roll(ctx.getLevel().getServer(), r);
            if (book != null) loot.add(book);
        }
        return loot;
    }

    @Override
    public MapCodec<? extends IGlobalLootModifier> codec() {
        return CODEC;
    }
}
