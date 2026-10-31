package dev.parle;

import io.netty.buffer.ByteBuf;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.EntitySleepEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityCombatEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Server side: watches what players do and tells their client which moment just happened
 * ("kill:zombie", "mine:diamond_ore", "biome:desert", …). The client picks a matching challenge.
 * Also pays out XP for solved challenges.
 */
public final class Parle implements ModInitializer {
    public static final String MOD = "parle";

    public record Trigger(String key) implements CustomPayload {
        public static final Id<Trigger> ID = new Id<>(Identifier.of(MOD, "trigger"));
        public static final PacketCodec<ByteBuf, Trigger> CODEC = PacketCodecs.STRING.xmap(Trigger::new, Trigger::key);
        @Override public Id<Trigger> getId() { return ID; }
    }

    public record Reward(int xp) implements CustomPayload {
        public static final Id<Reward> ID = new Id<>(Identifier.of(MOD, "reward"));
        public static final PacketCodec<ByteBuf, Reward> CODEC = PacketCodecs.VAR_INT.xmap(Reward::new, Reward::xp);
        @Override public Id<Reward> getId() { return ID; }
    }

    private static final class Watch { String biome; boolean low; long day = -1, lastReward = -100; }
    private static final Map<UUID, Watch> WATCH = new HashMap<>();

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.playS2C().register(Trigger.ID, Trigger.CODEC);
        PayloadTypeRegistry.playC2S().register(Reward.ID, Reward.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(Reward.ID, (r, ctx) -> {
            ServerPlayerEntity p = ctx.player();
            Watch w = watch(p);
            long now = p.getServerWorld().getTime();
            if (now - w.lastReward < 20) return; // one reward per second at most
            w.lastReward = now;
            p.addExperience(Math.clamp(r.xp(), 0, 20));
        });

        ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.register((world, killer, killed) -> {
            if (killer instanceof ServerPlayerEntity p) send(p, "kill:" + Registries.ENTITY_TYPE.getId(killed.getType()).getPath());
        });
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, be) -> {
            String b = Registries.BLOCK.getId(state.getBlock()).getPath().replace("deepslate_", "");
            if (player instanceof ServerPlayerEntity p) send(p, "mine:" + (b.endsWith("_log") || b.endsWith("_stem") ? "log" : b));
        });
        EntitySleepEvents.START_SLEEPING.register((e, pos) -> { if (e instanceof ServerPlayerEntity p) send(p, "sleep"); });
        ServerLivingEntityEvents.AFTER_DEATH.register((e, src) -> { if (e instanceof ServerPlayerEntity p) send(p, "death"); });
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((p, from, to) -> send(p, "dim:" + to.getRegistryKey().getValue().getPath()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> WATCH.remove(handler.player.getUuid()));

        // Every 2 s: new biome, low health, and a new morning.
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTicks() % 40 != 0) return;
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                Watch w = watch(p);
                ServerWorld world = p.getServerWorld();
                String biome = world.getBiome(p.getBlockPos()).getKey().map(k -> k.getValue().getPath()).orElse("");
                if (w.biome != null && !biome.equals(w.biome)) send(p, "biome:" + biome);
                w.biome = biome;
                boolean low = p.isAlive() && p.getHealth() <= 6;
                if (low && !w.low) send(p, "lowhp");
                w.low = low;
                long day = world.getTimeOfDay() / 24000;
                if (world.getTimeOfDay() % 24000 < 1000 && day != w.day && w.day >= 0) send(p, "dawn");
                if (world.getTimeOfDay() % 24000 < 1000 || w.day < 0) w.day = day;
            }
        });
    }

    private static Watch watch(ServerPlayerEntity p) { return WATCH.computeIfAbsent(p.getUuid(), u -> new Watch()); }

    private static void send(ServerPlayerEntity p, String key) {
        if (ServerPlayNetworking.canSend(p, Trigger.ID)) ServerPlayNetworking.send(p, new Trigger(key));
    }
}
