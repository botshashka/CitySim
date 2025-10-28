package dev.citysim.ui;

import dev.citysim.ui.ScoreboardService.Mode;

/**
 * Simple data container for player display preferences.
 */
public class DisplayPreferences {
    private boolean titlesEnabled = true;
    private boolean bossBarEnabled = true;
    private boolean scoreboardEnabled = false;
    private String scoreboardMode = Mode.COMPACT.name();

    public boolean isTitlesEnabled() {
        return titlesEnabled;
    }

    public void setTitlesEnabled(boolean titlesEnabled) {
        this.titlesEnabled = titlesEnabled;
    }

    public boolean isBossBarEnabled() {
        return bossBarEnabled;
    }

    public void setBossBarEnabled(boolean bossBarEnabled) {
        this.bossBarEnabled = bossBarEnabled;
    }

    public boolean isScoreboardEnabled() {
        return scoreboardEnabled;
    }

    public void setScoreboardEnabled(boolean scoreboardEnabled) {
        this.scoreboardEnabled = scoreboardEnabled;
    }

    public String getScoreboardMode() {
        return scoreboardMode;
    }

    public void setScoreboardMode(String scoreboardMode) {
        this.scoreboardMode = scoreboardMode;
    }
}
