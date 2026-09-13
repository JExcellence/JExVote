package de.jexcellence.vote.bedrock;

import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.flattener.ComponentFlattener;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/** Converts rich Java descriptions at the plain-string Cumulus boundary. */
final class BedrockFormText {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.builder()
            .flattener(ComponentFlattener.builder()
                    .mapper(TextComponent.class, TextComponent::content)
                    .mapper(TranslatableComponent.class, BedrockFormText::translationFallback)
                    .build())
            .build();

    private BedrockFormText() {
        // Cumulus strings do not interpret MiniMessage or Java translation components.
    }

    static @NotNull String plain(@NotNull String richText) {
        String withoutLegacy = PLAIN.serialize(LegacyComponentSerializer.legacySection().deserialize(richText));
        return PLAIN.serialize(MINI_MESSAGE.deserialize(withoutLegacy));
    }

    private static @NotNull String translationFallback(@NotNull TranslatableComponent component) {
        if (component.fallback() != null) {
            return component.fallback();
        }
        // Vanilla translation keys cannot be resolved by a Bedrock form. Keep a
        // readable material label rather than leaking item.minecraft.* to players.
        String key = component.key();
        String name = key.substring(key.lastIndexOf('.') + 1).replace('_', ' ');
        if (name.isEmpty()) {
            return key;
        }
        return name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
    }
}
