package dev.chunkreset.rollback;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.chunkreset.ChunkReset;
import dev.chunkreset.ModConfig;
import dev.chunkreset.storage.Database;
import dev.chunkreset.tracking.ChangeTracker;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.TagValueInput;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Plans and applies rollbacks. The plan (which positions need which historic state) is computed on the
 * database thread; the blocks are then placed on the server thread, spread over several ticks so that a
 * large rollback does not freeze the server.
 */
public final class RollbackEngine {
	/**
	 * Flags used when placing historic blocks: send the change to clients, but do not trigger neighbour updates,
	 * shape updates, drops, block entity removal side effects (chests spilling their items) or placement logic.
	 * The region is restored exactly as it was, so none of those are wanted.
	 */
	private static final int PLACE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS
			| Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS | Block.UPDATE_SKIP_ON_PLACE;

	/** An inclusive box inside one dimension. */
	public record Region(ServerLevel level, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
		public static Region of(ServerLevel level, BlockPos a, BlockPos b) {
			int minY = Math.max(level.getMinY(), Math.min(a.getY(), b.getY()));
			int maxY = Math.min(level.getMaxY(), Math.max(a.getY(), b.getY()));
			return new Region(level,
					Math.min(a.getX(), b.getX()), minY, Math.min(a.getZ(), b.getZ()),
					Math.max(a.getX(), b.getX()), maxY, Math.max(a.getZ(), b.getZ()));
		}

		public long chunkCount() {
			long w = SectionPos.blockToSectionCoord(maxX) - SectionPos.blockToSectionCoord(minX) + 1L;
			long d = SectionPos.blockToSectionCoord(maxZ) - SectionPos.blockToSectionCoord(minZ) + 1L;
			return w * d;
		}

		public long volume() {
			return (maxX - minX + 1L) * (maxY - minY + 1L) * (maxZ - minZ + 1L);
		}

		public String describe() {
			return "(" + minX + ", " + minY + ", " + minZ + ") to (" + maxX + ", " + maxY + ", " + maxZ + ") in "
					+ level.dimension().identifier();
		}
	}

	/** What the last finished rollback of an actor did, so it can be undone. */
	public record UndoRecord(Region region, long startedAt) {
	}

	private final class Job {
		final CommandSourceStack source;
		final String actor;
		final Region region;
		final long since;
		final boolean preview;
		final boolean undo;
		List<Database.HistoricState> plan = List.of();
		int index;
		long startedAt;
		int blocksChanged;
		int blockEntitiesRestored;
		int skippedUnloaded;
		int failed;
		final Map<String, BlockState> stateCache = new HashMap<>();
		HolderLookup<Block> blocks;

		Job(CommandSourceStack source, Region region, long since, boolean preview, boolean undo) {
			this.source = source;
			this.actor = source.getTextName();
			this.region = region;
			this.since = since;
			this.preview = preview;
			this.undo = undo;
		}
	}

	private final ModConfig config;
	private final ArrayDeque<Job> queue = new ArrayDeque<>();
	private final Map<String, UndoRecord> undoRecords = new HashMap<>();
	private @Nullable Job active;

	public RollbackEngine(ModConfig config) {
		this.config = config;
	}

	public void reset() {
		queue.clear();
		undoRecords.clear();
		active = null;
	}

	public @Nullable UndoRecord undoRecord(String actor) {
		return undoRecords.get(actor);
	}

	// ---------------------------------------------------------------- planning

	/**
	 * Starts a rollback of {@code region} to how it looked at {@code since} (epoch milliseconds).
	 * Feedback is sent to {@code source} when the plan is ready and when the rollback finished.
	 */
	public void submit(Database database, CommandSourceStack source, Region region, long since, boolean preview, boolean undo) {
		MinecraftServer server = source.getServer();
		Job job = new Job(source, region, since, preview, undo);
		String dimension = ChangeTracker.dimensionName(region.level());
		source.sendSuccess(() -> Component.literal((preview ? "Previewing" : "Preparing") + " rollback of " + region.describe()
				+ " to " + describeAgo(since) + "...").withStyle(ChatFormatting.GRAY), false);

		database.query(connection -> database.statesAt(connection, dimension, since,
						region.minX(), region.minY(), region.minZ(), region.maxX(), region.maxY(), region.maxZ()))
				.whenComplete((plan, error) -> server.execute(() -> {
					if (error != null) {
						ChunkReset.LOGGER.error("Rollback planning failed", error);
						source.sendFailure(Component.literal("Rollback failed while reading the change log: " + error.getMessage()));
						return;
					}
					job.plan = plan;
					if (job.preview) {
						source.sendSuccess(() -> Component.literal("Preview: " + plan.size() + " position(s) changed after "
								+ describeAgo(since) + " inside " + region.describe() + " and would be restored.").withStyle(ChatFormatting.YELLOW), false);
						return;
					}
					if (plan.isEmpty()) {
						source.sendSuccess(() -> Component.literal("Nothing to roll back: no recorded changes after "
								+ describeAgo(since) + " inside " + region.describe() + ".").withStyle(ChatFormatting.YELLOW), false);
						return;
					}
					queue.add(job);
					source.sendSuccess(() -> Component.literal("Rolling back " + plan.size() + " position(s)"
							+ (active != null ? " (queued behind another rollback)" : "") + "...").withStyle(ChatFormatting.GRAY), false);
				}));
	}

	// ---------------------------------------------------------------- applying

