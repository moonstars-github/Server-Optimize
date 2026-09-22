package com.server_optimize.client.mixin;

import com.server_optimize.client.CommandHistoryManager;
import net.minecraft.client.CommandHistory;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.GuiMessageTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Client-side command-history tweaks (client.CommandHistoryDeduplicate /
 * DropTalk / DropError). Intercepts the persistence call in
 * {@code ChatComponent.addRecentChat} and watches for red command-failure
 * messages in {@code ChatComponent.addMessage}.
 */
@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {

    /** Route the vanilla history add through the manager (dedup / talk / track). */
    @Redirect(
        method = "addRecentChat",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/CommandHistory;addCommand(Ljava/lang/String;)V")
    )
    private void serverOptimize$handleAddCommand(CommandHistory history, String command) {
        CommandHistoryManager.onCommand(history, command);
    }

    /** Detect red system messages (command failures) to classify drop-on-exit. */
    @Inject(
        method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V",
        at = @At("HEAD")
    )
    private void serverOptimize$detectCommandError(Component message, MessageSignature signature,
                                                   GuiMessageTag tag, CallbackInfo ci) {
        CommandHistoryManager.onChatMessage(message);
    }
}
