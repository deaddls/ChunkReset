package dev.chunkreset;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * User-editable settings, stored as JSON in {@code config/chunkreset.json}.
 * Missing keys fall back to the defaults below; the file is rewritten with all keys on load.
 */
public final class ModConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	/** How long change history is kept. {@code 0} keeps it forever. */
	public int retentionDays = 30;

	/** Maximum number of block positions a rollback applies per server tick. Lower values mean less lag spikes, higher values mean faster rollbacks. */
	public int blocksPerTick = 4096;

	/** Hard cap on how many chunks a single rollback region may span (protects against accidental world-sized rollbacks). */
	public int maxRegionChunks = 16384;

	/** Also track changes to block entity contents (chests, barrels, signs, furnaces, ...) that happen without the block itself changing. */
	public boolean trackBlockEntityContents = true;

	/**
	 * Minimum time in milliseconds between two recorded content changes of the same block entity. Keeps block
	 * entities that change every tick (hoppers, spawners, ...) from flooding the log. Rollbacks stay exact for
	 * the block itself; block entity contents may be off by at most this interval.
	 */
	public int blockEntityMinIntervalMs = 1000;

	/**
	 * Top-level NBT keys that are ignored when deciding whether block entity contents changed, and that are not
	 * restored. These are transient counters (furnace burn time, brewing progress, ...) that change every tick.
	 */
	public List<String> ignoredBlockEntityKeys = new ArrayList<>(List.of(
			"cooking_time_spent", "cooking_total_time", "lit_time_remaining", "lit_total_time", "RecipesUsed",
			"CookingTimes", "CookingTotalTimes",
			"BrewTime", "total_brew_time", "Fuel", "total_fuel",
			"Delay", "TransferCooldown", "Age", "ticks_since_song_started",
			"last_vibration_frequency", "listener", "warning_level"));

	/** Load chunks that are not currently loaded when a rollback needs to touch them. If false, blocks in unloaded chunks are skipped and reported. */
	public boolean loadUnloadedChunks = true;

	public static ModConfig load() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve(ChunkReset.MOD_ID + ".json");
		ModConfig config = new ModConfig();
		if (Files.exists(path)) {
			try {
				String json = Files.readString(path, StandardCharsets.UTF_8);
				ModConfig loaded = GSON.fromJson(json, ModConfig.class);
				if (loaded != null) {
					config = loaded;
				}
			} catch (IOException | JsonParseException e) {
				ChunkReset.LOGGER.error("Could not read {} - using defaults", path, e);
			}
		}
		config.sanitize();
		try {
			Files.createDirectories(path.getParent());
			Files.writeString(path, GSON.toJson(config), StandardCharsets.UTF_8);
		} catch (IOException e) {
			ChunkReset.LOGGER.warn("Could not write {}", path, e);
		}
		return config;
	}

	private void sanitize() {
		if (retentionDays < 0) retentionDays = 0;
		if (blocksPerTick < 1) blocksPerTick = 1;
		if (maxRegionChunks < 1) maxRegionChunks = 1;
		if (blockEntityMinIntervalMs < 0) blockEntityMinIntervalMs = 0;
		if (ignoredBlockEntityKeys == null) ignoredBlockEntityKeys = new ArrayList<>();
	}
}
