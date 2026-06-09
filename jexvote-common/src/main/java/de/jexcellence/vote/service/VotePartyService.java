package de.jexcellence.vote.service;

import de.jexcellence.jexplatform.reward.AbstractReward;
import de.jexcellence.jexplatform.scheduler.PlatformScheduler;
import de.jexcellence.vote.config.VotePartyConfig;
import de.jexcellence.vote.database.entity.PendingVoteRewardEntity;
import de.jexcellence.vote.database.entity.VotePartyContributorEntity;
import de.jexcellence.vote.database.entity.VotePartyEntity;
import de.jexcellence.vote.database.repository.PendingVoteRewardRepository;
import de.jexcellence.vote.database.repository.VotePartyContributorRepository;
import de.jexcellence.vote.database.repository.VotePartyRepository;
import de.jexcellence.jextranslate.R18nManager;
import de.jexcellence.vote.reward.LuckyReward;
import de.jexcellence.vote.reward.RewardStats;
import de.jexcellence.vote.view.VoteRewardDescriber;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages the server-wide Vote Party: a shared counter that, once it reaches the
 * configured target, rewards every contributor to that party (online players
 * immediately, offline players via the pending-reward queue) and resets.
 *
 * <p>Party state mutations are serialized via {@code synchronized} so concurrent
 * votes from the async processing pool cannot lose counts or double-trigger
 * completion. This is a Premium feature; the service is only constructed when the
 * edition permits it and {@code vote-party.enabled} is set.
 *
 * <p>Animations, sounds, and title messages are fully configurable via
 * {@link VotePartyConfig}.
 *
 * @author JExcellence
 */
public class VotePartyService {

    private static final String PARTY_SERVICE_NAME = "VoteParty";
    private static final int ROLL_GUARD = 200;

    private final Logger logger;
    private final PlatformScheduler scheduler;
    private final VotePartyRepository partyRepository;
    private final VotePartyContributorRepository contributorRepository;
    private final PendingVoteRewardRepository pendingRewardRepository;
    private final VoteRewardService rewardService;
    private final VoteBroadcastService broadcastService;
    private final VotePartyConfig partyConfig;
    private final List<AbstractReward> partyRewards;
    private final int target;

    /** Weighted rotation pool for the guaranteed + decaying-extra draws. */
    private @Nullable LuckyReward partyPool;

    // Live view of party progress for placeholders.
    private final AtomicInteger currentVotes = new AtomicInteger(0);
    private final AtomicInteger targetVotes = new AtomicInteger(0);

    @SuppressWarnings("java:S107")
    public VotePartyService(@NotNull JavaPlugin plugin,
                            @NotNull VotePartyRepository partyRepository,
                            @NotNull VotePartyContributorRepository contributorRepository,
                            @NotNull PendingVoteRewardRepository pendingRewardRepository,
                            @NotNull VoteRewardService rewardService,
                            @NotNull VoteBroadcastService broadcastService,
                            @NotNull VotePartyConfig partyConfig,
                            @NotNull List<AbstractReward> partyRewards,
                            int target) {
        this.logger = plugin.getLogger();
        this.scheduler = PlatformScheduler.of(plugin);
        this.partyRepository = partyRepository;
        this.contributorRepository = contributorRepository;
        this.pendingRewardRepository = pendingRewardRepository;
        this.rewardService = rewardService;
        this.broadcastService = broadcastService;
        this.partyConfig = partyConfig;
        this.partyRewards = partyRewards;
        this.target = target;

        VotePartyEntity party = getOrCreateActiveParty();
        this.currentVotes.set(party.getCurrentVotes());
        this.targetVotes.set(party.getTargetVotes());
    }

    /**
     * Records a single vote toward the active party. Thread-safe. Called from the
     * async vote-processing thread.
     */
    public synchronized void recordVote(@NotNull UUID uuid) {
        VotePartyEntity party = getOrCreateActiveParty();
        upsertContributor(party.getId(), uuid);

        party.setCurrentVotes(party.getCurrentVotes() + 1);
        if (party.getCurrentVotes() >= party.getTargetVotes()) {
            completeParty(party);
        } else {
            partyRepository.update(party);
            currentVotes.set(party.getCurrentVotes());
        }
    }

