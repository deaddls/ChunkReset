package dev.chunkreset.tracking;

import dev.chunkreset.ChunkReset;
import dev.chunkreset.storage.ChangeEntry;
import dev.chunkreset.storage.Database;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Receives every server-side block and block entity change (from the mixins) and turns it into
 * {@link ChangeEntry} records.
 *
 * <p>Block changes are caught in {@code LevelChunk.setBlockState}, which every block modification of a loaded
 * chunk goes through. Block entity data changes (chest contents, sign text, ...) do not change the block state,
 * so they are tracked separately: whenever a block entity reports itself as changed we remember its position,
 * and at the end of the tick its current data is compared with a cached snapshot of its previous data.
 *
 * <p>All methods except the cause stack must be called on the server thread.
 */
public final class ChangeTracker {
	private static final AtomicLong SEQUENCE = new AtomicLong();
	private static final ThreadLocal<ArrayDeque<String>> CAUSES = ThreadLocal.withInitial(ArrayDeque::new);
	private static final Map<ServerLevel, LevelData> LEVELS = new HashMap<>();

	private static @Nullable Database database;
	private static boolean trackBlockEntities = true;
	private static long minIntervalMs = 1000;
	private static Set<String> ignoredKeys = Set.of();

	private ChangeTracker() {
	}

	private static final class LevelData {
		/** Last known block entity data (as written by {@code saveWithoutMetadata}) of every loaded block entity. */
		final Long2ObjectOpenHashMap<CompoundTag> blockEntitySnapshots = new Long2ObjectOpenHashMap<>();
		/** Positions whose block entity reported a change since the last flush. */
		final LongOpenHashSet dirtyBlockEntities = new LongOpenHashSet();
		/** When a content change was last recorded per position (for throttling). */
		final Long2LongOpenHashMap lastRecorded = new Long2LongOpenHashMap();
	}

	/** State captured when a block change starts; completed when it finishes. */
	public record Frame(long time, long seq, BlockState oldState, @Nullable CompoundTag oldNbt) {
	}

	// ---------------------------------------------------------------- lifecycle

	public static void start(Database db, boolean trackBlockEntityContents, long blockEntityMinIntervalMs, Collection<String> ignoredBlockEntityKeys) {
		database = db;
		trackBlockEntities = trackBlockEntityContents;
		minIntervalMs = blockEntityMinIntervalMs;
		ignoredKeys = Set.copyOf(ignoredBlockEntityKeys);
		LEVELS.clear();
	}

	public static void stop() {
		database = null;
		LEVELS.clear();
	}

	public static boolean isActive() {
		return database != null;
	}

	public static long nextSequence() {
		return SEQUENCE.incrementAndGet();
	}

	// ---------------------------------------------------------------- cause attribution

	/** Marks every change made until the matching {@link #popCause()} as caused by {@code cause}. */
	public static void pushCause(String cause) {
		CAUSES.get().push(cause);
	}

	public static void popCause() {
		ArrayDeque<String> stack = CAUSES.get();
		if (!stack.isEmpty()) {
			stack.pop();
		}
	}

	public static @Nullable String currentCause() {
		return CAUSES.get().peek();
	}

	// ---------------------------------------------------------------- block changes

	/**
	 * Called at the very start of {@code LevelChunk.setBlockState}. Returns {@code null} when nothing needs tracking.
	 */
	public static @Nullable Frame beginBlockChange(LevelChunk chunk, BlockPos pos, BlockState newState) {
		Database db = database;
		if (db == null || !(chunk.getLevel() instanceof ServerLevel level) || !onServerThread(level)) {
			return null;
		}
		BlockState oldState = chunk.getBlockState(pos);
		if (oldState == newState) {
			return null;
		}
		CompoundTag oldNbt = null;
		if (oldState.hasBlockEntity()) {
			BlockEntity blockEntity = chunk.getBlockEntity(pos);
			if (blockEntity != null) {
				// Record any pending content change of this block entity before the block itself disappears,
				// so the block change below carries exactly the data that was there right before it.
				flushOne(level, pos.asLong(), blockEntity);
				oldNbt = saveBlockEntity(blockEntity, level);
			}
		}
		return new Frame(System.currentTimeMillis(), nextSequence(), oldState, oldNbt);
	}

