package dev.creatorcore;

import dev.creatorcore.block.BlockListener;
import dev.creatorcore.block.CustomBlocks;
import dev.creatorcore.block.MiningManager;
import dev.creatorcore.command.CreatorCommand;
import dev.creatorcore.item.ItemLoader;
import dev.creatorcore.pack.ResourcePackManager;
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
    private ResourcePackManager resourcePack;
    private ItemLoader itemLoader;
    private CustomBlocks blocks;
    private MiningManager mining;
    private NamespacedKey idKey;
    private NamespacedKey blockKey;
    private NamespacedKey mobKey;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        idKey = new NamespacedKey(this, "id");
        blockKey = new NamespacedKey(this, "bloco");
        mobKey = new NamespacedKey(this, "mob");

        blocks = new CustomBlocks(this);
        itemLoader = new ItemLoader(this, registry);
        itemLoader.saveDefaults();
        var loaded = itemLoader.load();

        var plugins = getServer().getPluginManager();
        updater = new AutoUpdater(this);
        plugins.registerEvents(updater, this);
        resourcePack = new ResourcePackManager(this);
        plugins.registerEvents(resourcePack, this);
        mining = new MiningManager(this, blocks);
        plugins.registerEvents(mining, this);
        plugins.registerEvents(new BlockListener(this, blocks, mining), this);
        plugins.registerEvents(new dev.creatorcore.mob.MobListener(this), this);
        plugins.registerEvents(new dev.creatorcore.item.AbilityListener(this), this);
        plugins.registerEvents(new Listener() {
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
        resourcePack.start();
        getLogger().info("CreatorCore " + getPluginMeta().getVersion() + " ativado (" + loaded.items()
                + " item(ns), " + loaded.blocks() + " bloco(s), " + loaded.mobs() + " mob(s), " + loaded.recipes() + " receita(s)).");
    }

    @Override
    public void onDisable() {
        if (mining != null) {
            mining.stopAll();
        }
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

    public ResourcePackManager resourcePack() {
        return resourcePack;
    }

    public ItemLoader itemLoader() {
        return itemLoader;
    }

    public CustomBlocks blocks() {
        return blocks;
    }

    /** Chave {@code creatorcore:id} gravada nos itens (e itens de bloco) criados pelo plugin. */
    public NamespacedKey idKey() {
        return idKey;
    }

    /** Chave {@code creatorcore:bloco} gravada nos itens que colocam um bloco customizado. */
    public NamespacedKey blockKey() {
        return blockKey;
    }

    /** Chave {@code creatorcore:mob} gravada nos mobs invocados pelo plugin. */
    public NamespacedKey mobKey() {
        return mobKey;
    }

    /** Arquivo .jar deste plugin (usado pelo atualizador para substituí-lo). */
    public File jarFile() {
        return getFile();
    }
}
