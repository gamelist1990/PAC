package org.pexserver.pac.api;

/** Named global PAC settings exposed by the typed settings API. */
public enum PacSetting {
    ALERTS_ENABLED("alerts.enabled"),
    DEBUG_RECORDING_MODE("debug.recording-mode"),
    ROLLBACK_ENABLED("punishments.rollback-enabled"),
    PROBATION_ENABLED("punishments.probation-enabled"),
    SCORE_THRESHOLD("punishments.score-threshold"),
    DAILY_RISK_ALLOWANCE("punishments.daily-risk-allowance"),
    TRUST_LOSS_PER_ACTION("punishments.trust-loss-per-action"),
    SIX_DAY_TRUST_THRESHOLD("punishments.six-day-trust-threshold"),
    PERMANENT_TRUST_THRESHOLD("punishments.permanent-trust-threshold"),
    TRUST_RECOVERY_PER_CLEAN_DAY("punishments.trust-recovery-per-clean-day"),
    SCORING_HALF_LIFE_SECONDS("scoring.half-life-seconds"),
    SCORING_REPEAT_WINDOW_SECONDS("scoring.repeat-window-seconds"),
    SCORING_REPEAT_MULTIPLIER("scoring.repeat-multiplier"),
    SCORING_MAXIMUM_MULTIPLIER("scoring.maximum-multiplier"),
    SCORING_MAXIMUM_SCORE("scoring.maximum-score");

    private final String path;

    PacSetting(String path) { this.path = path; }

    /** The config path represented by this setting. */
    public String path() { return path; }
}
