package de.jexcellence.vote;

import de.jexcellence.dependency.delegate.AbstractPluginDelegate;
import org.jetbrains.annotations.NotNull;

import java.util.logging.Logger;

public final class JExVotePremiumImpl extends AbstractPluginDelegate<JExVotePremium> {

    private static final Logger LOGGER = Logger.getLogger(JExVotePremiumImpl.class.getName());
    private static final int METRICS_ID = 0;

    private JExVote vote;

    public JExVotePremiumImpl(@NotNull JExVotePremium plugin) {
        super(plugin);
    }

    @Override
    public void onLoad() {
        this.vote = new JExVote(getPlugin(), "Premium") {
            @Override
            protected int metricsId() {
                return METRICS_ID;
            }

            @Override
            protected VoteEdition edition() {
                return new VoteEdition.PremiumEdition();
            }
        };
        this.vote.onLoad();
    }

    @Override
    public void onEnable() {
        if (this.vote == null) {
            getLogger().severe("JExVote failed to load — disabling");
            getPlugin().getServer().getPluginManager().disablePlugin(getPlugin());
            return;
        }
        this.vote.onEnable();
    }

    @Override
    public void onDisable() {
        if (this.vote != null) {
            this.vote.onDisable();
        }
    }
}
