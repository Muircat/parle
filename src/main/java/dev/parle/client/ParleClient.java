package dev.parle.client;

import dev.parle.Parle;
import dev.parle.core.Content;
import dev.parle.core.Content.Challenge;
import dev.parle.core.Profile;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

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

        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (!world.isClient || !(entity instanceof VillagerEntity v) || v.isBaby()) return ActionResult.PASS;
            String job = Registries.VILLAGER_PROFESSION.getId(v.getVillagerData().getProfession()).getPath();
            if (job.equals("none") || job.equals("nitwit") || System.currentTimeMillis() - UNLOCKED.getOrDefault(v.getUuid(), 0L) < 300_000)
                return ActionResult.PASS;
            MinecraftClient mc = MinecraftClient.getInstance();
            if (hand == Hand.MAIN_HAND && !(mc.currentScreen instanceof ChallengeScreen)) talk(mc, v, job);
            return ActionResult.FAIL; // no trading until the exchange is done
        });

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

    // Each job has a register (vous or tu) and a French title; each villager gets a name and voice pitch from its UUID.
    private static final Set<String> FORMAL = Set.of("librarian", "cleric", "cartographer", "armorer", "weaponsmith", "toolsmith");
    private static final Map<String, String> JOBS = Map.ofEntries(Map.entry("armorer", "armurier"), Map.entry("butcher", "boucher"),
            Map.entry("cartographer", "cartographe"), Map.entry("cleric", "prêtre"), Map.entry("farmer", "fermier"),
            Map.entry("fisherman", "pêcheur"), Map.entry("fletcher", "archer"), Map.entry("leatherworker", "tanneur"),
            Map.entry("librarian", "bibliothécaire"), Map.entry("mason", "maçon"), Map.entry("shepherd", "berger"),
            Map.entry("toolsmith", "forgeron"), Map.entry("weaponsmith", "fabricant d'armes"));
    private static final String[] NAMES = {"Mathilde", "Jacques", "Élodie", "Bernard", "Margaux", "Lucien", "Camille", "Gaston",
            "Héloïse", "Thibault", "Odette", "Rémi", "Solène", "Marcel", "Aurélie", "Hugo"};
    private static final Map<UUID, Long> UNLOCKED = new HashMap<>();

    /** A two-step exchange (greeting, then the actual request); getting both right opens the trade screen. */
    private static void talk(MinecraftClient mc, VillagerEntity v, String job) {
        int h = v.getUuid().hashCode();
        String name = v.hasCustomName() ? v.getCustomName().getString() : NAMES[Math.floorMod(h, NAMES.length)];
        String who = name + ", " + JOBS.getOrDefault(job, job);
        float pitch = 0.75f + Math.floorMod(h >> 4, 11) * 0.06f;
        Challenge greet = CONTENT.pick("greet:" + (FORMAL.contains(job) ? "vous" : "tu"), null, profile, RND);
        Challenge ask = CONTENT.pick("trade:" + job, null, profile, RND);
        if (ask == null) ask = CONTENT.pick("trade:any", null, profile, RND);
        Challenge second = ask;
        Runnable open = () -> {
            UNLOCKED.put(v.getUuid(), System.currentTimeMillis());
            mc.interactionManager.interactEntity(mc.player, v, Hand.MAIN_HAND);
        };
        mc.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.ENTITY_VILLAGER_AMBIENT, pitch, 1f));
        Runnable step2 = second == null ? open : () -> mc.setScreen(new ChallengeScreen(second, "trade:" + job, who, pitch, open));
        if (greet != null) mc.setScreen(new ChallengeScreen(greet, "trade:" + job, who, pitch, step2));
        else step2.run();
    }

    static String label(String source) {
        return switch (source.substring(0, source.indexOf(':') < 0 ? source.length() : source.indexOf(':'))) {
            case "kill" -> "Combat"; case "mine" -> "Minage"; case "sleep" -> "Au lit";
            case "death" -> "Aïe…"; case "biome" -> "Exploration"; case "dim" -> "Voyage";
            case "lowhp" -> "Danger"; case "dawn" -> "Le matin"; case "trade" -> "Échange"; default -> "Révision";
        };
    }
}
