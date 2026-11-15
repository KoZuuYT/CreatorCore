package dev.creatorcore;

import dev.creatorcore.update.AutoUpdater;
import java.io.File;
import org.bukkit.plugin.java.JavaPlugin;

/** Classe principal do CreatorCore: hub de criações customizadas para o vanilla. */
public final class CreatorCorePlugin extends JavaPlugin {

    private AutoUpdater updater;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        updater = new AutoUpdater(this);
        getServer().getPluginManager().registerEvents(updater, this);
        var command = getCommand("creatorcore");
        if (command != null) {
            command.setExecutor(updater);
            command.setTabCompleter(updater);
        }
        updater.start();
        getLogger().info("CreatorCore " + getPluginMeta().getVersion() + " ativado.");
    }

    /** Arquivo .jar deste plugin (usado pelo atualizador para substituí-lo). */
    public File jarFile() {
        return getFile();
    }
}
