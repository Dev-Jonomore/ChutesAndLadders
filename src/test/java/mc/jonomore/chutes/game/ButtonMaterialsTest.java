package mc.jonomore.chutes.game;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ButtonMaterialsTest {

    @ParameterizedTest
    @CsvSource({
            "POLISHED_BLACKSTONE_BRICKS, POLISHED_BLACKSTONE_BUTTON",
            "CHISELED_POLISHED_BLACKSTONE, POLISHED_BLACKSTONE_BUTTON",
            "DEEPSLATE_TILES, POLISHED_BLACKSTONE_BUTTON",
            "CRYING_OBSIDIAN, POLISHED_BLACKSTONE_BUTTON",
            "BOOKSHELF, OAK_BUTTON",
            "OAK_PLANKS, OAK_BUTTON",
            "STRIPPED_OAK_WOOD, OAK_BUTTON",
            "DARK_OAK_PLANKS, DARK_OAK_BUTTON",
            "DARK_OAK_LOG, DARK_OAK_BUTTON",
            "PALE_OAK_PLANKS, PALE_OAK_BUTTON",
            "SPRUCE_PLANKS, SPRUCE_BUTTON",
            "STONE_BRICKS, STONE_BUTTON",
            "MOSSY_STONE_BRICKS, STONE_BUTTON",
            "MOSSY_COBBLESTONE, STONE_BUTTON",
            "POLISHED_ANDESITE, STONE_BUTTON",
            "SMOOTH_STONE, STONE_BUTTON",
            "LIGHT_GRAY_CONCRETE, STONE_BUTTON",
            "SMOOTH_QUARTZ, PALE_OAK_BUTTON",
            "CHISELED_QUARTZ_BLOCK, PALE_OAK_BUTTON",
            "CALCITE, PALE_OAK_BUTTON",
            "HAY_BLOCK, BAMBOO_BUTTON",
            "MOSS_BLOCK, BAMBOO_BUTTON",
            "BROWN_WOOL, SPRUCE_BUTTON",
            "PRISMARINE_BRICKS, WARPED_BUTTON",
            "PACKED_ICE, WARPED_BUTTON",
            "LIGHT_BLUE_TERRACOTTA, WARPED_BUTTON",
            "TERRACOTTA, ACACIA_BUTTON",
            "RED_SANDSTONE, ACACIA_BUTTON",
            "SANDSTONE, BIRCH_BUTTON",
            "BRICKS, MANGROVE_BUTTON",
            "bookshelf, OAK_BUTTON",
    })
    void picksTheButtonThatBlendsIn(String support, String expected) {
        assertEquals(expected, ButtonMaterials.buttonNameFor(support));
    }
}
