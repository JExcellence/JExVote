package de.jexcellence.vote;

import de.jexcellence.dependency.JEDependency;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

public final class JExVoteFree extends JavaPlugin {

    private JExVoteFreeImpl implementation;

    @Override
    public void onLoad() {
        try {
            JEDependency.initializeWithRemapping(this, JExVoteFree.class);
            this.implementation = new JExVoteFreeImpl(this);
            this.implementation.onLoad();
        } catch (Exception exception) {
            this.getLogger().log(Level.SEVERE, "[JExVote-Free] Failed to load", exception);
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

    public JExVoteFreeImpl getImpl() {
        return this.implementation;
    }
}
