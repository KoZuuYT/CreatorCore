package dev.creatorcore.block;

import dev.creatorcore.CreatorCorePlugin;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.GameMode;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDamageAbortEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

/**
 * Mineração com dureza e ferramenta próprias. O note block tem dureza fixa no cliente, então, enquanto o
 * jogador bate num bloco customizado, ele recebe Fadiga de Mineração (invisível) para que o cliente não
 * quebre o bloco sozinho; o servidor acumula o progresso a cada tick, mostra as rachaduras e quebra
 * o bloco quando chega a 100%. Tudo é desfeito ao parar de bater, trocar de alvo ou sair do jogo.
 */
public final class MiningManager implements Listener {

    /** Fadiga forte o bastante para o cliente nunca terminar de quebrar sozinho. */
    private static final int FATIGUE_AMPLIFIER = 4;

    private static final class Session {
        final Block block;
        final BlockDefinition definition;
        final PotionEffect previousFatigue;
        BukkitTask task;
        double progress;

        Session(Block block, BlockDefinition definition, PotionEffect previousFatigue) {
            this.block = block;
            this.definition = definition;
            this.previousFatigue = previousFatigue;
        }
    }

    private final CreatorCorePlugin plugin;
    private final CustomBlocks blocks;
    private final Map<UUID, Session> sessions = new HashMap<>();

    public MiningManager(CreatorCorePlugin plugin, CustomBlocks blocks) {
        this.plugin = plugin;
        this.blocks = blocks;
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(BlockDamageEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() != GameMode.SURVIVAL) {
            return;
        }
        BlockDefinition definition = blocks.at(event.getBlock());
        if (definition == null) {
            return;
        }
        stop(player);
        start(player, event.getBlock(), definition);
    }

    @EventHandler
    public void onAbort(BlockDamageAbortEvent event) {
        stop(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        stop(event.getPlayer());
    }

    /** Encerra todas as sessões (ao desligar o plugin), devolvendo os efeitos. */
    public void stopAll() {
        for (UUID id : java.util.List.copyOf(sessions.keySet())) {
            Player player = plugin.getServer().getPlayer(id);
            if (player != null) {
                stop(player);
            }
        }
        sessions.clear();
    }

    private void start(Player player, Block block, BlockDefinition definition) {
        Session session = new Session(block, definition, player.getPotionEffect(PotionEffectType.MINING_FATIGUE));
        sessions.put(player.getUniqueId(), session);
        if (definition.hardness() == 0) {
            // Quebra instantânea: sem fadiga nem animação.
            sessions.remove(player.getUniqueId());
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (blocks.at(block) == definition) {
                    player.breakBlock(block);
                }
            });
            return;
        }
        player.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, PotionEffect.INFINITE_DURATION,
                FATIGUE_AMPLIFIER, false, false, false));
        session.task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> tick(player, session), 1L, 1L);
    }

    private void tick(Player player, Session session) {
        if (!player.isOnline() || player.getGameMode() != GameMode.SURVIVAL
                || sessions.get(player.getUniqueId()) != session
                || blocks.at(session.block) != session.definition
                || !session.block.equals(player.getTargetBlockExact(6))) {
            stop(player);
            return;
        }
        // Chão verificado no servidor (isOnGround() vem do cliente e é obsoleto).
        boolean onGround = player.getWorld().hasCollisionsIn(player.getBoundingBox().shift(0, -0.05, 0));
        session.progress += session.definition.damagePerTick(player.getInventory().getItemInMainHand(), onGround);
        if (session.progress >= 1) {
            Block block = session.block;
            stop(player);
            player.breakBlock(block);
            return;
        }
        player.sendBlockDamage(session.block.getLocation(), (float) session.progress);
    }

    /** Para a sessão do jogador (se houver), apaga as rachaduras e devolve o efeito anterior. */
    public void stop(Player player) {
        Session session = sessions.remove(player.getUniqueId());
        if (session == null) {
            return;
        }
        if (session.task != null) {
            session.task.cancel();
            player.removePotionEffect(PotionEffectType.MINING_FATIGUE);
            if (session.previousFatigue != null) {
                player.addPotionEffect(session.previousFatigue);
            }
        }
        player.sendBlockDamage(session.block.getLocation(), 0f);
    }
}
