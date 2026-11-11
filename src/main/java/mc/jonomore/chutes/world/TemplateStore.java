package mc.jonomore.chutes.world;

import java.util.List;
import java.util.Optional;

public interface TemplateStore {

    /**
     * Templates of a type. Never empty: when nothing is saved for the type, the built-in
     * fallback templates are returned. Order is stable (sorted by id).
     */
    List<RoomTemplate> all(TemplateType type);

    Optional<RoomTemplate> get(TemplateType type, String id);

    void reload();
}