	/**
	 * Called when {@code LevelChunk.setBlockState} returns. {@code returned} is the method's return value
	 * ({@code null} when vanilla considers the block unchanged).
	 */
	public static void endBlockChange(LevelChunk chunk, BlockPos pos, BlockState newState, @Nullable BlockState returned, Frame frame) {
		Database db = database;
		if (db == null || !(chunk.getLevel() instanceof ServerLevel level)) {
			return;
		}
		boolean changed = returned != null
				// Vanilla also returns null when the block was changed again while it was being placed
				// (e.g. a block that breaks itself immediately); the original change still happened.
				|| !(frame.oldState().isAir() && newState.isAir());
		if (!changed) {
			return;
		}
		LevelData data = LEVELS.get(level);
		if (data != null) {
			long key = pos.asLong();
			// The old block entity (if any) is gone or replaced; its snapshot is invalid now.
			data.blockEntitySnapshots.remove(key);
			if (newState.hasBlockEntity()) {
				// Snapshot the fresh block entity at the end of the tick without recording a change for it.
				data.dirtyBlockEntities.add(key);
			} else {
				data.dirtyBlockEntities.remove(key);
			}
		}
		db.record(new ChangeEntry(frame.time(), frame.seq(), dimensionName(level), pos.getX(), pos.getY(), pos.getZ(),
				ChangeEntry.KIND_BLOCK, frame.oldState(), newState, frame.oldNbt(), currentCause()));
	}

	// ---------------------------------------------------------------- block entity data changes

	/** Called from {@code Level.blockEntityChanged}. */
	public static void markBlockEntityDirty(Level level, BlockPos pos) {
		if (!trackBlockEntities || database == null || !(level instanceof ServerLevel serverLevel) || !onServerThread(serverLevel)) {
			return;
		}
		levelData(serverLevel).dirtyBlockEntities.add(pos.asLong());
	}

