package de.jexcellence.vote.bedrock;

import de.jexcellence.jextranslate.MessageBuilder;
import de.jexcellence.jextranslate.R18nManager;
import de.jexcellence.vote.settings.VoteSettingOption;
import de.jexcellence.vote.settings.VoteSettings;
import de.jexcellence.vote.settings.VoteSettingsService;
import de.jexcellence.vote.settings.VoteSettingsService.Availability;
import org.bukkit.entity.Player;
import org.geysermc.cumulus.form.CustomForm;
import org.geysermc.cumulus.response.CustomFormResponse;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Bedrock version of {@code /vote settings}: a custom form with a toggle for each on/off option, a dropdown
 * for the reminder mode and a text line for options the server has turned off. Submitting saves every changed
 * value at once; closing the form changes nothing.
 *
 * @author JExcellence
 * @since 3.4.0
 */
public final class VoteSettingsForm {

    private static final String KEY = "bedrock.settings.";

    private final BedrockFormBridge bridge;
    private final VoteSettingsService settings;

    public VoteSettingsForm(@NotNull BedrockFormBridge bridge, @NotNull VoteSettingsService settings) {
        this.bridge = bridge;
        this.settings = settings;
    }

    /**
     * Sends the settings form to a Bedrock player.
     *
     * @param player the Bedrock player
     */
    public void open(@NotNull Player player) {
        settings.load(player.getUniqueId()).thenAccept(current -> bridge.sendForm(player, build(player, current)));
    }

    private @NotNull CustomForm build(@NotNull Player player, @NotNull VoteSettings current) {
        UUID uuid = player.getUniqueId();
        CustomForm.Builder form = CustomForm.builder()
                .title(text(player, KEY + "title"))
                .label(text(player, KEY + "intro"));
        int component = 1;
        Map<Integer, VoteSettingOption> inputs = new LinkedHashMap<>();
        for (VoteSettingOption option : VoteSettingOption.values()) {
            Availability availability = settings.availability(uuid, option);
            String label = text(player, "vote_settings.option." + option.id() + ".label");
            if (availability == Availability.UNAVAILABLE) {
                form.label(msg(KEY + "unavailable").with("option", label).toPlainString(player));
                component++;
            } else if (availability == Availability.AVAILABLE) {
                addInput(form, player, option, label, current);
                inputs.put(component, option);
                component++;
            }
        }
        form.validResultHandler(response -> apply(player, current, inputs, response));
        return form.build();
    }

    private static void addInput(@NotNull CustomForm.Builder form, @NotNull Player player,
                                 @NotNull VoteSettingOption option, @NotNull String label,
                                 @NotNull VoteSettings current) {
        if (option.isSwitch()) {
            form.toggle(label, option.isActive(current));
            return;
        }
        List<String> values = new ArrayList<>(option.choices().size());
        for (String value : option.choices()) {
            values.add(text(player, "vote_settings.value." + value));
        }
        form.dropdown(label, values, option.index(current));
    }

    private void apply(@NotNull Player player, @NotNull VoteSettings before,
                       @NotNull Map<Integer, VoteSettingOption> inputs, @NotNull CustomFormResponse response) {
        VoteSettings next = before;
        for (Map.Entry<Integer, VoteSettingOption> input : inputs.entrySet()) {
            VoteSettingOption option = input.getValue();
            int index = option.isSwitch()
                    ? toggleIndex(response.asToggle(input.getKey()))
                    : response.asDropdown(input.getKey());
            next = option.select(next, index);
        }
        if (!next.equals(before)) {
            settings.update(player.getUniqueId(), next);
        }
        msg(KEY + "saved").prefix().send(player);
    }

    private static int toggleIndex(boolean enabled) {
        return enabled ? 0 : 1;
    }

    private static @NotNull String text(@NotNull Player player, @NotNull String key) {
        return msg(key).toPlainString(player);
    }

    private static @NotNull MessageBuilder msg(@NotNull String key) {
        return R18nManager.getInstance().msg(key);
    }
}
