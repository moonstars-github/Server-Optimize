package com.server_optimize.mixin.accessor;

import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Access to the private {@code source} field of {@link CommandSourceStack},
 * so the command module can tell console (ServerCommandSource) from Rcon
 * (RconConsoleSource) executors for the {@code [Server|Rcon|player]} prefix.
 */
@Mixin(CommandSourceStack.class)
public interface CommandSourceStackAccessor {

    @Accessor("source")
    CommandSource serverOptimize$getSource();
}