package dev.parle.client;

import dev.parle.Parle;
import dev.parle.core.Content;
import dev.parle.core.Content.Challenge;
import dev.parle.core.Profile;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Random;

/** Client side: owns the content and the learner profile, and opens challenges as gameplay triggers them. */
public final class ParleClient implements ClientModInitializer {
    static final Logger LOG = LoggerFactory.getLogger(Parle.MOD);
    static final Content CONTENT = new Content();
    static final Random RND = new Random();
    static final Deque<Pending> QUEUE = new ArrayDeque<>();
    static Profile profile;
    static KeyBinding openKey, notebookKey;
    private static long lastQueued;

    record Pending(Challenge c, String source, long at) {}

    @Override
    public void onInitializeClient() {
        try (Reader r = new InputStreamReader(ParleClient.class.getResourceAsStream("/assets/parle/content.json"), StandardCharsets.UTF_8)) {
            CONTENT.load(r);
        } catch (Exception e) { LOG.error("Could not load built-in French content", e); }

        // Extra hand-written packs: config/parle/packs/*.json (same format as the built-in content.json).
        Path dir = FabricLoader.getInstance().getConfigDir().resolve(Parle.MOD);
        try {
            Files.createDirectories(dir.resolve("packs"));
            try (var packs = Files.newDirectoryStream(dir.resolve("packs"), "*.json")) {
                for (Path p : packs) try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) { CONTENT.load(r); }
                catch (Exception e) { LOG.warn("Skipping broken pack {}", p, e); }
            }
        } catch (Exception e) { LOG.warn("Could not read packs folder", e); }
        profile = Profile.load(dir.resolve("profile.json"));
        LOG.info("Parle! loaded {} challenges in {} topics", CONTENT.challenges.size(), CONTENT.topics.size());

        openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.parle.open", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_J, "key.categories.parle"));
        notebookKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.parle.notebook", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_N, "key.categories.parle"));

        ClientPlayNetworking.registerGlobalReceiver(Parle.Trigger.ID, (t, ctx) -> ctx.client().execute(() -> onTrigger(t.key())));
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            long now = System.currentTimeMillis();
            QUEUE.removeIf(p -> now - p.at > 180_000); // "you just did X" goes stale after 3 minutes in a menu
            // Gameplay challenges are not optional: they open as soon as no other screen is in the way.
            if (!QUEUE.isEmpty() && mc.currentScreen == null && mc.player != null && mc.player.isAlive()) openNext(mc);
            while (openKey.wasPressed()) openNext(mc);
            while (notebookKey.wasPressed()) mc.setScreen(new NotebookScreen());
        });
    }

    private static void onTrigger(String key) {
        long now = System.currentTimeMillis();
        if (QUEUE.size() >= 3 || now - lastQueued < profile.cooldown * 1000L) return;
        Challenge c = CONTENT.pick(key, null, profile, RND);
        if (c == null || QUEUE.stream().anyMatch(p -> p.c == c)) return;
        QUEUE.add(new Pending(c, key, now));
        lastQueued = now;
    }

    /** Opens the oldest waiting challenge, or a review challenge if nothing is waiting. */
    static void openNext(MinecraftClient mc) {
        Pending p = QUEUE.poll();
        Challenge c = p != null ? p.c : CONTENT.pick(null, null, profile, RND);
        if (c != null) mc.setScreen(new ChallengeScreen(c, p != null ? p.source : "review"));
    }

    static String label(String source) {
        return switch (source.substring(0, source.indexOf(':') < 0 ? source.length() : source.indexOf(':'))) {
            case "kill" -> "Combat"; case "mine" -> "Minage"; case "sleep" -> "Au lit";
            case "death" -> "Aïe…"; case "biome" -> "Exploration"; case "dim" -> "Voyage";
            case "lowhp" -> "Danger"; case "dawn" -> "Le matin"; default -> "Révision";
        };
    }
}
