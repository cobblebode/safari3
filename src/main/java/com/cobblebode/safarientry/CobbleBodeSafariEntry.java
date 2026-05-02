package com.cobblebode.safarientry;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.UUID;

public class CobbleBodeSafariEntry implements ModInitializer {
    private static final Identifier TICKET_ID = Identifier.of("cobblesafari", "ticket_dungeon");

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
            Object portal = createHiddenCreativeDungeonPortal(player);

            Class<?> acceptClass = Class.forName("maxigregrze.cobblesafari.dungeon.DungeonTpAcceptHandler");
            Class<?> portalClass = Class.forName("maxigregrze.cobblesafari.block.dungeon.DungeonPortalBlockEntity");

            Method open = acceptClass.getMethod(
                    "openTpAcceptForDungeon",
                    ServerPlayerEntity.class,
                    portalClass
            );

            Method accept = acceptClass.getMethod(
                    "handleAcceptResponse",
                    ServerPlayerEntity.class,
                    boolean.class
            );

            open.invoke(null, player, portal);
            accept.invoke(null, player, true);

            player.sendMessage(Text.literal("§aTicket consumido! Você entrou na Mina Safari por 15 minutos."), false);
            return true;

        } catch (Throwable t) {
            giveItem(player, TICKET_ID, 1);
            player.sendMessage(Text.literal("§cErro ao abrir a Mina Safari. O ticket foi devolvido."), false);
            player.sendMessage(Text.literal("§7Debug: " + t.getClass().getSimpleName() + " - " + safeMsg(t)), false);
            return false;
        }
    }

    private static Object createHiddenCreativeDungeonPortal(ServerPlayerEntity player) throws Exception {
        Class<?> portalClass = Class.forName("maxigregrze.cobblesafari.block.dungeon.DungeonPortalBlockEntity");

        var world = player.getServerWorld();

        BlockPos pos = getHiddenPortalPos(player);

        Block portalBlock = Registries.BLOCK.get(Identifier.of("cobblesafari", "creative_dungeon_portal"));
        BlockState state = portalBlock.getDefaultState();

        world.setBlockState(pos, state, Block.NOTIFY_ALL);

        Object portal = world.getBlockEntity(pos);

        if (portal == null || !portalClass.isInstance(portal)) {
            throw new IllegalStateException("Falha ao criar o portal oculto do CobbleSafari em " + pos);
        }

        portalClass.getMethod("setPortalId", UUID.class).invoke(portal, UUID.randomUUID());
        portalClass.getMethod("setOriginPos", BlockPos.class).invoke(portal, player.getBlockPos());
        portalClass.getMethod("setOriginDimension", net.minecraft.registry.RegistryKey.class).invoke(portal, player.getWorld().getRegistryKey());

        portalClass.getMethod("setRandomDestinationMode", boolean.class).invoke(portal, true);
        portalClass.getMethod("setAutoRenewPortal", boolean.class).invoke(portal, true);

        portalClass.getMethod("setDungeonDimensionId", String.class).invoke(portal, (Object) null);
        portalClass.getMethod("setFixedDungeonId", String.class).invoke(portal, (Object) null);

        portalClass.getMethod("setSpawnTick", long.class).invoke(portal, world.getTime());

        return portal;
    }

    private static BlockPos getHiddenPortalPos(ServerPlayerEntity player) {
        int hash = Math.abs(player.getUuid().hashCode());

        int x = 30_000_000 - (hash % 1000);
        int z = 30_000_000 - ((hash / 1000) % 1000);

        return new BlockPos(x, -60, z);
    }

    private static boolean hasItem(ServerPlayerEntity player, Identifier itemId) {
        Item target = Registries.ITEM.get(itemId);

        for (ItemStack stack : player.getInventory().main) {
            if (!stack.isEmpty() && stack.isOf(target)) return true;
        }

        for (ItemStack stack : player.getInventory().offHand) {
            if (!stack.isEmpty() && stack.isOf(target)) return true;
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
