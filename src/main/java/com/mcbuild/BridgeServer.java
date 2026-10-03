package com.mcbuild;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Minimal HTTP server on 127.0.0.1 (plain sockets, so it works even on trimmed-down Java runtimes).
 *
 * Security:
 *  - bound to loopback only
 *  - every request needs the secret token from config/mcbuild_bridge.json (header X-McBuild-Token);
 *    websites in your browser cannot read that file, so they cannot talk to this
 *  - only the specific command shapes mcbuild.py generates are accepted (fill / setblock /
 *    marker summon + kill), so this can never be used to run /op, /stop, etc.
 */
public class BridgeServer {
	private static final int BASE_PORT = 25585;
	private static final int PORT_TRIES = 10;
	private static final int MAX_BODY = 200_000_000;      // 200x the old 1 MB
	private static final int MAX_COMMANDS = 600_000;     // 200x the old 3000
	// Commands run spread over ticks so huge builds can't trip the server watchdog.
	private static final long TICK_BUDGET_NANOS = 30_000_000L; // 30 ms of each 50 ms tick
	private static final long JOB_TIMEOUT_MINUTES = 120;

	private static final Pattern KILL =
		Pattern.compile("^kill @e\\[type=marker,tag=ai_[a-z0-9_]+\\]$");
	private static final Pattern EXEC = Pattern.compile(
		"^execute at (@p|@e\\[type=marker,tag=ai_[a-z0-9_]+,limit=1\\]) (align xyz )?"
			+ "run (fill|setblock|summon marker) [A-Za-z0-9_~\\[\\]=,:{}\". -]+$");
	private static final Pattern PLAYER_NAME = Pattern.compile("^[A-Za-z0-9_]{1,16}$");

	private static final class Job {
		final List<String> commands;
		final String wantedPlayer;
		final CompletableFuture<JsonObject> future = new CompletableFuture<>();
		String playerName;
		CommandSourceStack source;
		int next = 0;

		Job(List<String> commands, String wantedPlayer) {
			this.commands = commands;
			this.wantedPlayer = wantedPlayer;
		}
	}

	private final ConcurrentLinkedQueue<Job> jobs = new ConcurrentLinkedQueue<>();
	private final MinecraftServer server;
	private final String token;
	private final Path configFile;
	private ServerSocket socket;
	private Thread thread;
	private volatile boolean running;

	public BridgeServer(MinecraftServer server) {
		this.server = server;
		byte[] raw = new byte[16];
		new SecureRandom().nextBytes(raw);
		StringBuilder sb = new StringBuilder();
		for (byte b : raw) sb.append(String.format("%02x", b));
		this.token = sb.toString();
		this.configFile = FabricLoader.getInstance().getConfigDir().resolve("mcbuild_bridge.json");
	}

	public void start() throws IOException {
		IOException last = null;
		for (int i = 0; i < PORT_TRIES && socket == null; i++) {
			try {
				socket = new ServerSocket(BASE_PORT + i, 8, InetAddress.getLoopbackAddress());
			} catch (IOException e) {
				last = e;
			}
		}
		if (socket == null) throw last != null ? last : new IOException("no free port");

		JsonObject cfg = new JsonObject();
		cfg.addProperty("port", socket.getLocalPort());
		cfg.addProperty("token", token);
		Files.createDirectories(configFile.getParent());
		Files.writeString(configFile, cfg.toString(), StandardCharsets.UTF_8);

		running = true;
		thread = new Thread(this::loop, "mcbuild-bridge");
		thread.setDaemon(true);
		thread.start();
		McBuildMod.LOGGER.info("AI Builder Bridge listening on 127.0.0.1:{}", socket.getLocalPort());
	}

	public void stop() {
		running = false;
		Job j;
		while ((j = jobs.poll()) != null) j.future.complete(error("game is shutting down"));
		try {
			if (socket != null) socket.close();
		} catch (IOException ignored) {
		}
		try {
			Files.deleteIfExists(configFile);
		} catch (IOException ignored) {
		}
	}

	// ------------------------------------------------------------ networking

	private void loop() {
		while (running) {
			try (Socket s = socket.accept()) {
				handle(s);
			} catch (SocketException e) {
				if (running) McBuildMod.LOGGER.warn("Bridge socket error: {}", e.getMessage());
			} catch (Exception e) {
				McBuildMod.LOGGER.warn("Bridge request failed: {}", e.toString());
			}
		}
	}

