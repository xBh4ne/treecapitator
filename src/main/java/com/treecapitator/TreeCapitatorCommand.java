package com.treecapitator;

import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Registers /treecapitator and its player subcommands. No operator permission
 * is required.
 */
public final class TreeCapitatorCommand {
    private TreeCapitatorCommand() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("treecapitator")
                        .then(Commands.literal("toggle")
                                .executes(TreeCapitatorCommand::toggle))
                        .then(Commands.literal("status")
                                .executes(TreeCapitatorCommand::status))
                        .executes(TreeCapitatorCommand::status)));
    }

    private static int setEnabled(CommandContext<CommandSourceStack> context, boolean enabled) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFailure(Component.literal(
                    "Questo comando può essere usato solo da un giocatore."));
            return 0;
        }

        TreeCapitatorPreferences.setEnabled(player, enabled);

        Component message = enabled
                ? Component.literal("TreeCapitator abilitata per te.").withStyle(ChatFormatting.GREEN)
                : Component.literal("TreeCapitator disabilitata per te.").withStyle(ChatFormatting.RED);
        context.getSource().sendSuccess(() -> message, false);
        return 1;
    }

    private static int toggle(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFailure(Component.literal(
                    "Questo comando può essere usato solo da un giocatore."));
            return 0;
        }

        return setEnabled(context, !TreeCapitatorPreferences.isEnabled(player));
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFailure(Component.literal(
                    "Questo comando può essere usato solo da un giocatore."));
            return 0;
        }

        boolean enabled = TreeCapitatorPreferences.isEnabled(player);
        Component message = Component.literal("TreeCapitator: ")
                .append(Component.literal(enabled ? "abilitata" : "disabilitata")
                        .withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.RED))
                .append(Component.literal(" per te."));
        context.getSource().sendSuccess(() -> message, false);
        return 1;
    }
}
