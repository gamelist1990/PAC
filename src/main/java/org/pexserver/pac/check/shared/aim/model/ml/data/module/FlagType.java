package org.pexserver.pac.check.shared.aim.model.ml.data.module;

public enum FlagType {
    NORMAL,
    UNUSUAL,
    STRANGE,
    SUSPECTED;

    public int getLevel() {
        return this.ordinal();
    }
}
