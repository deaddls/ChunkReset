package dev.chunkreset.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.chunkreset.ChunkReset;
import dev.chunkreset.rollback.RollbackEngine;
import dev.chunkreset.storage.ChangeEntry;
import dev.chunkreset.storage.Database;
import dev.chunkreset.tracking.ChangeTracker;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <pre>
 * /rollback region &lt;from&gt; &lt;to&gt; &lt;time&gt; [&lt;dimension&gt;] [preview]
 * /rollback chunk &lt;time&gt; [&lt;chunkRadius&gt;] [preview]
 * /rollback radius &lt;blocks&gt; &lt;time&gt; [preview]
 * /rollback undo
 * /rollback lookup &lt;pos&gt; [&lt;count&gt;]
 * /rollback status
 * /rollback purge &lt;olderThan&gt;
 * </pre>
 * {@code time} is how far back to go, e.g. {@code 1h}, {@code 30m}, {@code 2d12h}, {@code 90s}, {@code 1w}.
 */
public final class RollbackCommand {
	private static final Pattern DURATION_PART = Pattern.compile("(\\d+)\\s*([smhdw])", Pattern.CASE_INSENSITIVE);
	private static final SimpleCommandExceptionType ERROR_BAD_DURATION = new SimpleCommandExceptionType(
			Component.literal("Invalid time. Use a duration like 1h, 30m, 2d12h, 90s or 1w"));
	private static final SimpleCommandExceptionType ERROR_DISABLED = new SimpleCommandExceptionType(
			Component.literal("ChunkReset is not active: the change database could not be opened (see the server log)"));
	private static final SimpleCommandExceptionType ERROR_NO_UNDO = new SimpleCommandExceptionType(
			Component.literal("You have not performed a rollback that could be undone"));

