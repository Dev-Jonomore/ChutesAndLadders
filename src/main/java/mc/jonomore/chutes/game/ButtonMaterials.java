package mc.jonomore.chutes.game;

import java.util.Locale;
import org.bukkit.Material;

/** Picks the vanilla button that blends best into the block it is attached to. */
final class ButtonMaterials {

    private static final String DEFAULT = "STONE_BUTTON";

    /** Checked in order; the first rule with a matching fragment wins, so specific names come first. */
    private static final String[][] RULES = {
            // Wood families, longest names first so DARK_OAK and PALE_OAK are not read as OAK.
            {"DARK_OAK_BUTTON", "DARK_OAK"},
            {"PALE_OAK_BUTTON", "PALE_OAK"},
            {"SPRUCE_BUTTON", "SPRUCE"},
            {"BIRCH_BUTTON", "BIRCH"},
            {"JUNGLE_BUTTON", "JUNGLE"},
            {"ACACIA_BUTTON", "ACACIA"},
            {"MANGROVE_BUTTON", "MANGROVE"},
            {"CHERRY_BUTTON", "CHERRY"},
            {"BAMBOO_BUTTON", "BAMBOO"},
            {"CRIMSON_BUTTON", "CRIMSON"},
            {"WARPED_BUTTON", "WARPED"},
            {"OAK_BUTTON", "OAK", "BOOKSHELF", "CRAFTING_TABLE", "BARREL", "CHEST", "LECTERN", "COMPOSTER",
                    "BEEHIVE", "LOOM"},
            // Dark stone.
            {"POLISHED_BLACKSTONE_BUTTON", "BLACKSTONE", "DEEPSLATE", "OBSIDIAN", "BASALT", "COAL_BLOCK", "BLACK_",
                    "SCULK", "NETHER_BRICK", "GRAY_CONCRETE", "GRAY_WOOL", "GRAY_TERRACOTTA", "TUFF"},
            // Colours, before the generic sandstone / terracotta rules below.
            {"WARPED_BUTTON", "LIGHT_BLUE_", "CYAN_", "BLUE_", "PRISMARINE", "ICE", "DIAMOND_BLOCK", "LAPIS"},
            {"CHERRY_BUTTON", "PINK_"},
            {"CRIMSON_BUTTON", "PURPUR", "PURPLE_", "MAGENTA_", "AMETHYST"},
            {"BAMBOO_BUTTON", "HAY_BLOCK", "YELLOW_", "GOLD_BLOCK", "SPONGE", "MOSS_BLOCK", "MOSS_CARPET", "LEAVES", "GREEN_", "LIME_",
                    "EMERALD_BLOCK", "MELON"},
            {"MANGROVE_BUTTON", "RED_NETHER", "RED_CONCRETE", "RED_WOOL", "RED_TERRACOTTA", "RED_MUSHROOM",
                    "REDSTONE_BLOCK", "NETHERRACK", "NETHER_WART"},
            {"ACACIA_BUTTON", "RED_SAND", "ORANGE_", "COPPER", "TERRACOTTA", "SHROOMLIGHT", "PUMPKIN"},
            {"SPRUCE_BUTTON", "BROWN_", "DIRT", "PODZOL", "MUD", "SOUL_S", "NOTE_BLOCK", "JUKEBOX"},
            {"PALE_OAK_BUTTON", "QUARTZ", "CALCITE", "WHITE_", "SNOW", "DIORITE", "BONE_BLOCK", "IRON_BLOCK",
                    "SEA_LANTERN"},
            {"BIRCH_BUTTON", "SANDSTONE", "SAND", "END_STONE", "GLOWSTONE"},
    };

    private ButtonMaterials() {
    }

    /** Button material name for a support block's material name, e.g. BOOKSHELF gives OAK_BUTTON. */
    static String buttonNameFor(String supportName) {
        String name = supportName.toUpperCase(Locale.ROOT);
        // Plain clay bricks are red; every other "...BRICKS" block is handled by the rules or is stone.
        if (name.equals("BRICKS") || name.startsWith("BRICK_")) {
            return "MANGROVE_BUTTON";
        }
        // Light gray reads as plain stone, not as the dark grays matched below.
        if (name.startsWith("LIGHT_GRAY_")) {
            return DEFAULT;
        }
        for (String[] rule : RULES) {
            for (int i = 1; i < rule.length; i++) {
                if (name.contains(rule[i])) {
                    return rule[0];
                }
            }
        }
        return DEFAULT;
    }

    static Material buttonFor(Material support) {
        Material button = Material.matchMaterial(buttonNameFor(support.name()));
        return button == null ? Material.STONE_BUTTON : button;
    }
}
