package org.pexserver.pac.check.shared.aim.model.ml.data.module;

import org.pexserver.pac.check.shared.aim.model.ml.data.ResultML;
import org.pexserver.pac.check.shared.aim.model.ml.logic.ModelVer;

public interface ModuleML {
    String getName();
    ModuleResultML getResult(ResultML resultML);
    int getParameterBuffer();
    ModelVer getVersion();
}
