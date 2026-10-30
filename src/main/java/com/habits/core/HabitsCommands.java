package com.habits.core;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

/** {@code /habits give|set|reset|list|roll|spec|rewardcard}. Op level 2. */
final class HabitsCommands {
    private HabitsCommands() {}

    private static final SuggestionProvider<CommandSourceStack> SKILLS =
            (c, b) -> SharedSuggestionProvider.suggestResource(SkillRegistry.all().stream().map(SkillDef::id), b);
    private static final DynamicCommandExceptionType UNKNOWN = new DynamicCommandExceptionType(
            id -> Component.translatable("habits.cmd.unknown_skill", String.valueOf(id)));

    static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("habits").requires(s -> s.hasPermission(2))
                .then(Commands.literal("give").then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("skill", ResourceLocationArgument.id()).suggests(SKILLS)
                                .executes(c -> give(c, 1))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                                        .executes(c -> give(c, IntegerArgumentType.getInteger(c, "count")))))))
                .then(Commands.literal("set").then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("skill", ResourceLocationArgument.id()).suggests(SKILLS)
                                .then(Commands.argument("level", IntegerArgumentType.integer(0, Leveling.MAX_LEVEL))
                                        .executes(HabitsCommands::set)))))
                .then(Commands.literal("reset").then(Commands.argument("player", EntityArgument.player())
                        .executes(HabitsCommands::reset)))
                .then(Commands.literal("list").then(Commands.argument("player", EntityArgument.player())
                        .executes(HabitsCommands::list)))
                .then(Commands.literal("spec").then(Commands.argument("player", EntityArgument.player())
                        .executes(c -> {
                            ServerPlayer p = EntityArgument.getPlayer(c, "player");
                            Specs.reset(p); // picks cleared, cards kept, the screen opens
                            c.getSource().sendSuccess(() -> Component.translatable("habits.cmd.spec_reset", p.getDisplayName()), true);
                            return 1;
                        })))
                .then(Commands.literal("rewardcard").then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("rarity", com.mojang.brigadier.arguments.StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(java.util.Arrays.stream(Rarity.values()).map(Rarity::getSerializedName), b))
                                .executes(HabitsCommands::rewardCard))))
                .then(Commands.literal("roll").then(Commands.argument("skill", ResourceLocationArgument.id()).suggests(SKILLS)
                        .executes(HabitsCommands::roll))));
    }

    private static SkillDef skill(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        var id = ResourceLocationArgument.getId(c, "skill");
        SkillDef def = SkillRegistry.get(id);
        if (def == null) throw UNKNOWN.create(id);
        return def;
    }

    private static int give(CommandContext<CommandSourceStack> c, int count) throws CommandSyntaxException {
        ServerPlayer p = EntityArgument.getPlayer(c, "player");
        SkillDef def = skill(c);
        ItemStack stack = CardItems.create(def);
        stack.setCount(count);
        if (!p.getInventory().add(stack)) p.drop(stack, false);
        c.getSource().sendSuccess(() -> Component.translatable("habits.cmd.give", count, def.displayName(), p.getDisplayName()), true);
        return count;
    }

    private static int set(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = EntityArgument.getPlayer(c, "player");
        SkillDef def = skill(c);
        int level = IntegerArgumentType.getInteger(c, "level");
        HabitsCollection col = HabitsApi.collection(p);
        var m = new HashMap<>(col.skills());
        if (level == 0) m.remove(def.id());
        else m.put(def.id(), new SkillProgress(level, 0, col.get(def.id()).enabled()));
        p.setData(CoreRegistries.COLLECTION, new HabitsCollection(m));
        c.getSource().sendSuccess(() -> Component.translatable("habits.cmd.set", def.displayName(), level, p.getDisplayName()), true);
        return level;
    }

    private static int reset(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = EntityArgument.getPlayer(c, "player");
        p.setData(CoreRegistries.COLLECTION, HabitsCollection.EMPTY);
        p.setData(CoreRegistries.UNSEEN, List.of());
        c.getSource().sendSuccess(() -> Component.translatable("habits.cmd.reset", p.getDisplayName()), true);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = EntityArgument.getPlayer(c, "player");
        var skills = HabitsApi.collection(p).skills();
        String body = skills.entrySet().stream()
                .map(e -> e.getKey().getPath() + " " + e.getValue().level() + (e.getValue().enabled() ? "" : " (off)"))
                .sorted().collect(Collectors.joining(", "));
        c.getSource().sendSuccess(() -> Component.translatable("habits.cmd.list", p.getDisplayName(), skills.size(), body), false);
        return skills.size();
    }

    /** Quest rewards: XP for a skill of the player's specializations, more for a higher rarity. */
    private static int rewardCard(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = EntityArgument.getPlayer(c, "player");
        String name = com.mojang.brigadier.arguments.StringArgumentType.getString(c, "rarity");
        Rarity rarity = java.util.Arrays.stream(Rarity.values()).filter(r -> r.getSerializedName().equals(name)).findFirst().orElse(null);
        if (rarity == null) throw UNKNOWN.create(name);
        SkillDef def = SkillXp.reward(p, rarity);
        if (def == null) return 0;
        c.getSource().sendSuccess(() -> Component.translatable("habits.cmd.give", 1, def.displayName(), p.getDisplayName()), false);
        return 1;
    }

    private static int roll(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        SkillDef def = skill(c);
        HabitsApi.dropCard(p, def, null);
        c.getSource().sendSuccess(() -> Component.translatable("habits.cmd.roll", def.displayName()), false);
        return 1;
    }
}