	/** Called once a chunk's block entities are registered with the level (chunk became loaded). */
	public static void onChunkBlockEntitiesRegistered(LevelChunk chunk) {
		if (!trackBlockEntities || database == null || !(chunk.getLevel() instanceof ServerLevel level) || !onServerThread(level)) {
			return;
		}
		LevelData data = levelData(level);
		for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
			CompoundTag nbt = saveBlockEntity(entry.getValue(), level);
			if (nbt != null) {
				data.blockEntitySnapshots.put(entry.getKey().asLong(), nbt);
			}
		}
		// Block entities loaded from disk stay as raw NBT until first accessed; snapshot those too.
		for (BlockPos pos : chunk.getBlockEntitiesPos()) {
			long key = pos.asLong();
			if (data.blockEntitySnapshots.containsKey(key)) {
				continue;
			}
			CompoundTag pendingTag = chunk.getBlockEntityNbt(pos);
			if (pendingTag != null && !"DUMMY".equals(pendingTag.getStringOr("id", ""))) {
				CompoundTag copy = pendingTag.copy();
				copy.remove("id");
				copy.remove("x");
				copy.remove("y");
				copy.remove("z");
				stripIgnoredKeys(copy);
				data.blockEntitySnapshots.put(key, copy);
			}
		}
	}

	/** Called when a chunk's block entities are discarded (chunk unloaded). */
	public static void onChunkBlockEntitiesCleared(LevelChunk chunk) {
		if (!(chunk.getLevel() instanceof ServerLevel level) || !onServerThread(level)) {
			return;
		}
		LevelData data = LEVELS.get(level);
		if (data == null) {
			return;
		}
		for (BlockPos pos : chunk.getBlockEntitiesPos()) {
			long key = pos.asLong();
			data.blockEntitySnapshots.remove(key);
			data.dirtyBlockEntities.remove(key);
			data.lastRecorded.remove(key);
		}
	}

	/** End of level tick: records every block entity whose data changed since its last snapshot. */
	public static void flushDirtyBlockEntities(ServerLevel level) {
		if (database == null) {
			return;
		}
		LevelData data = LEVELS.get(level);
		if (data == null || data.dirtyBlockEntities.isEmpty()) {
			return;
		}
		long[] keys = data.dirtyBlockEntities.toLongArray();
		data.dirtyBlockEntities.clear();
		for (long key : keys) {
			BlockPos pos = BlockPos.of(key);
			LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
			if (chunk == null) {
				continue;
			}
			BlockEntity blockEntity = chunk.getBlockEntity(pos);
			if (blockEntity == null || blockEntity.isRemoved()) {
				data.blockEntitySnapshots.remove(key);
				continue;
			}
			compareAndRecord(level, data, key, pos, blockEntity, false);
		}
	}

	/** Flushes a single position right away (used before the block at that position is replaced). */
	private static void flushOne(ServerLevel level, long key, BlockEntity blockEntity) {
		LevelData data = LEVELS.get(level);
		if (data == null || !data.dirtyBlockEntities.remove(key)) {
			return;
		}
		compareAndRecord(level, data, key, blockEntity.getBlockPos(), blockEntity, true);
	}

	private static void compareAndRecord(ServerLevel level, LevelData data, long key, BlockPos pos, BlockEntity blockEntity, boolean force) {
		Database db = database;
		if (db == null) {
			return;
		}
		long now = System.currentTimeMillis();
		if (!force && minIntervalMs > 0 && data.blockEntitySnapshots.containsKey(key)) {
			long last = data.lastRecorded.getOrDefault(key, 0L);
			if (now - last < minIntervalMs) {
				// Too soon after the last recorded change: keep it dirty and look again next tick.
				// The snapshot is deliberately left untouched so the eventual record still carries the data
				// as it was when the previous change was recorded.
				data.dirtyBlockEntities.add(key);
				return;
			}
		}
		CompoundTag current = saveBlockEntity(blockEntity, level);
		if (current == null) {
			return;
		}
		CompoundTag previous = data.blockEntitySnapshots.put(key, current);
		if (previous != null && !previous.equals(current)) {
			data.lastRecorded.put(key, now);
			db.record(new ChangeEntry(now, nextSequence(), dimensionName(level),
					pos.getX(), pos.getY(), pos.getZ(), ChangeEntry.KIND_BLOCK_ENTITY, null, null, previous, currentCause()));
		}
	}

	/**
	 * Called by the rollback engine after it loaded historic data into a block entity, so the snapshot matches
	 * and no spurious change is recorded. When {@code recordPrevious} is set the previous data is logged as a
	 * change (needed when the block itself was not replaced, so that the rollback itself can be undone).
	 */
	public static void onBlockEntityRestored(ServerLevel level, BlockPos pos, @Nullable CompoundTag previous, CompoundTag restored, boolean recordPrevious) {
		Database db = database;
		if (db == null) {
			return;
		}
		long key = pos.asLong();
		LevelData data = levelData(level);
		data.blockEntitySnapshots.put(key, restored);
		data.dirtyBlockEntities.remove(key);
		if (recordPrevious && previous != null && !previous.equals(restored)) {
			db.record(new ChangeEntry(System.currentTimeMillis(), nextSequence(), dimensionName(level),
					pos.getX(), pos.getY(), pos.getZ(), ChangeEntry.KIND_BLOCK_ENTITY, null, null, previous, currentCause()));
		}
	}

	// ---------------------------------------------------------------- helpers

	/** Serializes a block entity the way it is stored in the change log (without transient counters). */
	public static @Nullable CompoundTag saveBlockEntity(BlockEntity blockEntity, ServerLevel level) {
		try {
			CompoundTag tag = blockEntity.saveWithoutMetadata(level.registryAccess());
			stripIgnoredKeys(tag);
			return tag;
		} catch (Exception e) {
			ChunkReset.LOGGER.warn("Could not save block entity at {}", blockEntity.getBlockPos(), e);
			return null;
		}
	}

	public static void stripIgnoredKeys(CompoundTag tag) {
		for (String key : ignoredKeys) {
			tag.remove(key);
		}
	}

	/** Vanilla only changes blocks on the server thread; anything else would corrupt our bookkeeping, so it is ignored. */
	private static boolean onServerThread(ServerLevel level) {
		return Thread.currentThread() == level.getServer().getRunningThread();
	}

	public static String dimensionName(ServerLevel level) {
		return level.dimension().identifier().toString();
	}

	private static LevelData levelData(ServerLevel level) {
		return LEVELS.computeIfAbsent(level, l -> new LevelData());
	}

	public static void forgetLevel(ServerLevel level) {
		LEVELS.remove(level);
	}

	/** For the status command: number of block entities currently snapshotted. */
	public static int snapshotCount() {
		int total = 0;
		for (LevelData data : LEVELS.values()) {
			total += data.blockEntitySnapshots.size();
		}
		return total;
	}
}
