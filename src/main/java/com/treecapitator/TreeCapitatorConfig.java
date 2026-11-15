package com.treecapitator;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Reads config/treecapitator.properties. Invalid values are replaced with the
 * documented defaults and reported in the server log.
 */
public final class TreeCapitatorConfig {
    public static final int DEFAULT_MAX_LOGS = 256;
    public static final boolean DEFAULT_REQUIRE_AXE = true;
    public static final boolean DEFAULT_SNEAK_DISABLES = true;
    public static final boolean DEFAULT_BREAK_LEAVES = false;
    public static final int DEFAULT_LEAF_RADIUS = 4;
    public static final int DEFAULT_MIN_LEAVES = 1;
    public static final boolean DEFAULT_INCLUDE_MUSHROOMS = true;
    public static final boolean DEFAULT_BREAK_MUSHROOM_CAPS = true;
    public static final boolean DEFAULT_ALLOW_DIAGONAL = true;
    public static final int DEFAULT_BREAK_DELAY_MS = 0;

    public static volatile int maxLogs = DEFAULT_MAX_LOGS;
    public static volatile boolean requireAxe = DEFAULT_REQUIRE_AXE;
    public static volatile boolean sneakDisables = DEFAULT_SNEAK_DISABLES;
    public static volatile boolean breakLeaves = DEFAULT_BREAK_LEAVES;
    public static volatile int leafRadius = DEFAULT_LEAF_RADIUS;
    public static volatile int minLeaves = DEFAULT_MIN_LEAVES;
    public static volatile boolean includeMushrooms = DEFAULT_INCLUDE_MUSHROOMS;
    public static volatile boolean breakMushroomCaps = DEFAULT_BREAK_MUSHROOM_CAPS;
    public static volatile boolean allowDiagonal = DEFAULT_ALLOW_DIAGONAL;
    public static volatile int breakDelayMs = DEFAULT_BREAK_DELAY_MS;

    private static final String FILE_NAME = "treecapitator.properties";
    private static final String DEFAULT_FILE = """
            # TreeCapitator server configuration.
            # Restart the server after changing these values.

            # Maximum number of connected trunk blocks that may be felled at once.
            max-logs=256

            # If true, only axes from the minecraft:axes item tag can trigger felling.
            require-axe=true

            # If true, sneaking while breaking a log breaks only that block.
            sneak-disables=true

            # If true, leaves connected to the felled trunk are removed without drops.
            # When false, normal leaves decay naturally after the trunk is gone.
            break-leaves=false

            # Maximum distance, in blocks, from a trunk block for leaf removal.
            leaf-radius=4

            # Minimum number of connected leaves or mushroom caps required to fell.
            min-leaves=1

            # If true, giant mushroom stems can be felled like trees.
            include-mushrooms=true

            # If true, remove the caps of a felled giant mushroom. Caps do not decay
            # naturally in vanilla, so this defaults to true.
            break-mushroom-caps=true

            # If true, diagonal (edge and corner) trunk connections are allowed.
            # This supports trees whose trunk blocks do not share a face.
            allow-diagonal=true

            # Milliseconds to wait between sequential trunk breaks during felling.
            break-delay-ms=0
            """;

    private TreeCapitatorConfig() {
    }

    public static void load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        Properties properties = new Properties();

        try {
            Files.createDirectories(path.getParent());
            if (!Files.exists(path)) {
                Files.writeString(path, DEFAULT_FILE, StandardCharsets.UTF_8);
                TreeCapitatorMod.LOGGER.info("Created default configuration at {}", path);
            } else {
                try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    properties.load(reader);
                }
            }
        } catch (IOException exception) {
            TreeCapitatorMod.LOGGER.error("Could not read or create {}", path, exception);
            applyDefaults();
            return;
        }

        maxLogs = readInt(properties, "max-logs", DEFAULT_MAX_LOGS, 1, 4096);
        requireAxe = readBoolean(properties, "require-axe", DEFAULT_REQUIRE_AXE);
        sneakDisables = readBoolean(properties, "sneak-disables", DEFAULT_SNEAK_DISABLES);
        breakLeaves = readBoolean(properties, "break-leaves", DEFAULT_BREAK_LEAVES);
        leafRadius = readInt(properties, "leaf-radius", DEFAULT_LEAF_RADIUS, 1, 16);
        minLeaves = readInt(properties, "min-leaves", DEFAULT_MIN_LEAVES, 0, 4096);
        includeMushrooms = readBoolean(properties, "include-mushrooms", DEFAULT_INCLUDE_MUSHROOMS);
        breakMushroomCaps = readBoolean(properties, "break-mushroom-caps", DEFAULT_BREAK_MUSHROOM_CAPS);
        allowDiagonal = readBoolean(properties, "allow-diagonal", DEFAULT_ALLOW_DIAGONAL);
        breakDelayMs = readInt(properties, "break-delay-ms", DEFAULT_BREAK_DELAY_MS, 0, 60000);
    }

    private static void applyDefaults() {
        maxLogs = DEFAULT_MAX_LOGS;
        requireAxe = DEFAULT_REQUIRE_AXE;
        sneakDisables = DEFAULT_SNEAK_DISABLES;
        breakLeaves = DEFAULT_BREAK_LEAVES;
        leafRadius = DEFAULT_LEAF_RADIUS;
        minLeaves = DEFAULT_MIN_LEAVES;
        includeMushrooms = DEFAULT_INCLUDE_MUSHROOMS;
        breakMushroomCaps = DEFAULT_BREAK_MUSHROOM_CAPS;
        allowDiagonal = DEFAULT_ALLOW_DIAGONAL;
        breakDelayMs = DEFAULT_BREAK_DELAY_MS;
    }

    private static int readInt(Properties properties, String key, int defaultValue, int minimum, int maximum) {
        String raw = properties.getProperty(key);
        if (raw == null) {
            return defaultValue;
        }

        try {
            int value = Integer.parseInt(raw.trim());
            if (value < minimum || value > maximum) {
                warnInvalid(key, raw, defaultValue, "expected an integer between " + minimum + " and " + maximum);
                return defaultValue;
            }
            return value;
        } catch (NumberFormatException exception) {
            warnInvalid(key, raw, defaultValue, "expected an integer");
            return defaultValue;
        }
    }

    private static boolean readBoolean(Properties properties, String key, boolean defaultValue) {
        String raw = properties.getProperty(key);
        if (raw == null) {
            return defaultValue;
        }

        if ("true".equalsIgnoreCase(raw.trim())) {
            return true;
        }
        if ("false".equalsIgnoreCase(raw.trim())) {
            return false;
        }

        warnInvalid(key, raw, defaultValue, "expected true or false");
        return defaultValue;
    }

    private static void warnInvalid(String key, String raw, Object defaultValue, String reason) {
        TreeCapitatorMod.LOGGER.warn(
                "Invalid configuration value for '{}': '{}' ({}); using default {}",
                key, raw, reason, defaultValue);
    }
}
