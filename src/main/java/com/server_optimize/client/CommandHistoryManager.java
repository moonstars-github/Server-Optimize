package com.server_optimize.client;

import com.server_optimize.config.ModConfig;
import com.server_optimize.config.ModConfig.ClientConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.client.CommandHistory;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Client-side command-history (command_history.txt) tweaks:
 * <ul>
 *   <li>{@code client.CommandHistoryDeduplicate} - when a command is executed
 *       that already exists in the history, remove all its other records and
 *       append the current one as the last line (move-to-end).</li>
 *   <li>{@code client.CommandHistoryDropTalk} - /tell and /say count as chat
 *       and are never persisted to command_history.txt (they still appear in
 *       the in-session ↑↓ history).</li>
 *   <li>{@code client.CommandHistoryDropError} - commands that are incorrect
 *       (parse errors / entity selector type mismatch, e.g. a single-entity
 *       argument matched multiple) are removed from command_history.txt when
 *       the player exits the server or singleplayer world. "Entity/player not
 *       found" errors are NOT incorrect (the command was incomplete) and stay.
 *       Incorrect commands remain usable with ↑↓ during the session and are
 *       only dropped from the persisted file on exit.</li>
 * </ul>
 * Hooked from {@code ChatComponent.addRecentChat} (persistence) and
 * {@code ChatComponent.addMessage} (error detection).
 */
public final class CommandHistoryManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("server-optimize-command-history");

    /** Commands submitted this session, in order, awaiting an error verdict. */
    private static final ArrayDeque<String> pending = new ArrayDeque<>();
    /** Commands classified as incorrect - removed from the file on exit. */
    private static final Set<String> dropCommands = new HashSet<>();

    private CommandHistoryManager() {
    }

    /** Called instead of CommandHistory.addCommand when a command is submitted. */
    public static void onCommand(CommandHistory history, String command) {
        ModConfig cfg = ModConfig.INSTANCE;
        if (cfg == null || cfg.client == null) {
            history.addCommand(command);
            return;
        }
        ClientConfig cc = cfg.client;
        if (cc.commandHistoryDropError) {
            pending.addLast(command);
            while (pending.size() > 64) {
                pending.pollFirst();
            }
        }
        if (cc.commandHistoryDeduplicate && history.history().contains(command)) {
            // remove every other record of this command, then append the new one
            history.history().removeIf(command::equals);
        }
        if (cc.commandHistoryDropTalk && isTalkCommand(command)) {
            return; // treated as chat - never persisted
        }
        history.addCommand(command);
    }

    /** Called on every displayed chat message: red system text = command failure. */
    public static void onChatMessage(Component message) {
        ModConfig cfg = ModConfig.INSTANCE;
        if (cfg == null || cfg.client == null || !cfg.client.commandHistoryDropError) {
            return;
        }
        if (pending.isEmpty()) return;
        TextColor color = message.getStyle().getColor();
        if (color == null) return;
        Integer red = ChatFormatting.RED.getColor();
        if (red == null || color.getValue() != red) return;
        boolean incomplete = hasNotfoundKey(message);
        String command = pending.removeLast();
        if (!incomplete) {
            dropCommands.add(command);
        }
    }

    /** Called on client disconnect / singleplayer world exit. */
    public static void onExit() {
        if (dropCommands.isEmpty()) {
            pending.clear();
            return;
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.gameDirectory == null) {
                dropCommands.clear();
                pending.clear();
                return;
            }
            Path path = mc.gameDirectory.toPath().resolve("command_history.txt");
            if (Files.exists(path)) {
                List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
                List<String> filtered = new ArrayList<>(lines.size());
                for (String line : lines) {
                    if (!dropCommands.contains(line)) {
                        filtered.add(line);
                    }
                }
                if (filtered.size() != lines.size()) {
                    Files.write(path, filtered, StandardCharsets.UTF_8);
                }
            }
            // Also prune the in-memory history so a later save() cannot re-add them.
            CommandHistory history = mc.commandHistory();
            if (history != null) {
                history.history().removeIf(dropCommands::contains);
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to prune command_history.txt", e);
        }
        dropCommands.clear();
        pending.clear();
    }

    /** /tell and /say (and aliases /msg /w? no - only the two requested). */
    private static boolean isTalkCommand(String command) {
        return command.startsWith("/tell ") || command.startsWith("/say ")
            || command.equals("/tell") || command.equals("/say");
    }

    /** Walk the component tree for a translatable key containing "notfound"
     *  (entity/player resolution failure = incomplete command, keep it). */
    private static boolean hasNotfoundKey(Component component) {
        if (component.getContents() instanceof TranslatableContents translatable
            && translatable.getKey().contains("notfound")) {
            return true;
        }
        for (Component child : component.getSiblings()) {
            if (hasNotfoundKey(child)) {
                return true;
            }
        }
        return false;
    }
}