    private void upsertContributor(long partyId, @NotNull UUID uuid) {
        Optional<VotePartyContributorEntity> existing =
                contributorRepository.findByPartyAndPlayer(partyId, uuid);
        if (existing.isPresent()) {
            VotePartyContributorEntity contributor = existing.orElseThrow();
            contributor.setContributedVotes(contributor.getContributedVotes() + 1);
            contributorRepository.update(contributor);
        } else {
            contributorRepository.create(new VotePartyContributorEntity(partyId, uuid));
        }
    }

    private void completeParty(@NotNull VotePartyEntity party) {
        long partyId = party.getId();
        int completedNumber = party.getPartyNumber();
        List<VotePartyContributorEntity> contributors = contributorRepository.findByParty(partyId);

        for (VotePartyContributorEntity contributor : contributors) {
            rewardContributor(contributor.getPlayerUuid());
        }

        contributorRepository.deleteByParty(partyId);

        // Reset for the next party.
        party.setPartyNumber(completedNumber + 1);
        party.setCurrentVotes(0);
        party.setTargetVotes(target);
        party.setStartedAt(Instant.now());
        partyRepository.update(party);
        currentVotes.set(0);
        targetVotes.set(target);

        int rewarded = contributors.size();
        scheduler.runSync(() -> broadcastService.broadcastPartyReached(completedNumber));
        logger.log(Level.INFO, () -> String.format(
                "Vote Party #%d completed — rewarded %d contributor(s)", completedNumber, rewarded));
    }

    /** Injects the weighted party rotation pool (null = no rotation, baseline only). */
    public void setPartyPool(@Nullable LuckyReward partyPool) {
        this.partyPool = partyPool;
    }

    private void rewardContributor(@NotNull UUID uuid) {
        List<LuckyReward.Entry> picks = rollPartyEntries();
        List<AbstractReward> rewards = new ArrayList<>(partyRewards);
        for (LuckyReward.Entry entry : picks) {
            rewards.add(entry.reward());
            if (entry.id() != null) {
                RewardStats.logGrant(entry.id());
            }
        }

        Player online = Bukkit.getPlayer(uuid);
        if (online != null && online.isOnline()) {
            if (picks.isEmpty()) {
                scheduler.runAtEntity(online, () -> rewardService.grantRewardList(online, rewards));
            } else {
                animateThenGrant(uuid, picks, rewards, 0);
            }
        } else {
            queuePending(uuid, rewards);
        }
    }

    /** Serializes a reward list into the offline pending-reward queue. */
    private void queuePending(@NotNull UUID uuid, @NotNull List<AbstractReward> rewards) {
        String data = rewardService.serializeRewardList(rewards);
        if (data != null) {
            pendingRewardRepository.create(
                    new PendingVoteRewardEntity(uuid, PARTY_SERVICE_NAME, data));
        }
    }

    /**
     * Draws the per-contributor pool picks: {@link VotePartyConfig.AnimationSettings#guaranteedPicks()}
     * distinct draws plus decaying-chance extras (start {@link VotePartyConfig.AnimationSettings#extraStartChance()},
     * −{@link VotePartyConfig.AnimationSettings#extraStep()}/step) up to {@link VotePartyConfig.AnimationSettings#maxTotalPicks()}.
     * Empty when no pool is configured.
     */
    private @NotNull List<LuckyReward.Entry> rollPartyEntries() {
        LuckyReward pool = partyPool;
        if (pool == null || pool.getEntries().isEmpty()) {
            return List.of();
        }
        var settings = partyConfig.getAnimationSettings();
        List<LuckyReward.Entry> picks = new ArrayList<>();
        Set<String> usedIds = new HashSet<>();
        int distinctTarget = Math.min(settings.guaranteedPicks(), pool.getEntries().size());
        int guard = 0;
        while (picks.size() < distinctTarget && guard < ROLL_GUARD) {
            guard++;
            LuckyReward.Entry entry = pool.pick();
            String key = entry.id() != null ? entry.id() : "@" + System.identityHashCode(entry);
            if (usedIds.add(key)) {
                picks.add(entry);
            }
        }
        double chance = settings.extraStartChance();
        for (int i = settings.guaranteedPicks(); i < settings.maxTotalPicks() && chance > 0.0; i++) {
            if (ThreadLocalRandom.current().nextDouble() < chance) {
                picks.add(pool.pick());
            }
            chance -= settings.extraStep();
        }
        return picks;
    }

