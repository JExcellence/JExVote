package de.jexcellence.vote;

import de.jexcellence.dependency.JEDependency;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

public final class JExVotePremium extends JavaPlugin {

    private JExVotePremiumImpl implementation;

    @Override
    public void onLoad() {
        try {
            JEDependency.initializeWithRemapping(this, JExVotePremium.class);
            this.implementation = new JExVotePremiumImpl(this);
            this.implementation.onLoad();
        } catch (Exception exception) {
            this.getLogger().log(Level.SEVERE, "[JExVote-Premium] Failed to load", exception);
            this.implementation = null;
        }
    }

    @Override
    public void onEnable() {
        if (this.implementation != null) {
            this.implementation.onEnable();
        }
    }

    @Override
    public void onDisable() {
        if (this.implementation != null) {
            this.implementation.onDisable();
        }
    }

    public JExVotePremiumImpl getImpl() {
        return this.implementation;
    }
}
