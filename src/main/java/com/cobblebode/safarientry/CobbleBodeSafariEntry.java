package com.cobblebode.safarientry;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.Collection;

public class CobbleBodeSafariEntry implements ModInitializer {
    private static final Identifier TICKET_ID = Identifier.of("cobblesafari", "ticket_dungeon");
    private static final int DUNGEON_SECONDS = 1800;

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> register(dispatcher));
    }

    private static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
                CommandManager.literal("cobblebode")
                        .requires(source -> source.hasPermissionLevel(2))
                        .then(CommandManager.literal("dungeon")
                                .then(CommandManager.literal("enter")
                                        .then(CommandManager.argument("player", EntityArgumentType.players())
                                                .executes(ctx -> {
                                                    Collection<ServerPlayerEntity> players = EntityArgumentType.getPlayers(ctx, "player");

                                                    int success = 0;
                                                    for (ServerPlayerEntity player : players) {
                                                        if (enterDirect(player)) {
                                                            success++;
                                                        }
                                                    }

                                                    return success;
                                                })
                                        )
                                )
                                .then(CommandManager.literal("test_ticket")
                                        .then(CommandManager.argument("player", EntityArgumentType.player())
                                                .executes(ctx -> {
                                                    ServerPlayerEntity player = EntityArgumentType.getPlayer(ctx, "player");
                                                    boolean has = hasItem(player, TICKET_ID);

                                                    ctx.getSource().sendFeedback(
                                                            () -> Text.literal("Ticket Dungeon de " + player.getName().getString() + ": " + (has ? "SIM" : "NÃO")),
                                                            false
                                                    );

                                                    return has ? 1 : 0;
                                                })
                                        )
                                )
                        )
        );
    }

    private static boolean enterDirect(ServerPlayerEntity player) {
        if (!consumeItem(player, TICKET_ID, 1)) {
            player.sendMessage(Text.literal("§cVocê precisa de um Ticket Dungeon para entrar na Mina Safari."), false);
            return false;
        }

        try {
            String playerName = player.getName().getString();

            runConsoleCommand(player,
                    "cobblesafari timer dimension set " + playerName + " " + DUNGEON_SECONDS + " cobblesafari:dungeon_underground"
            );

            runConsoleCommand(player,
                    "cobblesafari dungeon enter " + playerName
            );

            player.sendMessage(Text.literal("§aTicket consumido! Você entrou na Mina Safari por 30 minutos."), false);
            return true;
        } catch (Throwable t) {
            giveItem(player, TICKET_ID, 1);

            player.sendMessage(Text.literal("§cErro ao abrir a Mina Safari. O ticket foi devolvido."), false);
            player.sendMessage(Text.literal("§7Debug: " + t.getClass().getSimpleName() + " - " + safeMsg(t)), false);
            return false;
        }
    }

    private static void runConsoleCommand(ServerPlayerEntity player, String command) {
        player.getServer().getCommandManager().executeWithPrefix(
                player.getServer().getCommandSource(),
                command
        );
    }

    private static boolean hasItem(ServerPlayerEntity player, Identifier itemId) {
        Item target = Registries.ITEM.get(itemId);

        for (ItemStack stack : player.getInventory().main) {
            if (!stack.isEmpty() && stack.isOf(target)) {
                return true;
            }
        }

        for (ItemStack stack : player.getInventory().offHand) {
            if (!stack.isEmpty() && stack.isOf(target)) {
                return true;
            }
        }

        return false;
    }

    private static boolean consumeItem(ServerPlayerEntity player, Identifier itemId, int amount) {
        Item target = Registries.ITEM.get(itemId);
        int remaining = amount;

        for (ItemStack stack : player.getInventory().main) {
            if (remaining <= 0) break;

            if (!stack.isEmpty() && stack.isOf(target)) {
                int remove = Math.min(remaining, stack.getCount());
                stack.decrement(remove);
                remaining -= remove;
            }
        }

        if (remaining > 0) {
            for (ItemStack stack : player.getInventory().offHand) {
                if (remaining <= 0) break;

                if (!stack.isEmpty() && stack.isOf(target)) {
                    int remove = Math.min(remaining, stack.getCount());
                    stack.decrement(remove);
                    remaining -= remove;
                }
            }
        }

        if (remaining == 0) {
            player.getInventory().markDirty();
            return true;
        }

        int consumed = amount - remaining;
        if (consumed > 0) {
            giveItem(player, itemId, consumed);
        }

        return false;
    }

    private static void giveItem(ServerPlayerEntity player, Identifier itemId, int amount) {
        Item item = Registries.ITEM.get(itemId);
        ItemStack stack = new ItemStack(item, amount);

        boolean inserted = player.getInventory().insertStack(stack);
        if (!inserted) {
            player.dropItem(stack, false);
        }
    }

    private static String safeMsg(Throwable t) {
        String msg = t.getMessage();

        if (msg == null && t.getCause() != null) {
            msg = t.getCause().getMessage();
        }

        return msg == null ? "sem mensagem" : msg;
    }
}
