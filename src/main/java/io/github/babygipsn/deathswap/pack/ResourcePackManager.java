package io.github.babygipsn.deathswap.pack;

import com.sun.net.httpserver.HttpServer;
import io.github.babygipsn.deathswap.Deathswap;
import io.github.babygipsn.deathswap.config.ConfigManager;
import io.github.babygipsn.deathswap.config.DeathswapConfig;
import io.github.babygipsn.deathswap.hud.HudFont;
import io.github.babygipsn.deathswap.lang.Lang;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Sends the HUD resource pack to vanilla clients and tracks whether they loaded it.
 * The pack is bundled in the jar; its SHA-1 is computed at startup so the client can verify the download.
 */
public final class ResourcePackManager {
	public static final UUID PACK_ID = UUID.nameUUIDFromBytes("deathswap:hud".getBytes(StandardCharsets.UTF_8));

	public enum Status { PENDING, LOADED, FAILED }

	private static final Map<UUID, Status> STATUS = new HashMap<>();
	private static byte[] zip = new byte[0];
	private static String sha1 = "";
	private static @Nullable HttpServer http;
	private static int hostedPort = -1;

	private ResourcePackManager() {
	}

	public static void load() {
		try (InputStream in = ResourcePackManager.class.getResourceAsStream("/deathswap/Deathswap-HUD.zip")) {
			zip = in.readAllBytes();
			sha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(zip));
			if (!sha1.equals(HudFont.packSha1)) {
				Deathswap.LOGGER.warn("Bundled pack SHA-1 {} differs from glyph table {}; regenerate the pack", sha1, HudFont.packSha1);
			}
		} catch (Exception e) {
			Deathswap.LOGGER.error("Could not load bundled resource pack", e);
		}
	}

	/** (Re)starts or stops the built-in HTTP host according to the config. */
	public static void reconfigure() {
		DeathswapConfig.ResourcePack cfg = ConfigManager.get().resourcePack;
		boolean wanted = cfg.mode == DeathswapConfig.PackMode.BUILTIN_HOST;
		if (wanted && http != null && hostedPort == cfg.hostPort) return;
		stopHost();
		if (!wanted) return;
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress(cfg.hostPort), 0);
			server.createContext("/", exchange -> {
				try (exchange) {
					if (!exchange.getRequestURI().getPath().endsWith(".zip")) {
						exchange.sendResponseHeaders(404, -1);
						return;
					}
					exchange.getResponseHeaders().add("Content-Type", "application/zip");
					exchange.sendResponseHeaders(200, zip.length);
					try (OutputStream out = exchange.getResponseBody()) {
						out.write(zip);
					}
				}
			});
			server.start();
			http = server;
			hostedPort = cfg.hostPort;
			Deathswap.LOGGER.info("Serving the HUD resource pack on port {}", cfg.hostPort);
			if (cfg.hostAddress.isBlank()) {
				Deathswap.LOGGER.warn("resourcePack.hostAddress is empty; set it to the public address players use to connect");
			}
		} catch (IOException e) {
			Deathswap.LOGGER.error("Could not start the resource pack host on port {}", cfg.hostPort, e);
		}
	}

	public static void stopHost() {
		if (http != null) {
			http.stop(0);
			http = null;
			hostedPort = -1;
		}
	}

	public static Optional<String> url() {
		DeathswapConfig.ResourcePack cfg = ConfigManager.get().resourcePack;
		return switch (cfg.mode) {
			case GITHUB -> Optional.of(cfg.githubBaseUrl + HudFont.packDistFile);
			case CUSTOM_URL -> cfg.customUrl.isBlank() ? Optional.empty() : Optional.of(cfg.customUrl);
			case BUILTIN_HOST -> cfg.hostAddress.isBlank() ? Optional.empty()
					: Optional.of("http://" + cfg.hostAddress + ":" + cfg.hostPort + "/" + HudFont.packDistFile);
			case OFF -> Optional.empty();
		};
	}

	public static void send(ServerPlayer player) {
		Optional<String> url = url();
		if (url.isEmpty() || zip.length == 0) {
			STATUS.put(player.getUUID(), Status.FAILED);
			return;
		}
		STATUS.put(player.getUUID(), Status.PENDING);
		Component prompt = Lang.tr(player, "deathswap.pack.prompt");
		player.connection.send(new ClientboundResourcePackPushPacket(PACK_ID, url.get(), sha1,
				ConfigManager.get().resourcePack.required, Optional.of(prompt)));
	}

	public static void onResponse(ServerPlayer player, ServerboundResourcePackPacket packet) {
		if (!packet.id().equals(PACK_ID)) return;
		switch (packet.action()) {
			case SUCCESSFULLY_LOADED -> STATUS.put(player.getUUID(), Status.LOADED);
			case DECLINED, FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD, DISCARDED -> {
				STATUS.put(player.getUUID(), Status.FAILED);
				Deathswap.LOGGER.info("{}: HUD resource pack {}", player.getName().getString(), packet.action());
			}
			default -> {
			}
		}
	}

	public static Status status(ServerPlayer player) {
		return STATUS.getOrDefault(player.getUUID(), Status.FAILED);
	}

	public static void forget(ServerPlayer player) {
		STATUS.remove(player.getUUID());
	}
}