	private RollbackCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		LiteralCommandNode<CommandSourceStack> root = dispatcher.register(Commands.literal("rollback")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("region")
						.then(Commands.argument("from", BlockPosArgument.blockPos())
								.then(Commands.argument("to", BlockPosArgument.blockPos())
										.then(withPreview(Commands.argument("time", StringArgumentType.word()), ctx -> region(ctx, false), ctx -> region(ctx, true))
												.then(withPreview(Commands.argument("dimension", DimensionArgument.dimension()), ctx -> region(ctx, false), ctx -> region(ctx, true)))))))
				.then(Commands.literal("chunk")
						.then(withPreview(Commands.argument("time", StringArgumentType.word()), ctx -> chunk(ctx, false), ctx -> chunk(ctx, true))
								.then(withPreview(Commands.argument("chunkRadius", IntegerArgumentType.integer(0, 32)), ctx -> chunk(ctx, false), ctx -> chunk(ctx, true)))))
				.then(Commands.literal("radius")
						.then(Commands.argument("blocks", IntegerArgumentType.integer(1, 512))
								.then(withPreview(Commands.argument("time", StringArgumentType.word()), ctx -> radius(ctx, false), ctx -> radius(ctx, true)))))
				.then(Commands.literal("undo")
						.executes(RollbackCommand::undo))
				.then(Commands.literal("lookup")
						.then(Commands.argument("pos", BlockPosArgument.blockPos())
								.executes(ctx -> lookup(ctx, 10))
								.then(Commands.argument("count", IntegerArgumentType.integer(1, 100))
										.executes(ctx -> lookup(ctx, IntegerArgumentType.getInteger(ctx, "count"))))))
				.then(Commands.literal("status")
						.executes(RollbackCommand::status))
				.then(Commands.literal("purge")
						.requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
						.then(Commands.argument("olderThan", StringArgumentType.word())
								.executes(RollbackCommand::purge))));
		// Alias in case another mod also provides /rollback.
		dispatcher.register(Commands.literal("chunkreset")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.redirect(root));
	}

	@FunctionalInterface
	private interface Action {
		int run(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException;
	}

	/** Attaches both the normal executor and an optional trailing {@code preview} literal to a node. */
	private static <T extends ArgumentBuilder<CommandSourceStack, T>> T withPreview(T node, Action run, Action preview) {
		return node.executes(run::run).then(Commands.literal("preview").executes(preview::run));
	}

	// ---------------------------------------------------------------- rollback variants

	private static int region(CommandContext<CommandSourceStack> ctx, boolean preview) throws CommandSyntaxException {
		BlockPos from = BlockPosArgument.getBlockPos(ctx, "from");
		BlockPos to = BlockPosArgument.getBlockPos(ctx, "to");
		ServerLevel level = hasArgument(ctx, "dimension") ? DimensionArgument.getDimension(ctx, "dimension") : ctx.getSource().getLevel();
		long since = parseSince(StringArgumentType.getString(ctx, "time"));
		return start(ctx.getSource(), RollbackEngine.Region.of(level, from, to), since, preview, false);
	}

	private static int chunk(CommandContext<CommandSourceStack> ctx, boolean preview) throws CommandSyntaxException {
		ServerLevel level = ctx.getSource().getLevel();
		BlockPos center = sourcePos(ctx.getSource());
		int radius = hasArgument(ctx, "chunkRadius") ? IntegerArgumentType.getInteger(ctx, "chunkRadius") : 0;
		int chunkX = center.getX() >> 4;
		int chunkZ = center.getZ() >> 4;
		BlockPos from = new BlockPos((chunkX - radius) << 4, level.getMinY(), (chunkZ - radius) << 4);
		BlockPos to = new BlockPos(((chunkX + radius) << 4) + 15, level.getMaxY(), ((chunkZ + radius) << 4) + 15);
		long since = parseSince(StringArgumentType.getString(ctx, "time"));
		return start(ctx.getSource(), RollbackEngine.Region.of(level, from, to), since, preview, false);
	}

	private static int radius(CommandContext<CommandSourceStack> ctx, boolean preview) throws CommandSyntaxException {
		ServerLevel level = ctx.getSource().getLevel();
		BlockPos center = sourcePos(ctx.getSource());
		int r = IntegerArgumentType.getInteger(ctx, "blocks");
		long since = parseSince(StringArgumentType.getString(ctx, "time"));
		return start(ctx.getSource(), RollbackEngine.Region.of(level, center.offset(-r, -r, -r), center.offset(r, r, r)), since, preview, false);
	}

	private static int undo(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		RollbackEngine engine = ChunkReset.engine();
		RollbackEngine.UndoRecord record = engine.undoRecord(ctx.getSource().getTextName());
		if (record == null) {
			throw ERROR_NO_UNDO.create();
		}
		// Everything the rollback did was recorded at or after `startedAt`, so going back to just before it undoes it.
		return start(ctx.getSource(), record.region(), record.startedAt() - 1, false, true);
	}

	private static int start(CommandSourceStack source, RollbackEngine.Region region, long since, boolean preview, boolean undo) throws CommandSyntaxException {
		Database database = requireDatabase();
		long chunks = region.chunkCount();
		int max = ChunkReset.config().maxRegionChunks;
		if (chunks > max) {
			source.sendFailure(Component.literal("That region spans " + chunks + " chunks; the limit is " + max
					+ " (maxRegionChunks in config/chunkreset.json)."));
			return 0;
		}
		ChunkReset.engine().submit(database, source, region, since, preview, undo);
		return 1;
	}

	// ---------------------------------------------------------------- lookup / status / purge

	private static int lookup(CommandContext<CommandSourceStack> ctx, int count) throws CommandSyntaxException {
		Database database = requireDatabase();
		CommandSourceStack source = ctx.getSource();
		BlockPos pos = BlockPosArgument.getBlockPos(ctx, "pos");
		String dimension = ChangeTracker.dimensionName(source.getLevel());
		database.query(connection -> database.history(connection, dimension, pos.getX(), pos.getY(), pos.getZ(), count))
				.whenComplete((rows, error) -> source.getServer().execute(() -> {
					if (error != null) {
						ChunkReset.LOGGER.error("Lookup failed", error);
						source.sendFailure(Component.literal("Lookup failed: " + error.getMessage()));
						return;
					}
					source.sendSuccess(() -> formatHistory(pos, rows), false);
				}));
		return 1;
	}

	private static Component formatHistory(BlockPos pos, List<Database.HistoryRow> rows) {
		MutableComponent out = Component.literal("History at " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()
				+ (rows.isEmpty() ? ": no recorded changes" : " (newest first):")).withStyle(ChatFormatting.YELLOW);
		SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
		for (Database.HistoryRow row : rows) {
			String who = row.cause() == null ? "#environment" : row.cause();
			String what;
			if (row.kind() == ChangeEntry.KIND_BLOCK) {
				what = shortState(row.oldState()) + " -> " + shortState(row.newState());
			} else {
				what = "block entity contents changed";
			}
			out.append(Component.literal("\n  " + format.format(new Date(row.time())) + " (" + RollbackEngine.describeAgo(row.time()) + ") ")
					.withStyle(ChatFormatting.GRAY));
			out.append(Component.literal(who + ": ").withStyle(ChatFormatting.AQUA));
			out.append(Component.literal(what).withStyle(ChatFormatting.WHITE));
		}
		return out;
	}

	private static String shortState(String state) {
		if (state == null) {
			return "?";
		}
		return state.startsWith("minecraft:") ? state.substring("minecraft:".length()) : state;
	}

	private static int status(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		Database database = requireDatabase();
		CommandSourceStack source = ctx.getSource();
		int pending = database.pendingCount();
		int snapshots = ChangeTracker.snapshotCount();
		database.query(database::stats).whenComplete((stats, error) -> source.getServer().execute(() -> {
			if (error != null) {
				source.sendFailure(Component.literal("Status failed: " + error.getMessage()));
				return;
			}
			MutableComponent out = Component.literal("ChunkReset status").withStyle(ChatFormatting.YELLOW);
			out.append(Component.literal("\n  Recorded changes: " + stats.rows()
					+ (stats.rows() > 0 ? " (oldest " + RollbackEngine.describeAgo(stats.oldestTime()) + ", newest " + RollbackEngine.describeAgo(stats.newestTime()) + ")" : "")
					+ "\n  Waiting to be written: " + pending
					+ "\n  Block entities being watched: " + snapshots
					+ "\n  Database size: " + (stats.fileBytes() / 1024 / 1024) + " MiB"
					+ "\n  Retention: " + (ChunkReset.config().retentionDays == 0 ? "forever" : ChunkReset.config().retentionDays + " days")).withStyle(ChatFormatting.GRAY));
			source.sendSuccess(() -> out, false);
		}));
		return 1;
	}

	private static int purge(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		Database database = requireDatabase();
		CommandSourceStack source = ctx.getSource();
		long before = parseSince(StringArgumentType.getString(ctx, "olderThan"));
		database.query(connection -> database.purge(connection, before)).whenComplete((deleted, error) -> source.getServer().execute(() -> {
			if (error != null) {
				source.sendFailure(Component.literal("Purge failed: " + error.getMessage()));
				return;
			}
			source.sendSuccess(() -> Component.literal("Deleted " + deleted + " recorded change(s) older than "
					+ RollbackEngine.describeAgo(before) + ".").withStyle(ChatFormatting.GREEN), true);
		}));
		return 1;
	}

	// ---------------------------------------------------------------- helpers

	private static Database requireDatabase() throws CommandSyntaxException {
		Database database = ChunkReset.database();
		if (database == null) {
			throw ERROR_DISABLED.create();
		}
		return database;
	}

	private static BlockPos sourcePos(CommandSourceStack source) {
		Vec3 pos = source.getPosition();
		return BlockPos.containing(pos.x(), pos.y(), pos.z());
	}

	private static boolean hasArgument(CommandContext<CommandSourceStack> ctx, String name) {
		try {
			ctx.getArgument(name, Object.class);
			return true;
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	/** Turns a duration like {@code 1h30m} into the epoch-millisecond timestamp that long ago. */
	static long parseSince(String duration) throws CommandSyntaxException {
		return System.currentTimeMillis() - parseDurationMillis(duration);
	}

	static long parseDurationMillis(String duration) throws CommandSyntaxException {
		Matcher matcher = DURATION_PART.matcher(duration);
		long total = 0;
		int end = 0;
		boolean any = false;
		while (matcher.find()) {
			if (matcher.start() != end) {
				throw ERROR_BAD_DURATION.create();
			}
			end = matcher.end();
			any = true;
			long amount;
			try {
				amount = Long.parseLong(matcher.group(1));
			} catch (NumberFormatException e) {
				throw ERROR_BAD_DURATION.create();
			}
			long unit = switch (Character.toLowerCase(matcher.group(2).charAt(0))) {
				case 's' -> 1_000L;
				case 'm' -> 60_000L;
				case 'h' -> 3_600_000L;
				case 'd' -> 86_400_000L;
				case 'w' -> 604_800_000L;
				default -> throw ERROR_BAD_DURATION.create();
			};
			if (amount > Long.MAX_VALUE / unit) {
				throw ERROR_BAD_DURATION.create();
			}
			total += amount * unit;
		}
		if (!any || end != duration.length() || total <= 0) {
			throw ERROR_BAD_DURATION.create();
		}
		return total;
	}
}
