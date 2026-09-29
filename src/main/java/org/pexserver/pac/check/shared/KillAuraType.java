package org.pexserver.pac.check.shared;

/** Stable sub-types for the shared KillAura detector. */
enum KillAuraType {
    A("attack view"),
    B("occlusion"),
    C("snap-back rotation"),
    D("target switching"),
    E("aim automation");

    private final String description;

    KillAuraType(String description) {
        this.description = description;
    }

    String label() {
        return "Type " + name();
    }

    String format(String source, String detail) {
        return label() + " / " + description + " / " + source + ": " + detail;
    }
}
