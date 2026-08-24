package dev.lunabridge.paper;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Lifecycle owner for Paper adapters only. Discord and network authority intentionally live on Velocity. */
public final class LunaBridgePaperPlugin extends JavaPlugin {
    private PaperNetworkClient network;

    @Override public void onEnable() {
        PaperSettings settings;
        try { settings = PaperSettings.load(this); }
        catch (RuntimeException configurationFailure) {
            getLogger().severe("LunaBridge configuration rejected; LunaChat remains untouched: " + configurationFailure.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        Plugin lunaChat = Bukkit.getPluginManager().getPlugin("LunaChat");
        if (lunaChat == null || !lunaChat.isEnabled()) {
            getLogger().severe("LunaChat is required; disabling bridge without registering any chat listeners.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        try { network = new PaperNetworkClient(this, settings); }
        catch (IllegalArgumentException invalidPass) {
            getLogger().severe("LunaBridge network configuration rejected (" + invalidPass.getMessage() + "); local LunaChat will continue normally.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        LunaChatAdapter adapter = new LunaChatAdapter(this, settings, network);
        network.attachAdapter(adapter);
        getServer().getMessenger().registerOutgoingPluginChannel(this, PaperNetworkClient.CHANNEL);
        getServer().getMessenger().registerIncomingPluginChannel(this, PaperNetworkClient.CHANNEL, network);
        getServer().getPluginManager().registerEvents(adapter, this);
        Bukkit.getScheduler().runTaskTimer(this, network::tick, 20L, 20L);
        getLogger().info("LunaBridge Paper enabled as a LunaChat observer; local chat is never intercepted.");
    }

    @Override public void onDisable() {
        if (network != null) network.close();
        getServer().getMessenger().unregisterIncomingPluginChannel(this, PaperNetworkClient.CHANNEL);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, PaperNetworkClient.CHANNEL);
        network = null;
    }
}
