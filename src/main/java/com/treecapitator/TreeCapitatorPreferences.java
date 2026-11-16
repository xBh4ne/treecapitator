package com.treecapitator;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Per-player opt-out list stored inside the world folder.
 *
 * <p>The world file is loaded lazily once per server instance. The hot path
 * only compares the current server reference and does one hash lookup, so
 * breaking a block does not touch the filesystem or allocate a {@link Path}.</p>
 */
public final class TreeCapitatorPreferences {
    private static final String FILE_NAME = "treecapitator_disabled.txt";
    private static final Set<UUID> DISABLED = new HashSet<>();

    private static MinecraftServer loadedServer;
    private static Path loadedPath;

    private TreeCapitatorPreferences() {
    }

    public static boolean isEnabled(ServerPlayer player) {
        ensureLoaded(player.level().getServer());
        return !DISABLED.contains(player.getUUID());
    }

    public static void setEnabled(ServerPlayer player, boolean enabled) {
        ensureLoaded(player.level().getServer());

        if (enabled) {
            DISABLED.remove(player.getUUID());
        } else {
            DISABLED.add(player.getUUID());
        }

        save();
    }

    private static void ensureLoaded(MinecraftServer server) {
        if (server == loadedServer) {
            return;
        }

        loadedServer = server;
        Path path = server.getWorldPath(LevelResource.ROOT).resolve(FILE_NAME);
        loadedPath = path;
        DISABLED.clear();

        if (!Files.exists(path)) {
            return;
        }

        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String value = line.trim();
                if (value.isEmpty()) {
                    continue;
                }
                try {
                    DISABLED.add(UUID.fromString(value));
                } catch (IllegalArgumentException exception) {
                    TreeCapitatorMod.LOGGER.warn("Invalid UUID in {}: {}", path, value);
                }
            }
        } catch (IOException exception) {
            TreeCapitatorMod.LOGGER.error("Could not read {}", path, exception);
        }
    }

    private static void save() {
        if (loadedPath == null) {
            return;
        }

        try {
            Files.createDirectories(loadedPath.getParent());
            List<String> lines = DISABLED.stream().map(UUID::toString).sorted().toList();
            Files.write(loadedPath, lines, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            TreeCapitatorMod.LOGGER.error("Could not save {}", loadedPath, exception);
        }
    }
}