	/** Called every server tick. Applies part of the active rollback. */
	public void tick(MinecraftServer server) {
		if (active == null) {
			active = queue.poll();
			if (active == null) {
				return;
			}
			active.startedAt = System.currentTimeMillis();
			active.blocks = active.region.level().registryAccess().lookupOrThrow(Registries.BLOCK);
		}
		Job job = active;
		ServerLevel level = job.region.level();
		if (server.getLevel(level.dimension()) != level) {
			finish(job, "the dimension was unloaded");
			return;
		}
		ChangeTracker.pushCause("#rollback:" + job.actor);
		try {
			int budget = config.blocksPerTick;
			while (budget-- > 0 && job.index < job.plan.size()) {
				apply(job, level, job.plan.get(job.index++));
			}
		} finally {
			ChangeTracker.popCause();
		}
		if (job.index >= job.plan.size()) {
			finish(job, null);
		}
	}

	private void apply(Job job, ServerLevel level, Database.HistoricState historic) {
		BlockPos pos = new BlockPos(historic.x(), historic.y(), historic.z());
		int chunkX = SectionPos.blockToSectionCoord(pos.getX());
		int chunkZ = SectionPos.blockToSectionCoord(pos.getZ());
		LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
		if (chunk == null) {
			if (!config.loadUnloadedChunks) {
				job.skippedUnloaded++;
				return;
			}
			chunk = level.getChunk(chunkX, chunkZ);
		}

		BlockState current = chunk.getBlockState(pos);
		BlockState target = current;
		if (historic.state() != null) {
			target = resolveState(job, historic.state());
			if (target == null) {
				job.failed++;
				return;
			}
		}

		boolean blockChanged = false;
		if (target != current) {
			level.setBlock(pos, target, PLACE_FLAGS);
			if (chunk.getBlockState(pos) != target) {
				job.failed++;
				return;
			}
			blockChanged = true;
			job.blocksChanged++;
		}

		if (historic.nbtKnown() && historic.nbt() != null && target.hasBlockEntity()) {
			BlockEntity blockEntity = chunk.getBlockEntity(pos);
			if (blockEntity == null) {
				return;
			}
			CompoundTag wanted;
			try {
				wanted = Database.decodeNbt(historic.nbt());
			} catch (IOException e) {
				ChunkReset.LOGGER.warn("Corrupt block entity data in the change log at {}", pos, e);
				job.failed++;
				return;
			}
			ChangeTracker.stripIgnoredKeys(wanted);
			CompoundTag currentNbt = ChangeTracker.saveBlockEntity(blockEntity, level);
			if (currentNbt != null && currentNbt.equals(wanted)) {
				return;
			}
			try {
				blockEntity.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), wanted));
			} catch (Exception e) {
				ChunkReset.LOGGER.warn("Could not restore block entity data at {}", pos, e);
				job.failed++;
				return;
			}
			blockEntity.setChanged();
			ChangeTracker.onBlockEntityRestored(level, pos, currentNbt, wanted, !blockChanged);
			if (!blockChanged) {
				// The block itself did not change, so clients have to be told about the new block entity data explicitly.
				level.sendBlockUpdated(pos, target, target, Block.UPDATE_CLIENTS);
			}
			job.blockEntitiesRestored++;
		}
	}

	private @Nullable BlockState resolveState(Job job, String serialized) {
		BlockState cached = job.stateCache.get(serialized);
		if (cached != null) {
			return cached;
		}
		try {
			BlockState state = BlockStateParser.parseForBlock(job.blocks, serialized, false).blockState();
			job.stateCache.put(serialized, state);
			return state;
		} catch (CommandSyntaxException e) {
			ChunkReset.LOGGER.warn("Unknown block state in the change log (removed mod?): {}", serialized);
			return null;
		}
	}

	private void finish(Job job, @Nullable String abortReason) {
		active = null;
		if (abortReason != null) {
			job.source.sendFailure(Component.literal("Rollback aborted: " + abortReason + "."));
			return;
		}
		undoRecords.put(job.actor, new UndoRecord(job.region, job.startedAt));
		MutableComponent message = Component.literal((job.undo ? "Undo complete: " : "Rollback complete: ") + job.region.describe()
				+ " restored to " + describeAgo(job.since) + ".").withStyle(ChatFormatting.GREEN);
		message.append(Component.literal("\n  Blocks changed: " + job.blocksChanged
				+ ", block entities restored: " + job.blockEntitiesRestored
				+ (job.skippedUnloaded > 0 ? ", skipped in unloaded chunks: " + job.skippedUnloaded : "")
				+ (job.failed > 0 ? ", failed: " + job.failed : "")).withStyle(ChatFormatting.GRAY));
		message.append(Component.literal("\n  Use /rollback undo to revert this " + (job.undo ? "undo" : "rollback") + ".").withStyle(ChatFormatting.DARK_GRAY));
		job.source.sendSuccess(() -> message, true);
		ChunkReset.LOGGER.info("{} rolled back {} to {}: {} blocks, {} block entities, {} skipped, {} failed",
				job.actor, job.region.describe(), describeAgo(job.since), job.blocksChanged, job.blockEntitiesRestored, job.skippedUnloaded, job.failed);
	}

	// ---------------------------------------------------------------- formatting

	/** "5m 12s ago" style description of an epoch-millisecond timestamp. */
	public static String describeAgo(long time) {
		long seconds = Math.max(0, (System.currentTimeMillis() - time) / 1000);
		return formatDuration(seconds) + " ago";
	}

	public static String formatDuration(long seconds) {
		if (seconds < 1) {
			return "0s";
		}
		StringBuilder sb = new StringBuilder();
		long days = seconds / 86_400;
		long hours = seconds % 86_400 / 3_600;
		long minutes = seconds % 3_600 / 60;
		long secs = seconds % 60;
		if (days > 0) sb.append(days).append("d ");
		if (hours > 0) sb.append(hours).append("h ");
		if (minutes > 0) sb.append(minutes).append("m ");
		if (secs > 0 && days == 0) sb.append(secs).append("s ");
		return sb.toString().trim();
	}
}
