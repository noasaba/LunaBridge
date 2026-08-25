package dev.lunabridge.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.lunachat.api.LunaChatApiProvider;
import dev.lunachat.api.LunaChatIntegrationApi;
import org.slf4j.Logger;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Discord-only connector; LunaChat owns Minecraft routing and backend transport. */
@Plugin(id="lunabridge-velocity", name="LunaBridge Velocity", version=LunaBridgeBuildVersion.VERSION,
        authors={"LunaBridge"}, dependencies={@Dependency(id="lunachat")})
public final class LunaBridgeVelocityPlugin {
    private final ProxyServer proxy; private final Logger logger; private final Path dataDirectory;
    private final Set<UUID> connected = ConcurrentHashMap.newKeySet();
    private DiscordGateway discord = DiscordGateway.disabled();
    private LunaChatIntegrationApi.Subscription subscription; private SeenPlayerStore seenPlayers; private VelocitySettings settings;

    @Inject public LunaBridgeVelocityPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy=proxy; this.logger=logger; this.dataDirectory=dataDirectory;
    }
    @Subscribe public void initialize(ProxyInitializeEvent event) {
        try {
            settings=VelocitySettings.load(dataDirectory);
            Object instance=proxy.getPluginManager().getPlugin("lunachat").flatMap(c->c.getInstance()).orElse(null);
            if (!(instance instanceof LunaChatApiProvider provider)) throw new IllegalStateException("LunaChat-Velocity Integration API v1 is unavailable");
            LunaChatIntegrationApi api=provider.integrationApi();
            if (api.runtimeRole()!=LunaChatIntegrationApi.RuntimeRole.NETWORK_AUTHORITY ||
                    !api.capabilities().containsAll(Set.of(LunaChatIntegrationApi.Capability.OBSERVE_ACCEPTED_MESSAGES,
                            LunaChatIntegrationApi.Capability.PUBLISH_EXTERNAL_MESSAGES)))
                throw new IllegalStateException("LunaChat is not the network authority required by LunaBridge");
            for (String id:settings.discordChannels.keySet()) {
                var channel=api.findChannel(new LunaChatIntegrationApi.ChannelId(id)).orElseThrow(()->new IllegalStateException("Unknown LunaChat channel id "+id));
                if (!channel.acceptsExternalMessages()) throw new IllegalStateException("External messages are disabled for "+id);
            }
            seenPlayers=new SeenPlayerStore(dataDirectory);
            discord=JdaDiscordGateway.start(api,proxy,settings,logger);
            subscription=api.observeAcceptedMessages(discord::relayMinecraft);
            discord.notification("startup",Map.of("online",Integer.toString(proxy.getPlayerCount()),"max","?"));
            logger.info("LunaBridge enabled as a Discord-only LunaChat connector.");
        } catch (IOException|RuntimeException failure) { cleanup(); logger.error("LunaBridge did not start; LunaChat remains untouched.",failure); }
    }
    @Subscribe public void connected(ServerConnectedEvent event) {
        if(settings==null)return; String to=event.getServer().getServerInfo().getName(); var values=values(event.getPlayer().getUsername(),event.getPlayer().getUniqueId(),"",to);
        if(event.getPreviousServer().isEmpty()) { connected.add(event.getPlayer().getUniqueId()); try {
            discord.notification(seenPlayers!=null&&seenPlayers.markFirst(event.getPlayer().getUniqueId())?"first-login":"login",values);
        } catch(IOException failure){logger.error("Could not persist first-login state",failure);} }
        else { String from=event.getPreviousServer().orElseThrow().getServerInfo().getName(); if(!from.equals(to))discord.notification("server-switch",values(event.getPlayer().getUsername(),event.getPlayer().getUniqueId(),from,to)); }
    }
    @Subscribe public void disconnected(DisconnectEvent event){if(connected.remove(event.getPlayer().getUniqueId()))discord.notification("quit",values(event.getPlayer().getUsername(),event.getPlayer().getUniqueId(),"",""));}
    @Subscribe public void shutdown(ProxyShutdownEvent event){discord.finalNotification("shutdown",Map.of("online",Integer.toString(proxy.getPlayerCount()),"max","?"));cleanup();}
    private void cleanup(){if(subscription!=null)try{subscription.close();}catch(RuntimeException e){logger.warn("Could not close LunaChat subscription",e);}subscription=null;try{discord.close();}catch(RuntimeException e){logger.warn("Could not close Discord",e);}discord=DiscordGateway.disabled();seenPlayers=null;settings=null;}
    private Map<String,String> values(String player,UUID uuid,String from,String to){return Map.of("player",player,"uuid",uuid.toString(),"server",to,"fromServer",from,"toServer",to,"online",Integer.toString(proxy.getPlayerCount()),"max","?");}
}
