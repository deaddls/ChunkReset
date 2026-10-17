package dev.chunkreset.storage;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * One recorded change, produced on the server thread and persisted by the database writer thread.
 *
 * <p>{@code kind} is {@link #KIND_BLOCK} when the block state itself changed (the entry then carries the
 * previous state and the previous block entity data, if any) or {@link #KIND_BLOCK_ENTITY} when only the
 * block entity data changed (contents of a chest, text of a sign, ...).
 *
 * <p>Entries are ordered by {@code time} first and {@code seq} second. {@code seq} is captured at the very
 * start of a change so nested changes (a block whose placement immediately changes itself again) are
 * ordered after the change that caused them.
 */
public record ChangeEntry(
		long time,
		long seq,
		String dimension,
		int x,
		int y,
		int z,
		int kind,
		@Nullable BlockState oldState,
		@Nullable BlockState newState,
		@Nullable CompoundTag oldNbt,
		@Nullable String cause
) {
	public static final int KIND_BLOCK = 0;
	public static final int KIND_BLOCK_ENTITY = 1;
}
