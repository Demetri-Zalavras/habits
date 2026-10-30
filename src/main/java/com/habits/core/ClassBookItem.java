package com.habits.core;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** A class book ({@code habits:class_book}, component {@link CoreRegistries#CLASS_BOOK}: the class id, a
 * {@code class_passive} skill). Right-click opens a confirm screen (pick which profession it replaces) to become that class
 * ({@link Specs#becomeClass}: the skills of its base reset, the other pick stays). Rare drop: chests, bosses, level-ups ({@link #roll}). */
public class ClassBookItem extends Item {
    public ClassBookItem(Properties props) {
        super(props);
    }

    public static ItemStack create(ResourceLocation clazz) {
        ItemStack s = new ItemStack(CoreRegistries.CLASS_BOOK_ITEM.get());
        s.set(CoreRegistries.CLASS_BOOK.get(), clazz);
        return s;
    }

    @Nullable
    public static SkillDef classOf(ItemStack s) {
        ResourceLocation id = s.get(CoreRegistries.CLASS_BOOK.get());
        SkillDef d = id == null ? null : SkillRegistry.get(id);
        return d != null && d.classPassive() ? d : null;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!(player instanceof ServerPlayer sp)) return InteractionResultHolder.success(stack);
        SkillDef clazz = classOf(stack);
        if (clazz == null) return InteractionResultHolder.fail(stack);
        String refusal = Specs.classRefusal(sp, clazz);
        if (refusal != null) {
            sp.displayClientMessage(Component.translatable(refusal, clazz.category().displayName()), true);
            return InteractionResultHolder.fail(stack);
        }
        if (!(sp instanceof net.neoforged.neoforge.common.util.FakePlayer))
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp, new Payloads.OpenClassBook(clazz.id(), Specs.replaceable(sp, clazz)));
        return InteractionResultHolder.consume(stack);
    }

    /** The confirm screen's choice: become {@code clazzId} from a held book, giving up {@code replace} (must be one of
     * {@link Specs#replaceable}, or null when that is empty). Uses up the book. */
    public static void confirm(ServerPlayer sp, ResourceLocation clazzId, @Nullable Category replace) {
        InteractionHand hand = null;
        for (InteractionHand h : InteractionHand.values()) {
            SkillDef d = classOf(sp.getItemInHand(h));
            if (d != null && d.id().equals(clazzId)) { hand = h; break; }
        }
        if (hand == null) return;
        SkillDef clazz = SkillRegistry.get(clazzId);
        List<Category> options = Specs.replaceable(sp, clazz);
        if (replace == null ? !options.isEmpty() : !options.contains(replace)) return; // stale screen: picks changed
        String refusal = Specs.becomeClass(sp, clazz, replace != null && options.indexOf(replace) == 0 && options.size() > 1);
        if (refusal != null) {
            sp.displayClientMessage(Component.translatable(refusal, clazz.category().displayName()), true);
            return;
        }
        sp.getItemInHand(hand).consume(1, sp);
    }

    @Override
    public Component getName(ItemStack stack) {
        SkillDef c = classOf(stack);
        return c == null ? super.getName(stack)
                : Component.translatable("item.habits.class_book.named", c.displayName()).withColor(c.rarity().color);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> lines, TooltipFlag flag) {
        SkillDef c = classOf(stack);
        if (c == null) return;
        lines.add(Component.translatable("item.habits.class_book.base", c.rarity().displayName(), c.category().displayName()).withColor(0xA0A0A0));
        lines.add(c.perkLine(1).copy().withColor(0xE0E0E0));
        List<Component> extra = new ArrayList<>();
        for (SkillDef d : SkillRegistry.all())
            if (d.classId().filter(c.id()::equals).isPresent()) extra.add(d.displayName().copy().withColor(d.rarity().color));
        if (!extra.isEmpty()) {
            lines.add(Component.translatable("item.habits.class_book.skills").withColor(0xA0A0A0));
            extra.forEach(e -> lines.add(Component.literal("  ").append(e)));
        }
        if (c.rarity() == Rarity.LEGENDARY) lines.add(Component.translatable("item.habits.class_book.unique").withColor(Rarity.LEGENDARY.color));
        lines.add(Component.translatable("item.habits.class_book.warn").withColor(0xE05050));
    }

    // ---------------------------------------------------------------- drops

    /** A random class book (weights per rarity: {@code core.classBookWeights}), never a Legendary someone holds; null
     * when no class is loaded. */
    @Nullable
    public static ItemStack roll(MinecraftServer server, RandomSource r) {
        List<SkillDef> classes = new ArrayList<>();
        List<Double> w = new ArrayList<>();
        double total = 0;
        for (SkillDef d : SkillRegistry.all()) {
            if (!d.classPassive() || d.rarity() == Rarity.LEGENDARY && ClassHolders.taken(server, d.id())
                    || d.category().combat && !Specs.combatSpecs()) continue;
            double weight = CoreInit.at(CoreInit.CLASS_BOOK_WEIGHTS, d.rarity());
            if (weight <= 0) continue;
            classes.add(d);
            w.add(weight);
            total += weight;
        }
        double x = r.nextDouble() * total;
        for (int i = 0; i < classes.size(); i++) if ((x -= w.get(i)) < 0) return create(classes.get(i).id());
        return null;
    }

    /** Gives a rolled book straight to the player (inventory, else at their feet) with a chat line. */
    public static void grant(ServerPlayer p) {
        ItemStack book = roll(p.server, p.getRandom());
        if (book == null) return;
        p.displayClientMessage(Component.translatable("habits.msg.class_book_found", book.getHoverName()), false);
        if (!p.getInventory().add(book)) p.drop(book, false);
    }
}
