package dev.creatorcore.registry;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Registro central de criações. Cada tipo tem seu próprio espaço de ids. Uso na thread principal. */
public final class CreationRegistry {

    private static final Pattern VALID_ID = Pattern.compile("[a-z0-9_]+");

    private final Map<CreationType, Map<String, Creation>> byType = new EnumMap<>(CreationType.class);

    public CreationRegistry() {
        for (CreationType type : CreationType.values()) {
            byType.put(type, new TreeMap<>());
        }
    }

    public static boolean isValidId(String id) {
        return id != null && VALID_ID.matcher(id).matches();
    }

    /** @throws IllegalArgumentException se o id for inválido ou já estiver registrado nesse tipo. */
    public void register(Creation creation) {
        if (!isValidId(creation.id())) {
            throw new IllegalArgumentException("id inválido '" + creation.id() + "' (use minúsculas, números e _)");
        }
        if (byType.get(creation.type()).putIfAbsent(creation.id(), creation) != null) {
            throw new IllegalArgumentException("id duplicado '" + creation.id() + "'");
        }
    }

    public Optional<Creation> get(CreationType type, String id) {
        return Optional.ofNullable(byType.get(type).get(id));
    }

    /** Todas as criações do tipo, ordenadas por id. */
    public Collection<Creation> all(CreationType type) {
        return Collections.unmodifiableCollection(byType.get(type).values());
    }

    public void clear(CreationType type) {
        byType.get(type).clear();
    }
}
