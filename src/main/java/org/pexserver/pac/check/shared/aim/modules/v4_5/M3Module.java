package org.pexserver.pac.check.shared.aim.modules.v4_5;

import org.pexserver.pac.check.shared.aim.model.math.Simplification;
import org.pexserver.pac.check.shared.aim.model.ml.data.ResultML;
import org.pexserver.pac.check.shared.aim.model.ml.data.module.FlagType;
import org.pexserver.pac.check.shared.aim.model.ml.data.module.ModuleML;
import org.pexserver.pac.check.shared.aim.model.ml.data.module.ModuleResultML;
import org.pexserver.pac.check.shared.aim.model.ml.logic.ModelVer;

public class M3Module implements ModuleML {

    private static final double M = 2.0;

    @Override
    public String getName() {
        return "m3";
    }

    @Override
    public ModuleResultML getResult(ResultML resultML) {
        ResultML.CheckResultML stats = resultML.statisticsResult;
        final double UNUSUAL = stats.UNUSUAL / M;
        final double STRANGE = stats.STRANGE / M;
        final double SUSPECTED = stats.SUSPECTED / M;
        final double SUSPICIOUSLY = stats.SUSPICIOUSLY / M;
        String scaledUnusual = String.valueOf(Simplification.scaleVal(UNUSUAL, 3));

        if (UNUSUAL > 0.3 && STRANGE > 0.20 && SUSPECTED > 0.14 && SUSPICIOUSLY > 0.07)
            return new ModuleResultML(20, FlagType.SUSPECTED, scaledUnusual);

        if ((UNUSUAL > 0.34 && STRANGE > 0.12 && SUSPECTED > 0.1 && SUSPICIOUSLY > 0) ||
                (UNUSUAL > 0.25 && STRANGE > 0.12 && SUSPECTED > 0.12) ||
                (UNUSUAL > 0.10 && STRANGE > 0.14 && SUSPECTED > 0.08 && SUSPICIOUSLY > 0))
            return new ModuleResultML(20, FlagType.STRANGE, scaledUnusual);

        return new ModuleResultML(0, FlagType.NORMAL, scaledUnusual);
    }

    @Override
    public int getParameterBuffer() {
        return 15;
    }

    @Override
    public ModelVer getVersion() {
        return ModelVer.VERSION_4_5;
    }
}
