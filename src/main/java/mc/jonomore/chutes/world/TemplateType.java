package mc.jonomore.chutes.world;

public enum TemplateType {
    ROOM,
    BREAK,
    /** Room of a ladder: long and quick to cross. Falls back to ROOM templates when none fit. */
    LADDER,
    /** Room of a chute: long and quick to cross. Falls back to ROOM templates when none fit. */
    CHUTE,
    ARENA
}
