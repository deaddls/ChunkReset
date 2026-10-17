package dev.chunkreset;

import dev.chunkreset.command.RollbackCommand;
import dev.chunkreset.rollback.RollbackEngine;
import dev.chunkreset.storage.Database;
import dev.chunkreset.tracking.ChangeTracker;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * ChunkReset: records every block change on the server and lets operators roll any region back to how it
 * was at a chosen moment, while the server keeps running.
 */
public final class ChunkReset implements ModInitializer {
	public static final String MOD_ID = "chunkreset";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static ModConfig config = new ModConfig();
	private static RollbackEngine engine = new RollbackEngine(config);
	private static @Nullable Database database;
	private static long nextPurgeAt;
	private static int tickCounter;

	@Override
	public void onInitialize() {
		config = ModConfig.load();
		engine = new RollbackEngine(config);

		ServerLifecycleEvents.SERVER_STARTING.register(ChunkReset::onServerStarting);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			Database db = database;
			if (db != null) {
				db.scheduleFlush();
			}
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(ChunkReset::onServerStopped);
		ServerLevelEvents.UNLOAD.register((server, level) -> ChangeTracker.forgetLevel(level));
		ServerTickEvents.END_LEVEL_TICK.register(ChangeTracker::flushDirtyBlockEntities);
		ServerTickEvents.END_SERVER_TICK.register(ChunkReset::onEndTick);
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> RollbackCommand.register(dispatcher));

		LOGGER.info("ChunkReset loaded: block change history will be recorded once the server starts");
	}

	private static void onServerStarting(MinecraftServer server) {
		Path file = server.getWorldPath(LevelResource.ROOT).resolve(MOD_ID).resolve("changes.db");
		try {
			database = Database.open(file);
		} catch (Exception e) {
			database = null;
			LOGGER.error("ChunkReset could not open its database at {}. Block changes will NOT be recorded and /rollback is unavailable.", file, e);
			return;
		}
		ChangeTracker.start(database, config.trackBlockEntityContents, config.blockEntityMinIntervalMs, config.ignoredBlockEntityKeys);
		engine.reset();
		tickCounter = 0;
		nextPurgeAt = 0; // purge right away on the first tick
		LOGGER.info("Recording block changes to {}", file);
	}

	private static void onServerStopped(MinecraftServer server) {
		ChangeTracker.stop();
		engine.reset();
		Database db = database;
		database = null;
		if (db != null) {
			db.close();
			LOGGER.info("Change database closed");
		}
	}

	private static void onEndTick(MinecraftServer server) {
		Database db = database;
		if (db == null) {
			return;
		}
		engine.tick(server);
		if (++tickCounter % 20 == 0) {
			db.scheduleFlush();
		}
		if (config.retentionDays > 0) {
			long now = System.currentTimeMillis();
			if (now >= nextPurgeAt) {
				nextPurgeAt = now + TimeUnit.HOURS.toMillis(1);
				long before = now - TimeUnit.DAYS.toMillis(config.retentionDays);
				db.query(connection -> db.purge(connection, before)).whenComplete((deleted, error) -> {
					if (error != null) {
						LOGGER.error("Automatic purge failed", error);
					} else if (deleted > 0) {
						LOGGER.info("Purged {} recorded change(s) older than {} days", deleted, config.retentionDays);
					}
				});
			}
		}
	}

	public static ModConfig config() {
		return config;
	}

	public static RollbackEngine engine() {
		return engine;
	}

	public static @Nullable Database database() {
		return database;
	}
}
