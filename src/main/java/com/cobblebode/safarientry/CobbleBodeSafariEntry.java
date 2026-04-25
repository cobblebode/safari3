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

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public class CobbleBodeSafariEntry implements ModInitializer {
    private static final Identifier TICKET_ID = Identifier.of("cobblesafari", "ticket_dungeon");
    private static final int DUNGEON_SECONDS = 1800;
    private static final int DUNGEON_TICKS = DUNGEON_SECONDS * 20;

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
                                                    ctx.getSource().sendFeedback(() -> Text.literal("Ticket Dungeon de " + player.getName().getString() + ": " + (has ? "SIM" : "NÃO")), false);
                                                    return has ? 1 : 0;
                                                })
                                        )
                                )
                        )
        );
    }

    /**
     * Direct mode:
     * - no Hoopa portal
     * - no portal spawn command
     * - consumes cobblesafari:ticket_dungeon
     * - creates a temporary DungeonPortalBlockEntity only as a data carrier
     * - lets CobbleSafari generate/prepare/teleport using its own DungeonTeleportHandler
     */
    private static boolean enterDirect(ServerPlayerEntity player) {
        if (!consumeItem(player, TICKET_ID, 1)) {
            player.sendMessage(Text.literal("§cVocê precisa de um Ticket Dungeon para entrar na Mina Safari."), false);
            return false;
        }

        try {
            Object portal = createTemporaryRandomDungeonPortal(player);

            Class<?> handlerClass = Class.forName("maxigregrze.cobblesafari.dungeon.DungeonTeleportHandler");
            Class<?> portalClass = Class.forName("maxigregrze.cobblesafari.block.dungeon.DungeonPortalBlockEntity");
            Class<?> validationClass = Class.forName("maxigregrze.cobblesafari.dungeon.DungeonTeleportHandler$DungeonValidationResult");
            Class<?> prepClass = Class.forName("maxigregrze.cobblesafari.dungeon.DungeonTeleportHandler$DungeonPrepResult");

            Method validate = handlerClass.getMethod("validateDungeonEntry", ServerPlayerEntity.class, portalClass);
            Object validation = validate.invoke(null, player, portal);
            if (validation == null) {
                giveItem(player, TICKET_ID, 1);
                player.sendMessage(Text.literal("§cNão foi possível validar a entrada da Mina Safari. O ticket foi devolvido."), false);
                return false;
            }

            Object forcedValidation = forceValidationTimer(validation, validationClass);

            Method generateAndPrepare = handlerClass.getMethod("generateAndPrepareDungeon", ServerPlayerEntity.class, portalClass, validationClass);
            Object prep = generateAndPrepare.invoke(null, player, portal, forcedValidation);
            if (prep == null) {
                giveItem(player, TICKET_ID, 1);
                player.sendMessage(Text.literal("§cNão foi possível gerar a Mina Safari. O ticket foi devolvido."), false);
                return false;
            }

            Object forcedPrep = forcePrepTimer(prep, prepClass);

            Method execute = handlerClass.getMethod("executeDungeonTeleport", ServerPlayerEntity.class, portalClass, prepClass);
            execute.invoke(null, player, portal, forcedPrep);

            // Force timer again after teleport, because CobbleSafari default config is 900s.
            String dimensionId = (String) prepClass.getMethod("dimensionId").invoke(forcedPrep);
            setDungeonTimer(player, dimensionId, DUNGEON_TICKS);
            markEntryFeePaid(player, dimensionId);

            player.sendMessage(Text.literal("§aTicket consumido! Você entrou na Mina Safari por 30 minutos."), false);
            return true;
        } catch (Throwable t) {
            giveItem(player, TICKET_ID, 1);
            player.sendMessage(Text.literal("§cErro ao abrir a Mina Safari. O ticket foi devolvido."), false);
            player.sendMessage(Text.literal("§7Debug: " + t.getClass().getSimpleName() + " - " + safeMsg(t)), false);
            return false;
        }
    }

    private static Object createTemporaryRandomDungeonPortal(ServerPlayerEntity player) throws Exception {
        Class<?> portalClass = Class.forName("maxigregrze.cobblesafari.block.dungeon.DungeonPortalBlockEntity");

        BlockPos pos = player.getBlockPos();

        // IMPORTANT:
        // DungeonPortalBlockEntity validates that it was created with the correct block state.
        // Using the player's current block state, usually air, throws:
        // Invalid block entity cobblesafari:dungeon_portal // Block{minecraft:air}
        Block portalBlock = Registries.BLOCK.get(Identifier.of("cobblesafari", "dungeon_portal"));
        BlockState state = portalBlock.getDefaultState();

        Constructor<?> constructor = portalClass.getConstructor(BlockPos.class, BlockState.class);
        Object portal = constructor.newInstance(pos, state);

        portalClass.getMethod("setPortalId", UUID.class).invoke(portal, UUID.randomUUID());
        portalClass.getMethod("setOriginPos", BlockPos.class).invoke(portal, pos);
        portalClass.getMethod("setOriginDimension", net.minecraft.registry.RegistryKey.class).invoke(portal, player.getWorld().getRegistryKey());
        portalClass.getMethod("setRandomDestinationMode", boolean.class).invoke(portal, true);
        portalClass.getMethod("setDungeonDimensionId", String.class).invoke(portal, (Object) null);
        portalClass.getMethod("setSpawnTick", long.class).invoke(portal, player.getServerWorld().getTime());

        return portal;
    }

    private static Object forceValidationTimer(Object validation, Class<?> validationClass) throws Exception {
        Object config = validationClass.getMethod("config").invoke(validation);
        Object dungeonLevel = validationClass.getMethod("dungeonLevel").invoke(validation);
        Object dimensionId = validationClass.getMethod("dimensionId").invoke(validation);
        Object isReEntry = validationClass.getMethod("isReEntry").invoke(validation);
        Object playerOriginPos = validationClass.getMethod("playerOriginPos").invoke(validation);
        Object playerOriginDimension = validationClass.getMethod("playerOriginDimension").invoke(validation);

        Constructor<?> c = validationClass.getConstructor(
                Class.forName("maxigregrze.cobblesafari.dungeon.DungeonConfig"),
                net.minecraft.server.world.ServerWorld.class,
                String.class,
                boolean.class,
                int.class,
                BlockPos.class,
                net.minecraft.registry.RegistryKey.class
        );

        return c.newInstance(config, dungeonLevel, dimensionId, isReEntry, DUNGEON_TICKS, playerOriginPos, playerOriginDimension);
    }

    private static Object forcePrepTimer(Object prep, Class<?> prepClass) throws Exception {
        Object dungeonLevel = prepClass.getMethod("dungeonLevel").invoke(prep);
        Object playerSpawnPos = prepClass.getMethod("playerSpawnPos").invoke(prep);
        Object playerYaw = prepClass.getMethod("playerYaw").invoke(prep);
        Object dimensionId = prepClass.getMethod("dimensionId").invoke(prep);
        Object isReEntry = prepClass.getMethod("isReEntry").invoke(prep);
        Object playerOriginPos = prepClass.getMethod("playerOriginPos").invoke(prep);
        Object playerOriginDimension = prepClass.getMethod("playerOriginDimension").invoke(prep);

        Constructor<?> c = prepClass.getConstructor(
                net.minecraft.server.world.ServerWorld.class,
                BlockPos.class,
                float.class,
                String.class,
                boolean.class,
                int.class,
                BlockPos.class,
                net.minecraft.registry.RegistryKey.class
        );

        return c.newInstance(dungeonLevel, playerSpawnPos, playerYaw, dimensionId, isReEntry, DUNGEON_TICKS, playerOriginPos, playerOriginDimension);
    }

    private static void setDungeonTimer(ServerPlayerEntity player, String dimensionId, int ticks) {
        try {
            Class<?> timerManager = Class.forName("maxigregrze.cobblesafari.manager.TimerManager");
            Method getOrCreateData = timerManager.getMethod("getOrCreateData", ServerPlayerEntity.class, String.class);
            Object data = getOrCreateData.invoke(null, player, dimensionId);

            data.getClass().getMethod("setRemainingTicks", int.class).invoke(data, ticks);
            data.getClass().getMethod("setActive", boolean.class).invoke(data, true);
            try {
                data.getClass().getMethod("setNeedsEvacuation", boolean.class).invoke(data, false);
            } catch (NoSuchMethodException ignored) {}

            try {
                timerManager.getMethod("savePlayerData", ServerPlayerEntity.class, data.getClass()).invoke(null, player, data);
            } catch (Throwable ignored) {}

            try {
                timerManager.getMethod("syncToClient", ServerPlayerEntity.class, data.getClass()).invoke(null, player, data);
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    private static void markEntryFeePaid(ServerPlayerEntity player, String dimensionId) {
        try {
            Class<?> timerManager = Class.forName("maxigregrze.cobblesafari.manager.TimerManager");
            Method getOrCreateData = timerManager.getMethod("getOrCreateData", ServerPlayerEntity.class, String.class);
            Object data = getOrCreateData.invoke(null, player, dimensionId);
            data.getClass().getMethod("markEntryFeePaidToday").invoke(data);
        } catch (Throwable ignored) {}
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
        if (msg == null && t.getCause() != null) msg = t.getCause().getMessage();
        return msg == null ? "sem mensagem" : msg;
    }
}