    /**
     * Plays the slot-machine reveal for an online contributor, then grants the
     * rewards on the final frame. Self-reschedules via the platform scheduler
     * (Folia-safe: player ops run in the entity's region). If the player drops
     * mid-animation, the rewards fall back to the offline queue.
     */
    private void animateThenGrant(@NotNull UUID uuid, @NotNull List<LuckyReward.Entry> picks,
                                  @NotNull List<AbstractReward> rewards, int frame) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline()) {
            queuePending(uuid, rewards);
            return;
        }
        var settings = partyConfig.getAnimationSettings();
        if (frame >= settings.spinFrames()) {
            scheduler.runAtEntity(player, () -> {
                showReveal(player, picks);
                rewardService.grantRewardList(player, rewards);
            });
            return;
        }
        scheduler.runAtEntity(player, () -> showSpinFrame(player, picks, frame));
        scheduler.runDelayed(() -> animateThenGrant(uuid, picks, rewards, frame + 1), settings.frameTicks());
    }

    private void showSpinFrame(@NotNull Player player, @NotNull List<LuckyReward.Entry> picks, int frame) {
        var soundSettings = partyConfig.getSoundSettings();
        var titleSettings = partyConfig.getTitleSettings();

        LuckyReward.Entry spin = picks.get(ThreadLocalRandom.current().nextInt(picks.size()));
        String name = VoteRewardDescriber.describe(spin.reward());

        player.showTitle(Title.title(
                MiniMessage.miniMessage().deserialize(titleSettings.spinTitle()),
                MiniMessage.miniMessage().deserialize(name),
                Title.Times.times(titleSettings.fadeIn(), titleSettings.stay(), titleSettings.fadeOut())));

        Sound spinSound = Sound.valueOf(soundSettings.spinSound());
        float pitch = soundSettings.spinPitchBase() + (frame % 5) * soundSettings.spinPitchStep();
        player.playSound(player, spinSound, soundSettings.spinVolume(), pitch);
    }

    private void showReveal(@NotNull Player player, @NotNull List<LuckyReward.Entry> picks) {
        var soundSettings = partyConfig.getSoundSettings();
        var titleSettings = partyConfig.getTitleSettings();

        String subtitle = titleSettings.revealSubtitle()
                .replace("{count}", String.valueOf(picks.size()));
        player.showTitle(Title.title(
                MiniMessage.miniMessage().deserialize(titleSettings.revealTitle()),
                MiniMessage.miniMessage().deserialize(subtitle),
                Title.Times.times(Duration.ofMillis(100), titleSettings.stay(), Duration.ofMillis(400))));

        Sound revealSound = Sound.valueOf(soundSettings.revealSound());
        player.playSound(player, revealSound, soundSettings.revealVolume(), soundSettings.revealPitch());
    }

    private @NotNull VotePartyEntity getOrCreateActiveParty() {
        return partyRepository.findActive().orElseGet(() -> {
            VotePartyEntity party = new VotePartyEntity(1, target);
            partyRepository.create(party);
            return party;
        });
    }

    public int getCurrentVotes() {
        return currentVotes.get();
    }

    public int getTargetVotes() {
        return targetVotes.get();
    }

    public int getRemainingVotes() {
        return Math.max(0, targetVotes.get() - currentVotes.get());
    }
}
