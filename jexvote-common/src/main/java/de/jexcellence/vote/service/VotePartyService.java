package de.jexcellence.vote.service;

import de.jexcellence.jexplatform.reward.AbstractReward;
import de.jexcellence.jexplatform.scheduler.PlatformScheduler;
import de.jexcellence.vote.database.entity.PendingVoteRewardEntity;
import de.jexcellence.vote.database.entity.VotePartyContributorEntity;
import de.jexcellence.vote.database.entity.VotePartyEntity;
import de.jexcellence.vote.database.repository.PendingVoteRewardRepository;
import de.jexcellence.vote.database.repository.VotePartyContributorRepository;
import de.jexcellence.vote.database.repository.VotePartyRepository;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
 * @author JExcellence
 */
public class VotePartyService {

    private static final String PARTY_SERVICE_NAME = "VoteParty";

    private final Logger logger;
    private final PlatformScheduler scheduler;
    private final VotePartyRepository partyRepository;
    private final VotePartyContributorRepository contributorRepository;
    private final PendingVoteRewardRepository pendingRewardRepository;
    private final VoteRewardService rewardService;
    private final VoteBroadcastService broadcastService;
    private final List<AbstractReward> partyRewards;
    private final int target;

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
                            @NotNull List<AbstractReward> partyRewards,
                            int target) {
        this.logger = plugin.getLogger();
        this.scheduler = PlatformScheduler.of(plugin);
        this.partyRepository = partyRepository;
        this.contributorRepository = contributorRepository;
        this.pendingRewardRepository = pendingRewardRepository;
        this.rewardService = rewardService;
        this.broadcastService = broadcastService;
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

    private void rewardContributor(@NotNull UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null && online.isOnline()) {
            scheduler.runAtEntity(online, () -> rewardService.grantRewardList(online, partyRewards));
        } else {
            String data = rewardService.serializeRewardList(partyRewards);
            if (data != null) {
                pendingRewardRepository.create(
                        new PendingVoteRewardEntity(uuid, PARTY_SERVICE_NAME, data));
            }
        }
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
