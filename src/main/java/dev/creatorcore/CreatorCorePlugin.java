package dev.creatorcore;

import dev.creatorcore.command.CreatorCommand;
import dev.creatorcore.item.ItemLoader;
import dev.creatorcore.registry.CreationRegistry;
import dev.creatorcore.update.AutoUpdater;
import java.io.File;
import org.bukkit.NamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Classe principal do CreatorCore: hub de criações customizadas para o vanilla. */
public final class CreatorCorePlugin extends JavaPlugin {

    private final CreationRegistry registry = new CreationRegistry();
    private AutoUpdater updater;
    private ItemLoader itemLoader;
    private NamespacedKey idKey;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        idKey = new NamespacedKey(this, "id");

        itemLoader = new ItemLoader(this, registry);
        itemLoader.saveDefaults();
        var loaded = itemLoader.load();

        updater = new AutoUpdater(this);
        getServer().getPluginManager().registerEvents(updater, this);
        getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onJoin(PlayerJoinEvent event) {
                itemLoader.discoverFor(event.getPlayer());
            }
        }, this);

        var command = getCommand("creatorcore");
        if (command != null) {
            var executor = new CreatorCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }
        updater.start();
        getLogger().info("CreatorCore " + getPluginMeta().getVersion() + " ativado (" + loaded.items()
                + " item(ns), " + loaded.recipes() + " receita(s)).");
    }

    @Override
    public void onDisable() {
        if (itemLoader != null) {
            itemLoader.unregisterRecipes();
        }
    }

    public CreationRegistry registry() {
        return registry;
    }

    public AutoUpdater updater() {
        return updater;
    }

    public ItemLoader itemLoader() {
        return itemLoader;
    }

    /** Chave {@code creatorcore:id} gravada nos itens criados pelo plugin. */
    public NamespacedKey idKey() {
        return idKey;
    }

    /** Arquivo .jar deste plugin (usado pelo atualizador para substituí-lo). */
    public File jarFile() {
        return getFile();
    }
}
