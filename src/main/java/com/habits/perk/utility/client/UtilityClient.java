package com.habits.perk.utility.client;

import com.habits.perk.utility.UtilityHooks;
import com.habits.perk.utility.UtilityNet;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Client side of the utility perks: the Ender Link key, rummager slot tint, bookworm hints. */
public final class UtilityClient {
    private UtilityClient() {}

    static final KeyMapping ENDER = new KeyMapping("key.habits.utility_ender", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_END, "key.categories.habits_utility"); // not ' (Open Parties and Claims menu)

    public static void init(IEventBus modBus) {
        modBus.addListener((RegisterKeyMappingsEvent e) -> e.register(ENDER));
        NeoForge.EVENT_BUS.addListener(UtilityClient::onTick);
    }

    // rummager: what the player carries, rebuilt every 10 ticks into the same set (no per-frame work)
    private static final Set<Item> CARRIED = new HashSet<>();
    private static boolean rummager;

    private static void onTick(ClientTickEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) { rummager = false; CARRIED.clear(); return; }
        while (ENDER.consumeClick()) PacketDistributor.sendToServer(UtilityNet.EnderKey.INSTANCE);
        if (mc.screen == null && p.tickCount % 2 == 0 && p.isShiftKeyDown() && mc.options.keyUse.isDown() && UtilityHooks.mendingTouch(p))
            PacketDistributor.sendToServer(UtilityNet.MendPulse.INSTANCE);
        if (p.tickCount % 10 != 0) return;
        rummager = UtilityHooks.rummager(p);
        CARRIED.clear();
        if (!rummager) return;
        Inventory inv = p.getInventory();
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty()) CARRIED.add(s.getItem());
        }
    }

    /** Called after vanilla draws each slot: chest-like slots holding an item you carry get a soft gold tint. */
    public static void rummagerTint(GuiGraphics g, AbstractContainerMenu menu, Slot slot) {
        if (!rummager || !(menu instanceof ChestMenu || menu instanceof ShulkerBoxMenu) || slot.container instanceof Inventory) return;
        ItemStack s = slot.getItem();
        if (!s.isEmpty() && CARRIED.contains(s.getItem())) {
            g.fill(RenderType.guiOverlay(), slot.x, slot.y, slot.x + 16, slot.y + 16, 0x60FFD24A);
        }
    }

    // bookworm: the client knows the table's seed, costs and item, so it rolls the same lists the server does
    private static long hintKey = Long.MIN_VALUE;
    private static List<Component> hintLines = List.of();

    private static final int SEED_SLOT = 3; // EnchantmentMenu data slots: costs 0-2, then the seed

    /** Adds the offer's other enchants under vanilla's one-hint line. */
    public static void bookwormHints(EnchantmentMenu menu, List<Component> lines, int row) {
        Minecraft mc = Minecraft.getInstance();
        int show = UtilityHooks.bookwormHints(mc.player);
        if (show <= 1 || row < 0 || row > 2 || lines.isEmpty() || mc.level == null || menu.enchantClue[row] < 0) return;
        // a remote client's data slot holds only the seed's low 16 bits: roll from the server's full seed, or show nothing
        int slot = menu.getEnchantmentSeed(), seed = UtilityHooks.tableSeed;
        if (slot != seed && slot != (short) seed) return;
        ItemStack item = menu.getSlot(0).getItem();
        long key = ((long) seed << 20) ^ ((long) row << 18) ^ ((long) menu.costs[row] << 8) ^ System.identityHashCode(item) ^ show;
        if (key != hintKey) {
            hintKey = key;
            var clue = mc.level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT).getHolder(menu.enchantClue[row]);
            List<Component> extra = new ArrayList<>();
            menu.setData(SEED_SLOT, seed);
            List<EnchantmentInstance> list = menu.getEnchantmentList(mc.level.registryAccess(), item, row, menu.costs[row]);
            menu.setData(SEED_SLOT, slot);
            for (EnchantmentInstance e : list) {
                if (extra.size() >= show - 1) break;
                if (clue.isPresent() && e.enchantment.is(clue.get()) && e.level == menu.levelClue[row]) continue;
                extra.add(Component.translatable("container.enchant.clue", Enchantment.getFullname(e.enchantment, e.level)).withStyle(ChatFormatting.WHITE));
            }
            hintLines = extra;
        }
        lines.addAll(1, hintLines);
    }
}
