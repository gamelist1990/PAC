package org.pexserver.pac.check.shared.aim.model.ml.logic.rnn.data.preprocessing;

import org.pexserver.pac.check.shared.aim.model.ml.logic.rnn.data.SequenceData;

public interface SequencePreprocessor {
    SequenceData prepare(double[][] rawVecs);
}