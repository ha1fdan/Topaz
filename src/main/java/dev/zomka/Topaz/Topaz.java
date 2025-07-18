package dev.zomka.Topaz;
import com.google.inject.Inject;
import com.moandjiezana.toml.Toml;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.slf4j.Logger;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static net.kyori.adventure.text.Component.text;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
@Plugin(
        id = "topaz",
        name = "Topaz",
        version = dev.zomka.Topaz.BuildConstants.VERSION,
        description = "A simple Anti-VPN that doesn't depend on it's own weird, unknown API. Inspired by egg82/Laarryy's Anti-VPN plugin.",
        authors = {"Zomka"}
)
public class Topaz {
    private final ProxyServer proxy;
    private final List<String> allowedIPs;
    private final List<String> blockedIPs;
    @Inject
    private Logger logger;
    private Toml config;
    @Inject @DataDirectory
    private Path configFolder;
    @SuppressWarnings("ResultOfMethodCallIgnored")
    private Toml loadConfig(Path path) {
        File folder = path.toFile();
        File file = new File(folder, "config.toml");
        if (!file.getParentFile().exists()) {
            file.getParentFile().mkdirs();
        }
        if (!file.exists()) {
            try (InputStream input = getClass().getResourceAsStream("/" + file.getName())) {
                if (input != null) {
                    Files.copy(input, file.toPath());
                } else {
                    file.createNewFile();
                }
            } catch (IOException exception) {
                exception.printStackTrace();
                return null;
            }
        }
        return new Toml().read(file);
    }
    @SuppressWarnings("ResultOfMethodCallIgnored")
    public void configLoader(Path folder) throws IOException {
        config = loadConfig(folder);
        assert config != null;
        Toml options = config.getTable("Options");
        double configVersion = options.getDouble("configVersion");
        if (configVersion != 2.0) {
            logger.error("Your config is outdated! Your current config was backed up and a new one was generated!");
            Files.move(folder.resolve("config.toml"), folder.resolve("config.toml.backup"), StandardCopyOption.REPLACE_EXISTING);
            File file = new File(folder.toFile(), "config.toml");
            if (!file.exists()) {
                try (InputStream input = getClass().getResourceAsStream("/" + file.getName())) {
                    if (input != null) {
                        Files.copy(input, file.toPath());
                    } else {
                        file.createNewFile();
                    }
                } catch (IOException exception) {
                    exception.printStackTrace();
                }
            }
        }
    }
    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        try {
            configLoader(configFolder);
        } catch (Exception e) {
            logger.error("Failed to load config!", e);
            return;
        }
        logger.info("Startup successful.");
        proxy.getScheduler().buildTask(this, () -> {allowedIPs.clear();blockedIPs.clear();logger.info("Cache cleared!");}).delay(0, TimeUnit.SECONDS).repeat(config.getTable("Options").getLong("cacheClearInterval").intValue(), TimeUnit.SECONDS).schedule();
    }
    @Inject
    public Topaz(ProxyServer proxy) {
        this.proxy = proxy;
        this.allowedIPs = new ArrayList<>();
        this.blockedIPs = new ArrayList<>();
        CommandManager commandManager = proxy.getCommandManager();
        CommandMeta meta = commandManager.metaBuilder("topazreload").build();
        commandManager.register(meta, new ReloadCommand());
    }

    private boolean isLocalAddress(String ip) {
        return ip.startsWith("127.") || ip.equals("::1") || ip.startsWith("10.") || ip.startsWith("192.168.") || ip.matches("^172\\.(1[6-9]|2[0-9]|3[0-1])\\..*");
    }

    @Subscribe
    public void onLoginEvent(LoginEvent e) {
        Toml messages = config.getTable("Messages");
        MiniMessage miniMessage = MiniMessage.miniMessage();
        String playerIp = e.getPlayer().getRemoteAddress().getHostString();

        if (isLocalAddress(playerIp)) { logger.info(e.getPlayer().getUsername() + "'s IP address is a local one. Ignoring them... (" + playerIp + ")"); return; }
        if (e.getPlayer().hasPermission("topaz.bypass") || allowedIPs.contains(playerIp)) { return; }

        Toml options = config.getTable("Options");
        if (blockedIPs.contains(playerIp)) {
            e.setResult(ResultedEvent.ComponentResult.denied(miniMessage.deserialize(messages.getString("usingVPN"))));
            logger.warn(e.getPlayer().getUsername() + " (" + e.getPlayer().getUniqueId() + ") failed the proxy check! Cached blocked IP! (" + playerIp + ")");
            return;
        }
        try {
            URL url = new URL("https://api.ha1fdan.xyz/ip/" + playerIp);
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("GET");
            if (con.getResponseCode() != HttpURLConnection.HTTP_OK) {
                e.setResult(ResultedEvent.ComponentResult.denied(miniMessage.deserialize(messages.getString("errorKick"))));
                throw new RuntimeException("Failed to connect! HTTP error code: " + con.getResponseCode());
            }
            BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()));
            String inputLine;
            StringBuilder response = new StringBuilder();
            while ((inputLine = in.readLine()) != null) { response.append(inputLine); }
            in.close();
            JsonObject ipInfo = JsonParser.parseString(response.toString()).getAsJsonObject();
            if (ipInfo == null) {
                logger.error("Something went wrong! Make sure your API is running!\n\nHere's the JSON that Topaz received:");
                logger.error(response.toString());
                e.setResult(ResultedEvent.ComponentResult.denied(miniMessage.deserialize(messages.getString("errorKick"))));
                return;
            }
            if (ipInfo.has("isVpnOrDc") && ipInfo.get("isVpnOrDc").getAsBoolean()) {
                blockedIPs.add(playerIp);
                e.setResult(ResultedEvent.ComponentResult.denied(miniMessage.deserialize(messages.getString("usingVPN"))));
                logger.warn(e.getPlayer().getUsername() + " (" + e.getPlayer().getUniqueId() + ") failed the proxy check! (" + playerIp + ")");
            } else {
                allowedIPs.add(playerIp);
            }
        } catch (IOException ex) {
            logger.error("Something went wrong! Make sure your local API is running and accessible!");
            ex.printStackTrace();
            e.setResult(ResultedEvent.ComponentResult.denied(miniMessage.deserialize(messages.getString("errorKick"))));
        }
    }

    public final class ReloadCommand implements SimpleCommand {
        @Override
        public void execute(final Invocation invocation) {
            CommandSource source = invocation.source();
            try {
                configLoader(configFolder);
                allowedIPs.clear();
                blockedIPs.clear();
                source.sendMessage(text("Reloaded config!"));
            } catch (IOException e) {
                source.sendMessage(text("Failed to reload config because of " + e.getMessage()));
                e.printStackTrace();
            }
        }
        @Override
        public boolean hasPermission(final Invocation invocation) {
            return invocation.source().hasPermission("topaz.reload");
        }
    }
}
