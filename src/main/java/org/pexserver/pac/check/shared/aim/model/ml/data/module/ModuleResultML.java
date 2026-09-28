package org.pexserver.pac.check.shared.aim.model.ml.data.module;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;

@AllArgsConstructor
@Getter
@EqualsAndHashCode
public class ModuleResultML {
    private int priority;
    private FlagType type;
    private String info;
}
