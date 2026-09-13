package de.jexcellence.vote.bedrock;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BedrockFormTextTest {

    @Test
    void removesThePerCharacterHexMarkupSeenInTheVoteShop() {
        String serialized = MiniMessage.miniMessage().serialize(MiniMessage.miniMessage()
                .deserialize("<italic><gradient:#A5F3FC:#06B6D4>1x Dragon Crate Key</gradient></italic>"));
        assertEquals("1x Dragon Crate Key", BedrockFormText.plain(serialized));
    }

    @Test
    void keepsGermanWordsAmountsAndLineBreaksWithoutFormatting() {
        assertEquals("Schluessel\n25 Punkte", BedrockFormText.plain(
                "<gold>Schluessel</gold><newline><bold>25 Punkte</bold>"));
        assertEquals("Backpack Upgrader", BedrockFormText.plain(
                "<hover:show_text:'Details'><aqua>Backpack Upgrader</aqua></hover>"));
    }

    @Test
    void translationComponentsHaveReadableFallbacksNotJavaKeys() {
        assertEquals("2x Diamond sword", BedrockFormText.plain("2x <lang:item.minecraft.diamond_sword>"));
        String translated = MiniMessage.miniMessage().serialize(
                Component.translatable("item.minecraft.diamond", "Diamant"));
        assertEquals("Diamant", BedrockFormText.plain(translated));
    }

    @Test
    void preservesPlainPunctuationAndRemovesLegacyFormatting() {
        assertEquals("2 < 3 & 5 > 4", BedrockFormText.plain("2 < 3 & 5 > 4"));
        assertEquals("25 Coins", BedrockFormText.plain("\u00a7a25 \u00a7lCoins"));
        assertEquals("", BedrockFormText.plain(""));
    }
}
