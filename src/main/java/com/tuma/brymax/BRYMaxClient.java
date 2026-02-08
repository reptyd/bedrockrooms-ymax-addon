package com.tuma.brymax;

import it.unimi.dsi.fastutil.longs.LongCollection;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;
import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;

public class BRYMaxClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("BRYMax");
    private static KeyBinding scanKey;
    private static int pad = 2;

    @Override
    public void onInitializeClient() {
        scanKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.brymax.scan",
                GLFW.GLFW_KEY_K,
                "category.brymax"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (scanKey.wasPressed()) {
                scanNearestRoom(client);
            }
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(literal("brymaxpad")
                    .then(argument("value", integer(0, 64))
                            .executes(ctx -> {
                                pad = getInteger(ctx, "value");
                                ctx.getSource().sendFeedback(Text.literal("[BR-Y] Pad set to " + pad));
                                return 1;
                            }))
                    .executes(ctx -> {
                        ctx.getSource().sendFeedback(Text.literal("[BR-Y] Current pad = " + pad));
                        return 1;
                    }));
        });
    }

    private static void scanNearestRoom(MinecraftClient client) {
        if (client == null || client.player == null || client.world == null) {
            return;
        }
        RoomInfo nearest = findNearestRoom(client.player);
        if (nearest == null || nearest.cells == null || nearest.cells.isEmpty()) {
            client.player.sendMessage(Text.literal("[BR-Y] No nearby BedrockRooms base found."), false);
            return;
        }
        scanTopLayer(client.world, client.player, nearest);
    }

    private static void scanTopLayer(ClientWorld world, ClientPlayerEntity player, RoomInfo room) {
        LongCollection cells = room.cells;
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        LongIterator it = cells.iterator();
        while (it.hasNext()) {
            long l = it.nextLong();
            int x = BlockPos.unpackLongX(l);
            int z = BlockPos.unpackLongZ(l);
            if (x < minX) {
                minX = x;
            }
            if (z < minZ) {
                minZ = z;
            }
            if (x > maxX) {
                maxX = x;
            }
            if (z > maxZ) {
                maxZ = z;
            }
        }
        int yMax = getBedrockRoomsYMax();
        if (yMax == Integer.MIN_VALUE) {
            player.sendMessage(Text.literal("[BR-Y] Room data is empty."), false);
            return;
        }

        int x0 = minX - pad;
        int x1 = maxX + pad;
        int z0 = minZ - pad;
        int z1 = maxZ + pad;

        int total = 0;
        int bedrock = 0;
        int air = 0;
        int other = 0;
        int skipped = 0;
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int x = x0; x <= x1; ++x) {
            for (int z = z0; z <= z1; ++z) {
                if (!world.getChunkManager().isChunkLoaded(x >> 4, z >> 4)) {
                    skipped++;
                    continue;
                }
                total++;
                pos.set(x, yMax, z);
                BlockState st = world.getBlockState(pos);
                if (st.isOf(Blocks.BEDROCK)) {
                    bedrock++;
                } else if (st.isAir() || st.getFluidState().isIn(FluidTags.LAVA)) {
                    air++;
                } else {
                    other++;
                }
            }
        }

        if (total == 0) {
            player.sendMessage(Text.literal("[BR-Y] No loaded blocks on Y max for this base."), false);
            return;
        }

        double bedrockPct = bedrock * 100.0 / total;
        double airPct = air * 100.0 / total;
        double otherPct = other * 100.0 / total;
        int width = x1 - x0 + 1;
        int depth = z1 - z0 + 1;
        String msg = String.format(
                "[BR-Y] Room#%d yMax=%d area=%dx%d loaded=%d bedrock=%.1f%% air=%.1f%% other=%.1f%%",
                room.roomId, yMax, width, depth, total, bedrockPct, airPct, otherPct
        );
        if (skipped > 0) {
            msg += " (skipped " + skipped + " unloaded)";
        }
        player.sendMessage(Text.literal(msg), false);
    }

    private static RoomInfo findNearestRoom(ClientPlayerEntity player) {
        Iterable<?> rooms = getRoomsIterable();
        if (rooms == null) {
            player.sendMessage(Text.literal("[BR-Y] BedrockRooms not available."), false);
            return null;
        }
        Vec3d pos = player.getPos();
        RoomInfo nearest = null;
        double bestDistSq = Double.MAX_VALUE;
        for (Object r : rooms) {
            RoomInfo info = toRoomInfo(r);
            if (info == null) {
                continue;
            }
            double dx = info.centerX - pos.x;
            double dy = info.centerY - pos.y;
            double dz = info.centerZ - pos.z;
            double ds = dx * dx + dy * dy + dz * dz;
            if (ds < bestDistSq) {
                bestDistSq = ds;
                nearest = info;
            }
        }
        return nearest;
    }

    private static int getBedrockRoomsYMax() {
        try {
            Class<?> cfg = Class.forName("com.bedrockrooms.config.BRConfig");
            Object inst = cfg.getField("INSTANCE").get(null);
            return ((Number)cfg.getField("yMax").get(inst)).intValue();
        } catch (Throwable t) {
            LOGGER.warn("Failed to read BedrockRooms yMax.", t);
            return Integer.MIN_VALUE;
        }
    }

    private static Iterable<?> getRoomsIterable() {
        try {
            Class<?> brScanner = Class.forName("com.bedrockrooms.scan.BRScanner");
            Method getRooms = brScanner.getMethod("getRooms");
            Object roomsMap = getRooms.invoke(null);
            Method values = roomsMap.getClass().getMethod("values");
            Object valuesObj = values.invoke(roomsMap);
            if (valuesObj instanceof Iterable) {
                return (Iterable<?>) valuesObj;
            }
        } catch (Throwable t) {
            LOGGER.warn("Failed to access BedrockRooms rooms.", t);
        }
        return null;
    }

    private static RoomInfo toRoomInfo(Object room) {
        if (room == null) {
            return null;
        }
        try {
            Class<?> cls = room.getClass();
            Field cellsF = cls.getField("roomCells");
            Object cellsObj = cellsF.get(room);
            if (!(cellsObj instanceof LongCollection)) {
                return null;
            }
            LongCollection cells = (LongCollection) cellsObj;
            if (cells.isEmpty()) {
                return null;
            }
            long roomId = ((Number)cls.getField("roomId").get(room)).longValue();
            double cx = ((Number)cls.getField("centerX").get(room)).doubleValue();
            double cy = ((Number)cls.getField("centerY").get(room)).doubleValue();
            double cz = ((Number)cls.getField("centerZ").get(room)).doubleValue();
            return new RoomInfo(roomId, cx, cy, cz, cells);
        } catch (Throwable t) {
            LOGGER.warn("Failed to read BedrockRooms room info.", t);
            return null;
        }
    }

    private static final class RoomInfo {
        private final long roomId;
        private final double centerX;
        private final double centerY;
        private final double centerZ;
        private final LongCollection cells;

        private RoomInfo(long roomId, double centerX, double centerY, double centerZ, LongCollection cells) {
            this.roomId = roomId;
            this.centerX = centerX;
            this.centerY = centerY;
            this.centerZ = centerZ;
            this.cells = cells;
        }
    }
}
