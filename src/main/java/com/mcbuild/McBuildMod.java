package com.mcbuild;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * AI Builder Bridge.
 * Starts a tiny localhost-only server whenever a world (or dedicated server) starts,
 * so mcbuild.py can send build commands straight into the running game.
 */
public class McBuildMod implements ModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("mcbuild_bridge");

	private BridgeServer bridge;

	@Override
	public void onInitialize() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			stopBridge();
			try {
				bridge = new BridgeServer(server);
				bridge.start();
			} catch (IOException e) {
				LOGGER.error("AI Builder Bridge could not start", e);
			}
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> stopBridge());
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			BridgeServer b = bridge;
			if (b != null) b.tick();
		});
	}

	private void stopBridge() {
		if (bridge != null) {
			bridge.stop();
			bridge = null;
		}
	}
}
