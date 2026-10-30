package com.habits.perk.utility;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Takes items out of the containers a player carries (bundles, shulker boxes) or stands near. Every take removes
 * exactly what it returns, so nothing is created. */
final class Pull {
    private Pull() {}

    /** Remove up to {@code max} items matching {@code want} from bundles in the player's inventory. */
    static ItemStack fromBundles(Player p, Predicate<ItemStack> want, int max) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack holder = inv.getItem(i);
            BundleContents b = holder.get(DataComponents.BUNDLE_CONTENTS);
            if (b == null || b.isEmpty()) continue;
            List<ItemStack> items = new ArrayList<>();
            b.itemCopyStream().forEach(items::add);
            ItemStack got = takeFrom(items, want, max);
            if (!got.isEmpty()) {
                holder.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(items));
                return got;
            }
        }
        return ItemStack.EMPTY;
    }

    /** Remove up to {@code max} items matching {@code want} from shulker boxes (any item with a container) in the inventory. */
    static ItemStack fromShulkers(Player p, Predicate<ItemStack> want, int max) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack holder = inv.getItem(i);
            ItemContainerContents c = holder.get(DataComponents.CONTAINER);
            if (c == null || holder.getCount() != 1) continue;
            NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);
            c.copyInto(items);
            ItemStack got = takeFrom(items, want, max);
            if (!got.isEmpty()) {
                holder.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(items));
                return got;
            }
        }
        return ItemStack.EMPTY;
    }

    /** The 36 main inventory slots only, never armor or the offhand. */
    static ItemStack fromMain(Player p, Predicate<ItemStack> want, int max) {
        ItemStack got = takeFrom(p.getInventory().items, want, max);
        if (!got.isEmpty()) p.getInventory().setChanged();
        return got;
    }

    static ItemStack fromContainer(Container c, Predicate<ItemStack> want, int max) {
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            if (!s.isEmpty() && want.test(s)) {
                ItemStack got = c.removeItem(i, Math.min(max, s.getCount()));
                c.setChanged();
                return got;
            }
        }
        return ItemStack.EMPTY;
    }

    private static ItemStack takeFrom(List<ItemStack> items, Predicate<ItemStack> want, int max) {
        for (int i = 0; i < items.size(); i++) {
            ItemStack s = items.get(i);
            if (s.isEmpty() || !want.test(s)) continue;
            ItemStack got = s.split(Math.min(max, s.getCount()));
            if (s.isEmpty()) {
                if (items instanceof NonNullList) items.set(i, ItemStack.EMPTY);
                else items.remove(i);
            }
            return got;
        }
        return ItemStack.EMPTY;
    }

    /** Container block entities within {@code radius} of the player, loaded chunks only. Called on demand, never per tick. */
    static List<Container> nearby(Player p, int radius) {
        List<Container> out = new ArrayList<>();
        if (radius <= 0) return out;
        Level level = p.level();
        BlockPos c = p.blockPosition();
        int r2 = radius * radius;
        for (int cx = (c.getX() - radius) >> 4; cx <= (c.getX() + radius) >> 4; cx++) {
            for (int cz = (c.getZ() - radius) >> 4; cz <= (c.getZ() + radius) >> 4; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    if (be instanceof Container ct && !be.isRemoved() && be.getBlockPos().distSqr(c) <= r2) out.add(ct);
                }
            }
        }
        return out;
    }
}
