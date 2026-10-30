package com.habits.client.book;

import com.habits.core.HabitsApi;
import com.habits.core.Payloads;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/** "New" state of skills, for the binder's NEW badge and open-to-newest (screenshots swap in test data). */
interface BookSeen {
    boolean isUnseen(ResourceLocation skill);

    @Nullable
    ResourceLocation newest();

    /** The player hovered the card: tell the server once. */
    void markSeen(ResourceLocation skill);

    /** The live player's state (core's synced UNSEEN attachment). */
    BookSeen PLAYER = new BookSeen() {
        @Override
        public boolean isUnseen(ResourceLocation skill) {
            var player = Minecraft.getInstance().player;
            return player != null && HabitsApi.isUnseen(player, skill);
        }

        @Override
        public @Nullable ResourceLocation newest() {
            var player = Minecraft.getInstance().player;
            return player == null ? null : HabitsApi.newestUnseen(player);
        }

        @Override
        public void markSeen(ResourceLocation skill) {
            PacketDistributor.sendToServer(new Payloads.MarkSeen(skill));
        }
    };
}
