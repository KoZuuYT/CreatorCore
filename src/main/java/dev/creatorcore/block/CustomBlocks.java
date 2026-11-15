package dev.creatorcore.block;

import dev.creatorcore.CreatorCorePlugin;
import dev.creatorcore.registry.CreationRegistry;
import dev.creatorcore.registry.CreationType;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.block.Block;

/** Reconhece, no mundo, os blocos customizados (pelo estado do note block). Uso na thread principal. */
public final class CustomBlocks {

    private final BlockStates states;
    private final Map<String, BlockDefinition> byState = new HashMap<>();

    public CustomBlocks(CreatorCorePlugin plugin) {
        this.states = new BlockStates(plugin);
    }

    public BlockStates states() {
        return states;
    }

    /** Refaz o índice estado → bloco a partir do registro (chamar após carregar os blocos). */
    public void rebuild(CreationRegistry registry) {
        byState.clear();
        for (var creation : registry.all(CreationType.BLOCK)) {
            BlockDefinition block = (BlockDefinition) creation;
            byState.put(states.data(block.state()).getAsString(), block);
        }
    }

    /** @return a definição do bloco customizado em {@code block}, ou {@code null} se for outro bloco. */
    public BlockDefinition at(Block block) {
        if (block.getType() != Material.NOTE_BLOCK) {
            return null;
        }
        return byState.get(block.getBlockData().getAsString());
    }
}