	private void handle(Socket s) throws IOException {
		s.setSoTimeout(60_000);
		InputStream in = s.getInputStream();
		OutputStream out = s.getOutputStream();

		String requestLine = readLine(in);
		if (requestLine == null) return;
		String[] parts = requestLine.split(" ");
		if (parts.length < 2) {
			respond(out, 400, error("bad request"));
			return;
		}
		String method = parts[0];
		String path = parts[1];

		Map<String, String> headers = new HashMap<>();
		String line;
		while ((line = readLine(in)) != null && !line.isEmpty()) {
			int i = line.indexOf(':');
			if (i > 0) {
				headers.put(line.substring(0, i).trim().toLowerCase(Locale.ROOT), line.substring(i + 1).trim());
			}
		}

		String given = headers.getOrDefault("x-mcbuild-token", "");
		if (!MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8))) {
			respond(out, 401, error("bad token"));
			return;
		}

		if (method.equals("GET") && path.equals("/ping")) {
			respond(out, 200, ping());
			return;
		}

		if (method.equals("POST") && path.equals("/run")) {
			int len;
			try {
				len = Integer.parseInt(headers.getOrDefault("content-length", "0"));
			} catch (NumberFormatException e) {
				len = -1;
			}
			if (len < 0 || len > MAX_BODY) {
				respond(out, 413, error("body too large"));
				return;
			}
			String body = new String(in.readNBytes(len), StandardCharsets.UTF_8);
			respond(out, 200, run(body));
			return;
		}

		respond(out, 404, error("not found"));
	}

	private static String readLine(InputStream in) throws IOException {
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		int b;
		boolean any = false;
		while ((b = in.read()) != -1) {
			any = true;
			if (b == '\n') break;
			if (b != '\r') buf.write(b);
			if (buf.size() > 8192) throw new IOException("line too long");
		}
		return any ? buf.toString(StandardCharsets.ISO_8859_1) : null;
	}

	private static void respond(OutputStream out, int status, JsonObject json) throws IOException {
		byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);
		String head = "HTTP/1.1 " + status + " " + (status == 200 ? "OK" : "Error") + "\r\n"
			+ "Content-Type: application/json\r\n"
			+ "Content-Length: " + body.length + "\r\n"
			+ "Connection: close\r\n\r\n";
		out.write(head.getBytes(StandardCharsets.ISO_8859_1));
		out.write(body);
		out.flush();
	}

	private static JsonObject error(String msg) {
		JsonObject o = new JsonObject();
		o.addProperty("error", msg);
		return o;
	}

	// ------------------------------------------------------------ game side

	private JsonObject ping() {
		CompletableFuture<JsonObject> fut = new CompletableFuture<>();
		server.execute(() -> {
			try {
				JsonObject o = new JsonObject();
				o.addProperty("mod", "mcbuild_bridge");
				JsonArray players = new JsonArray();
				for (ServerPlayer p : server.getPlayerList().getPlayers()) {
					players.add(p.getName().getString());
				}
				o.add("players", players);
				fut.complete(o);
			} catch (Throwable t) {
				fut.completeExceptionally(t);
			}
		});
		return await(fut);
	}

	private JsonObject run(String body) {
		List<String> commands = new ArrayList<>();
		String wanted = null;
		try {
			JsonObject req = JsonParser.parseString(body).getAsJsonObject();
			if (req.has("player") && !req.get("player").isJsonNull()) {
				wanted = req.get("player").getAsString();
				if (!PLAYER_NAME.matcher(wanted).matches()) return error("bad player name");
			}
			JsonArray arr = req.getAsJsonArray("commands");
			if (arr == null || arr.isEmpty()) return error("no commands");
			if (arr.size() > MAX_COMMANDS) return error("too many commands");
			for (JsonElement e : arr) {
				String c = e.getAsString();
				if (!KILL.matcher(c).matches() && !EXEC.matcher(c).matches()) {
					return error("command not allowed: " + c);
				}
				commands.add(c);
			}
		} catch (Exception e) {
			return error("bad json: " + e.getMessage());
		}

		Job job = new Job(commands, wanted);
		jobs.add(job);
		return await(job.future);
	}

	/** Called on the server thread at the end of every tick (see McBuildMod). */
	public void tick() {
		Job job = jobs.peek();
		if (job == null) return;
		try {
			if (job.playerName == null) {
				ServerPlayer target = null;
				for (ServerPlayer p : server.getPlayerList().getPlayers()) {
					if (job.wantedPlayer == null || p.getName().getString().equalsIgnoreCase(job.wantedPlayer)) {
						target = p;
						break;
					}
				}
				if (target == null) {
					jobs.poll();
					job.future.complete(error("no matching player is online - join the world first"));
					return;
				}
				job.playerName = target.getName().getString();
				job.source = server.createCommandSourceStack();
			}

			long end = System.nanoTime() + TICK_BUDGET_NANOS;
			do {
				String cmd = job.commands.get(job.next++);
				String real;
				if (cmd.startsWith("execute at @p ")) {
					// swap the selector for the real name so it works from the server console source
					real = "execute at " + job.playerName + " " + cmd.substring("execute at @p ".length());
				} else {
					// hop into the player's dimension first so marker lookups happen there
					real = "execute at " + job.playerName + " run " + cmd;
				}
				server.getCommands().performPrefixedCommand(job.source, real);
			} while (job.next < job.commands.size() && System.nanoTime() < end);

			if (job.next >= job.commands.size()) {
				jobs.poll();
				McBuildMod.LOGGER.info("AI Builder Bridge ran {} commands for {}", job.next, job.playerName);
				JsonObject o = new JsonObject();
				o.addProperty("sent", job.next);
				o.addProperty("player", job.playerName);
				job.future.complete(o);
			}
		} catch (Throwable t) {
			jobs.poll();
			job.future.completeExceptionally(t);
		}
	}

	private static JsonObject await(CompletableFuture<JsonObject> fut) {
		try {
			return fut.get(JOB_TIMEOUT_MINUTES, TimeUnit.MINUTES);
		} catch (Exception e) {
			return error("game did not respond: " + e);
		}
	}
}
