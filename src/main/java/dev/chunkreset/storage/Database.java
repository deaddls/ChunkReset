package dev.chunkreset.storage;

import dev.chunkreset.ChunkReset;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;
import org.sqlite.SQLiteConfig;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SQLite-backed change log. All database access happens on one dedicated writer thread; the server thread
 * only enqueues entries and submits read tasks. Tasks are executed strictly in submission order, and every
 * read task first drains the pending entry queue, so a query always sees every change recorded before it
 * was submitted.
 */
public final class Database implements AutoCloseable {
	private static final int BATCH_SIZE = 5000;

	private final Path file;
	private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "ChunkReset-Database");
		t.setDaemon(true);
		return t;
	});
	private final ConcurrentLinkedQueue<ChangeEntry> pending = new ConcurrentLinkedQueue<>();
	private final AtomicInteger pendingCount = new AtomicInteger();
	private final AtomicBoolean flushScheduled = new AtomicBoolean();
	private volatile boolean closed;

	// The fields below are only touched on the writer thread.
	private Connection connection;
	private PreparedStatement insertChange;
	private final Map<String, Integer> dimensionIds = new HashMap<>();
	private final Map<String, Integer> stateIds = new HashMap<>();
	private final Map<Integer, String> stateNames = new HashMap<>();

	private Database(Path file) {
		this.file = file;
	}

	/** Opens (or creates) the database. Blocks until the schema is ready so startup failures are reported immediately. */
	public static Database open(Path file) throws Exception {
		Database db = new Database(file);
		try {
			db.executor.submit(db::openConnection).get();
		} catch (Exception e) {
			db.executor.shutdownNow();
			throw e;
		}
		return db;
	}

	private Void openConnection() throws SQLException, IOException {
		Files.createDirectories(file.getParent());
		SQLiteConfig config = new SQLiteConfig();
		config.setJournalMode(SQLiteConfig.JournalMode.WAL);
		config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
		config.setBusyTimeout(30_000);
		// Connect through the driver directly instead of DriverManager, which does not see classes loaded by the mod loader.
		connection = config.createConnection("jdbc:sqlite:" + file.toAbsolutePath());
		try (Statement st = connection.createStatement()) {
			st.executeUpdate("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
			st.executeUpdate("CREATE TABLE IF NOT EXISTS dimensions (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE)");
			st.executeUpdate("CREATE TABLE IF NOT EXISTS states (id INTEGER PRIMARY KEY AUTOINCREMENT, state TEXT NOT NULL UNIQUE)");
			st.executeUpdate("""
					CREATE TABLE IF NOT EXISTS changes (
						id INTEGER PRIMARY KEY AUTOINCREMENT,
						time INTEGER NOT NULL,
						seq INTEGER NOT NULL,
						dim INTEGER NOT NULL,
						x INTEGER NOT NULL,
						y INTEGER NOT NULL,
						z INTEGER NOT NULL,
						cx INTEGER NOT NULL,
						cz INTEGER NOT NULL,
						kind INTEGER NOT NULL,
						old_state INTEGER,
						new_state INTEGER,
						old_nbt BLOB,
						cause TEXT
					)""");
			st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_changes_region ON changes (dim, cx, cz, time)");
			st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_changes_time ON changes (time)");
			st.executeUpdate("INSERT OR IGNORE INTO meta (key, value) VALUES ('schema', '1')");
		}
		try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery("SELECT id, name FROM dimensions")) {
			while (rs.next()) {
				dimensionIds.put(rs.getString(2), rs.getInt(1));
			}
		}
		try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery("SELECT id, state FROM states")) {
			while (rs.next()) {
				stateIds.put(rs.getString(2), rs.getInt(1));
				stateNames.put(rs.getInt(1), rs.getString(2));
			}
		}
		insertChange = connection.prepareStatement(
				"INSERT INTO changes (time, seq, dim, x, y, z, cx, cz, kind, old_state, new_state, old_nbt, cause) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
		connection.setAutoCommit(false);
		return null;
	}

	// ---------------------------------------------------------------- writing

	/** Queues an entry for persistence. Safe to call from any thread; never blocks. */
	public void record(ChangeEntry entry) {
		if (closed) {
			return;
		}
		pending.add(entry);
		pendingCount.incrementAndGet();
		scheduleFlush();
	}

	public int pendingCount() {
		return pendingCount.get();
	}

	/** Makes sure queued entries get written soon. Called every second from the server thread as a safety net. */
	public void scheduleFlush() {
		if (!closed && !pending.isEmpty() && flushScheduled.compareAndSet(false, true)) {
			executor.execute(() -> {
				try {
					drainPending();
				} finally {
					flushScheduled.set(false);
					if (!pending.isEmpty()) {
						scheduleFlush();
					}
				}
			});
		}
	}

	/** Writer thread only: persists every queued entry. */
	private void drainPending() {
		while (!pending.isEmpty()) {
			int written = 0;
			try {
				ChangeEntry entry;
				while (written < BATCH_SIZE && (entry = pending.poll()) != null) {
					pendingCount.decrementAndGet();
					bind(entry);
					insertChange.addBatch();
					written++;
				}
				if (written > 0) {
					insertChange.executeBatch();
					connection.commit();
				}
			} catch (SQLException | IOException e) {
				ChunkReset.LOGGER.error("Failed to write {} change(s) to the database", written, e);
				try {
					insertChange.clearBatch();
					connection.rollback();
				} catch (SQLException ignored) {
				}
			}
		}
	}

	private void bind(ChangeEntry e) throws SQLException, IOException {
		insertChange.setLong(1, e.time());
		insertChange.setLong(2, e.seq());
		insertChange.setInt(3, dimensionId(e.dimension()));
		insertChange.setInt(4, e.x());
		insertChange.setInt(5, e.y());
		insertChange.setInt(6, e.z());
		insertChange.setInt(7, SectionPos.blockToSectionCoord(e.x()));
		insertChange.setInt(8, SectionPos.blockToSectionCoord(e.z()));
		insertChange.setInt(9, e.kind());
		if (e.oldState() != null) {
			insertChange.setInt(10, stateId(e.oldState()));
		} else {
			insertChange.setNull(10, java.sql.Types.INTEGER);
		}
		if (e.newState() != null) {
			insertChange.setInt(11, stateId(e.newState()));
		} else {
			insertChange.setNull(11, java.sql.Types.INTEGER);
		}
		if (e.oldNbt() != null) {
			insertChange.setBytes(12, encodeNbt(e.oldNbt()));
		} else {
			insertChange.setNull(12, java.sql.Types.BLOB);
		}
		if (e.cause() != null) {
			insertChange.setString(13, e.cause());
		} else {
			insertChange.setNull(13, java.sql.Types.VARCHAR);
		}
	}

	private int dimensionId(String name) throws SQLException {
		Integer id = dimensionIds.get(name);
		if (id != null) {
			return id;
		}
		try (PreparedStatement ps = connection.prepareStatement("INSERT INTO dimensions (name) VALUES (?)", Statement.RETURN_GENERATED_KEYS)) {
			ps.setString(1, name);
			ps.executeUpdate();
			try (ResultSet keys = ps.getGeneratedKeys()) {
				keys.next();
				id = keys.getInt(1);
			}
		}
		dimensionIds.put(name, id);
		return id;
	}

	private int stateId(BlockState state) throws SQLException {
		String serialized = BlockStateParser.serialize(state);
		Integer id = stateIds.get(serialized);
		if (id != null) {
			return id;
		}
		try (PreparedStatement ps = connection.prepareStatement("INSERT INTO states (state) VALUES (?)", Statement.RETURN_GENERATED_KEYS)) {
			ps.setString(1, serialized);
			ps.executeUpdate();
			try (ResultSet keys = ps.getGeneratedKeys()) {
				keys.next();
				id = keys.getInt(1);
			}
		}
		stateIds.put(serialized, id);
		stateNames.put(id, serialized);
		return id;
	}

	static byte[] encodeNbt(CompoundTag tag) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream(256);
		NbtIo.writeCompressed(tag, out);
		return out.toByteArray();
	}

	public static CompoundTag decodeNbt(byte[] bytes) throws IOException {
		return NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.unlimitedHeap());
	}

	// ---------------------------------------------------------------- reading

	/** Runs {@code task} on the database thread after every entry queued so far has been written. */
	public <T> CompletableFuture<T> query(DatabaseTask<T> task) {
		CompletableFuture<T> future = new CompletableFuture<>();
		if (closed) {
			future.completeExceptionally(new IllegalStateException("Database is closed"));
			return future;
		}
		executor.execute(() -> {
			try {
				drainPending();
				T result = task.run(connection);
				// Reads implicitly open a transaction; end it so WAL checkpoints are never held up.
				connection.commit();
				future.complete(result);
			} catch (Throwable t) {
				try {
					connection.rollback();
				} catch (SQLException ignored) {
				}
				future.completeExceptionally(t);
			}
		});
		return future;
	}

	@FunctionalInterface
	public interface DatabaseTask<T> {
		T run(Connection connection) throws Exception;
	}

	/** The block state at time {@code T} for one position, plus the block entity data at that time. */
	public record HistoricState(int x, int y, int z, @Nullable String state, boolean nbtKnown, byte @Nullable [] nbt) {
	}

	/**
	 * Writer thread only. Reconstructs the state of every position inside the box that changed after {@code since}.
	 *
	 * <p>For a position, the oldest change after {@code since} tells us what was there at {@code since}: the oldest
	 * block change carries the block state, and the oldest change of any kind carries the block entity data.
	 * Positions without any change after {@code since} are not returned because they are already in their
	 * historic state.
	 */
	public List<HistoricState> statesAt(Connection connection, String dimension, long since,
			int minX, int minY, int minZ, int maxX, int maxY, int maxZ) throws SQLException {
		Integer dim = dimensionIds.get(dimension);
		if (dim == null) {
			return List.of();
		}
		String sql = """
				SELECT x, y, z, kind, old_state, old_nbt FROM changes
				WHERE dim = ? AND cx BETWEEN ? AND ? AND cz BETWEEN ? AND ? AND time > ?
				  AND x BETWEEN ? AND ? AND y BETWEEN ? AND ? AND z BETWEEN ? AND ?
				ORDER BY time ASC, seq ASC, id ASC""";
		Map<Long, HistoricState> result = new HashMap<>();
		List<Long> order = new ArrayList<>();
		try (PreparedStatement ps = connection.prepareStatement(sql)) {
			ps.setInt(1, dim);
			ps.setInt(2, SectionPos.blockToSectionCoord(minX));
			ps.setInt(3, SectionPos.blockToSectionCoord(maxX));
			ps.setInt(4, SectionPos.blockToSectionCoord(minZ));
			ps.setInt(5, SectionPos.blockToSectionCoord(maxZ));
			ps.setLong(6, since);
			ps.setInt(7, minX);
			ps.setInt(8, maxX);
			ps.setInt(9, minY);
			ps.setInt(10, maxY);
			ps.setInt(11, minZ);
			ps.setInt(12, maxZ);
			ps.setFetchSize(10_000);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					int x = rs.getInt(1);
					int y = rs.getInt(2);
					int z = rs.getInt(3);
					int kind = rs.getInt(4);
					long key = net.minecraft.core.BlockPos.asLong(x, y, z);
					HistoricState existing = result.get(key);
					if (existing == null) {
						// Oldest change of any kind: it carries the block entity data at `since`.
						byte[] nbt = rs.getBytes(6);
						String state = null;
						if (kind == ChangeEntry.KIND_BLOCK) {
							state = stateNames.get(rs.getInt(5));
						}
						result.put(key, new HistoricState(x, y, z, state, true, nbt));
						order.add(key);
					} else if (existing.state() == null && kind == ChangeEntry.KIND_BLOCK) {
						// Oldest *block* change: it carries the block state at `since`.
						result.put(key, new HistoricState(x, y, z, stateNames.get(rs.getInt(5)), existing.nbtKnown(), existing.nbt()));
					}
				}
			}
		}
		List<HistoricState> list = new ArrayList<>(order.size());
		for (long key : order) {
			list.add(result.get(key));
		}
		return list;
	}

	/** One row of history for display purposes. */
	public record HistoryRow(long time, int kind, @Nullable String oldState, @Nullable String newState, @Nullable String cause, boolean hadNbt) {
	}

	/** Writer thread only. Most recent changes at one position, newest first. */
	public List<HistoryRow> history(Connection connection, String dimension, int x, int y, int z, int limit) throws SQLException {
		Integer dim = dimensionIds.get(dimension);
		if (dim == null) {
			return List.of();
		}
		List<HistoryRow> rows = new ArrayList<>();
		String sql = """
				SELECT time, kind, old_state, new_state, cause, old_nbt IS NOT NULL FROM changes
				WHERE dim = ? AND cx = ? AND cz = ? AND x = ? AND y = ? AND z = ?
				ORDER BY time DESC, seq DESC, id DESC LIMIT ?""";
		try (PreparedStatement ps = connection.prepareStatement(sql)) {
			ps.setInt(1, dim);
			ps.setInt(2, SectionPos.blockToSectionCoord(x));
			ps.setInt(3, SectionPos.blockToSectionCoord(z));
			ps.setInt(4, x);
			ps.setInt(5, y);
			ps.setInt(6, z);
			ps.setInt(7, limit);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					String oldState = rs.getObject(3) == null ? null : stateNames.get(rs.getInt(3));
					String newState = rs.getObject(4) == null ? null : stateNames.get(rs.getInt(4));
					rows.add(new HistoryRow(rs.getLong(1), rs.getInt(2), oldState, newState, rs.getString(5), rs.getBoolean(6)));
				}
			}
		}
		return rows;
	}

	public record Stats(long rows, long oldestTime, long newestTime, long fileBytes) {
	}

	/** Writer thread only. */
	public Stats stats(Connection connection) throws SQLException {
		long rows = 0;
		long oldest = 0;
		long newest = 0;
		try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*), MIN(time), MAX(time) FROM changes")) {
			if (rs.next()) {
				rows = rs.getLong(1);
				oldest = rs.getLong(2);
				newest = rs.getLong(3);
			}
		}
		long bytes = 0;
		try {
			bytes = Files.size(file);
			Path wal = file.resolveSibling(file.getFileName() + "-wal");
			if (Files.exists(wal)) {
				bytes += Files.size(wal);
			}
		} catch (IOException ignored) {
		}
		return new Stats(rows, oldest, newest, bytes);
	}

	/** Writer thread only. Deletes every change older than {@code before} in bounded batches and returns how many rows went away. */
	public long purge(Connection connection, long before) throws SQLException {
		long total = 0;
		try (PreparedStatement ps = connection.prepareStatement(
				"DELETE FROM changes WHERE id IN (SELECT id FROM changes WHERE time < ? ORDER BY time LIMIT 20000)")) {
			while (true) {
				ps.setLong(1, before);
				int deleted = ps.executeUpdate();
				connection.commit();
				total += deleted;
				if (deleted == 0) {
					break;
				}
			}
		}
		return total;
	}

	// ---------------------------------------------------------------- lifecycle

	/** Writes everything that is still queued and closes the connection. Blocks until done. */
	@Override
	public void close() {
		if (closed) {
			return;
		}
		closed = true;
		try {
			executor.submit(() -> {
				drainPending();
				try {
					if (insertChange != null) {
						insertChange.close();
					}
					if (connection != null) {
						connection.commit();
						connection.close();
					}
				} catch (SQLException e) {
					ChunkReset.LOGGER.error("Error while closing the database", e);
				}
			}).get(60, TimeUnit.SECONDS);
		} catch (Exception e) {
			ChunkReset.LOGGER.error("Could not flush the database cleanly", e);
		}
		executor.shutdown();
	}
}
