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
                                                        if (enterDirect(player)) success++;
                                                    }

                                                    return success;
                                                })
                                        )
                                )
                        )
        );
    }

    private static boolean enterDirect(ServerPlayerEntity player) {
        if (!consumeItem(player, TICKET_ID, 1)) {
            player.sendMessage(Text.literal("§cVocê precisa de um Ticket Dungeon."), false);
            return false;
        }

        try {
            Object portal = createHiddenCreativeDungeonPortal(player);

            Class<?> acceptClass = Class.forName("maxigregrze.cobblesafari.dungeon.DungeonTpAcceptHandler");
            Class<?> portalClass = Class.forName("maxigregrze.cobblesafari.block.dungeon.DungeonPortalBlockEntity");

            acceptClass.getMethod("openTpAcceptForDungeon", ServerPlayerEntity.class, portalClass)
                    .invoke(null, player, portal);

            acceptClass.getMethod("handleAcceptResponse", ServerPlayerEntity.class, boolean.class)
                    .invoke(null, player, true);

            player.sendMessage(Text.literal("§aVocê entrou na Mina Safari!"), false);
            return true;

        } catch (Throwable t) {
            giveItem(player, TICKET_ID, 1);
            player.sendMessage(Text.literal("§cErro ao entrar. Ticket devolvido."), false);
            t.printStackTrace();
            return false;
        }
    }

    private static Object createHiddenCreativeDungeonPortal(ServerPlayerEntity player) throws Exception {

        Class<?> portalClass = Class.forName("maxigregrze.cobblesafari.block.dungeon.DungeonPortalBlockEntity");

        var world = player.getServerWorld();

        // posição escondida
        BlockPos pos = new BlockPos(30000000, -60, 30000000);

        Block portalBlock = Registries.BLOCK.get(Identifier.of("cobblesafari", "creative_dungeon_portal"));
        BlockState state = portalBlock.getDefaultState();

        world.setBlockState(pos, state, Block.NOTIFY_ALL);

        // 🔥 REGISTRO CORRETO (ESSENCIAL)
        Class<?> portalSpawnManager = Class.forName("maxigregrze.cobblesafari.dungeon.PortalSpawnManager");

        boolean registered = (boolean) portalSpawnManager
                .getMethod("registerCreativePortal", net.minecraft.server.world.ServerWorld.class, BlockPos.class)
                .invoke(null, world, pos);

        if (!registered) {
            throw new IllegalStateException("Falha ao registrar portal no PortalSpawnManager");
        }

        Object portal = world.getBlockEntity(pos);

        portalClass.getMethod("setPortalId", UUID.class).invoke(portal, UUID.randomUUID());
        portalClass.getMethod("setOriginPos", BlockPos.class).invoke(portal, player.getBlockPos());
        portalClass.getMethod("setOriginDimension", net.minecraft.registry.RegistryKey.class)
                .invoke(portal, player.getWorld().getRegistryKey());

        portalClass.getMethod("setRandomDestinationMode", boolean.class).invoke(portal, true);
        portalClass.getMethod("setAutoRenewPortal", boolean.class).invoke(portal, true);

        portalClass.getMethod("setSpawnTick", long.class).invoke(portal, world.getTime());

        return portal;
    }

    private static boolean consumeItem(ServerPlayerEntity player, Identifier itemId, int amount) {
        Item target = Registries.ITEM.get(itemId);
        int remaining = amount;

        for (ItemStack stack : player.getInventory().main) {
            if (!stack.isEmpty() && stack.isOf(target)) {
                int remove = Math.min(remaining, stack.getCount());
                stack.decrement(remove);
                remaining -= remove;
                if (remaining <= 0) break;
            }
        }

        return remaining <= 0;
    }

    private static void giveItem(ServerPlayerEntity player, Identifier itemId, int amount) {
        Item item = Registries.ITEM.get(itemId);
        player.giveItemStack(new ItemStack(item, amount));
    }
}
