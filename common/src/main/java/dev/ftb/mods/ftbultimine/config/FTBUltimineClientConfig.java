package dev.ftb.mods.ftbultimine.config;

import dev.ftb.mods.ftblibrary.snbt.config.IntValue;
import dev.ftb.mods.ftblibrary.snbt.config.SNBTConfig;

import static dev.ftb.mods.ftbultimine.FTBUltimine.MOD_ID;
import static dev.ftb.mods.ftbultimine.utils.IOUtil.LOCAL_DIR;
import static dev.ftb.mods.ftbultimine.utils.IOUtil.loadDefaulted;

/**
 * @author LatvianModder
 */
public interface FTBUltimineClientConfig {

	SNBTConfig CONFIG = SNBTConfig.create(MOD_ID + "-client")
			.comment("Client-specific configuration for FTB Ultimine",
					"This file is meant for users to control Ultimine's clientside behaviour and rendering.",
					"Changes to this file require you to reload the world");

	IntValue xOffset = CONFIG.getInt("x_offset", -1)
			.comment("Manual x offset of FTB Ultimine overlay, required for some modpacks");

	IntValue renderOutline = CONFIG.getInt("render_outline", 256)
			.range(0, Integer.MAX_VALUE)
			.comment("Maximum number of blocks the white outline should be rendered for",
					"Keep in mind this may get *very* laggy for large amounts of blocks!");

	static void load() {
		loadDefaulted(CONFIG, LOCAL_DIR);
	}
}
