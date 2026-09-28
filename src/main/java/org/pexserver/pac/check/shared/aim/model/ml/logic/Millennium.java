package org.pexserver.pac.check.shared.aim.model.ml.logic;

import org.pexserver.pac.check.shared.aim.model.ml.data.ObjectML;
import org.pexserver.pac.check.shared.aim.model.ml.data.ResultML;
import org.pexserver.pac.check.shared.aim.model.vectors.Pair;

import java.util.List;

public interface Millennium {
    ResultML checkData(List<ObjectML> o);
    void learnByData(List<ObjectML> o, boolean isMustBeBlocked);
    void trainEpochs(List<Pair<List<ObjectML>, Boolean>> dataset, int epochs);
    void saveToFile(String fileName);
    int parameters();
}