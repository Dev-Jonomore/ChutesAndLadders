package mc.jonomore.chutes;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Entry point. Builds {@link Services} and registers the /chutes command tree.
 * Wiring of the implementations is added by the integrator once the workstreams land.
 */
public final class ChutesPlugin extends JavaPlugin {

    private ChutesConfig config;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            config = ChutesConfig.load(getConfig());
        } catch (IllegalArgumentException e) {
            getSLF4JLogger().error("Invalid config.yml: {}", e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    public ChutesConfig chutesConfig() {
        return config;
    }
}
