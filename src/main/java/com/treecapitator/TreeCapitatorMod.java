package com.treecapitator;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TreeCapitatorMod implements ModInitializer {
    public static final String MOD_ID = "treecapitator";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        TreeCapitatorConfig.load();
        TreeCapitatorCommand.register();
        TreeCapitatorHandler.register();
        PlayerBlockBreakEvents.AFTER.register(TreeCapitatorHandler::afterBlockBreak);
        LOGGER.info("TreeCapitator 1.0.0 loaded.");
    }
}
