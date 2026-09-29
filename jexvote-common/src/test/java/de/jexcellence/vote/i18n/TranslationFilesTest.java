package de.jexcellence.vote.i18n;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the bundled translations: English and German carry the same keys, the keys the menus look up exist,
 * and the text follows the suite rules (no "[+]" token, no em-dash).
 */
class TranslationFilesTest {

    private static final List<String> REQUIRED = List.of(
            "vote_overview.intro.name",
            "vote_overview.intro.description",
            "vote_overview.votes.name",
            "vote_overview.points.name",
            "vote_overview.points.action",
            "vote_overview.streak.name",
            "vote_overview.site.no-link",
            "vote_overview.nav.party.name",
            "vote_overview.nav.party.description",
            "vote_rewards.every-vote.name",
            "vote_rewards.every-vote.description-points",
            "vote_rewards.multiplier.description-scope",
            "vote_rewards.freeze.status-full",
            "vote_rewards.freeze.status-short",
            "vote_streak.milestone.status-claimable",
            "vote_streak.milestone.status-locked",
            "vote_shop.back-to-menu",
            "vote_party.back-to-menu",
            "vote_party.no-pool.name",
            "vote.guaranteed-rewards",
            "vote.list-separator",
            "vote_help.desc.rewards-menu",
            "vote_admin.desc.overview",
            "vote_admin.desc.debug-services",
            "vote_admin.status.title",
            "vote_admin.status.heading",
            "vote_admin.status.row",
            "vote_admin.status.value.sites-limited",
            "vote_admin.status.value.economy-none",
            "vote_admin.status.value.economy-jexeconomy",
            "vote_admin.status.value.economy-vault",
            "vote_admin.status.tip.test",
            "gui.common.label.sites-ready",
            "gui.common.label.weekend-bonus",
            "gui.common.section.spend-on",
            "gui.common.section.site-bonus",
            "bedrock.nav.party",
            "bedrock.overview.intro",
            "bedrock.leaderboard.header-monthly",
            "bedrock.leaderboard.show-all-time",
            "bedrock.streaks.status-claimable",
            "bedrock.shop.line-short",
            "bedrock.rewards.every-vote",
            "bedrock.nav.settings",
            "bedrock.settings.unavailable",
            "vote_overview.nav.settings.name",
            "vote_settings.option.reminder.name",
            "vote_settings.option.discord.description",
            "vote_settings.value.enabled",
            "vote_settings.value.disabled",
            "vote.reminder.chat",
            "vote.reminder.streak-warning",
            "vote.reminder.discord.site-link");

    @Test
    void englishAndGermanHaveTheSameKeys() throws IOException {
        Set<String> english = leafKeys("en_US");
        Set<String> german = leafKeys("de_DE");

        Set<String> missingInGerman = new TreeSet<>(english);
        missingInGerman.removeAll(german);
        Set<String> missingInEnglish = new TreeSet<>(german);
        missingInEnglish.removeAll(english);

        assertEquals(Set.of(), missingInGerman, "keys missing in de_DE");
        assertEquals(Set.of(), missingInEnglish, "keys missing in en_US");
    }

    @Test
    void menuKeysExist() throws IOException {
        for (String locale : List.of("en_US", "de_DE")) {
            YamlConfiguration yaml = load(locale);
            for (String key : REQUIRED) {
                assertTrue(yaml.contains(key), locale + " lacks " + key);
            }
        }
    }

    @Test
    void textFollowsTheSymbolRules() throws IOException {
        for (String locale : List.of("en_US", "de_DE")) {
            YamlConfiguration yaml = load(locale);
            for (String key : leafKeys(locale)) {
                String value = String.valueOf(yaml.get(key));
                assertFalse(value.contains("[+]"), locale + " " + key + " uses [+]");
                assertFalse(value.contains("—"), locale + " " + key + " uses an em-dash");
            }
        }
    }

    @Test
    void menuLoreUsesNoChatStatusTokens() throws IOException {
        for (String locale : List.of("en_US", "de_DE")) {
            YamlConfiguration yaml = load(locale);
            for (String key : List.of("vote_streak.milestone.status-claimed", "vote_streak.milestone.status-next",
                    "vote_rewards.freeze.status-short", "vote_rewards.multiplier.status-on", "vote_shop.tile.short")) {
                String value = yaml.getString(key, "");
                assertFalse(value.contains("[OK]") || value.contains("[X]") || value.contains("[!]"),
                        locale + " " + key);
            }
        }
    }

    @Test
    void fallbackLocalesParse() throws IOException {
        for (String locale : List.of("cs_CZ", "sk_SK")) {
            assertTrue(load(locale).contains("prefix"), locale);
        }
    }

    private static Set<String> leafKeys(String locale) throws IOException {
        YamlConfiguration yaml = load(locale);
        return yaml.getKeys(true).stream()
                .filter(key -> !yaml.isConfigurationSection(key))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static YamlConfiguration load(String locale) throws IOException {
        try (InputStream in = TranslationFilesTest.class.getResourceAsStream("/translations/" + locale + ".yml")) {
            assertNotNull(in, locale);
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }
}
