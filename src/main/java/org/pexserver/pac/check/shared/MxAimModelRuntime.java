package org.pexserver.pac.check.shared;

import org.pexserver.pac.check.shared.aim.modules.v4_5.M1Module;
import org.pexserver.pac.check.shared.aim.modules.v4_5.M2Module;
import org.pexserver.pac.check.shared.aim.modules.v4_5.M3Module;
import org.pexserver.pac.check.shared.aim.modules.v4_5.M4Module;
import org.pexserver.pac.check.shared.aim.modules.v4_5.M5Module;
import org.pexserver.pac.check.shared.aim.modules.v4_5.MHuge1Module;
import org.pexserver.pac.check.shared.aim.modules.v4_5.MHuge2Module;
import org.pexserver.pac.check.shared.aim.modules.v5.RNN1Module;
import org.pexserver.pac.check.shared.aim.model.ml.data.ObjectML;
import org.pexserver.pac.check.shared.aim.model.ml.data.ResultML;
import org.pexserver.pac.check.shared.aim.model.ml.data.DataML;
import org.pexserver.pac.check.shared.aim.model.ml.data.module.FlagType;
import org.pexserver.pac.check.shared.aim.model.ml.data.module.ModuleML;
import org.pexserver.pac.check.shared.aim.model.ml.data.module.ModuleResultML;
import org.pexserver.pac.check.shared.aim.model.ml.logic.Millennium;
import org.pexserver.pac.check.shared.aim.model.ml.logic.ModelML;
import org.pexserver.pac.check.shared.aim.model.ml.logic.RNNModelML;
import org.pexserver.pac.check.shared.aim.model.ml.data.statistic.StatisticML;
import org.pexserver.pac.check.shared.aim.model.ml.data.statistic.StatisticPattern;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Loads and runs the pretrained MX legacy and RNN aim classifiers off the server thread. */
final class MxAimModelRuntime implements AutoCloseable {
    record Prediction(String architecture, FlagType severity, int priority,
                      Set<String> models, String detail) {
        boolean flagged() { return severity != FlagType.NORMAL; }
    }

    private record LoadedModels(List<Millennium> legacy, List<ModuleML> legacyModules,
                               Millennium rnn, ModuleML rnnModule, long parameterCount) { }

    private static final List<String> LEGACY_MODEL_NAMES = List.of(
            "m1.pacmodel", "m2.pacmodel", "m3.pacmodel", "m4.pacmodel", "m5.pacmodel",
            "m_huge1.pacmodel", "m_huge2.pacmodel");

    private final ThreadPoolExecutor inferenceQueue;
    private final CompletableFuture<LoadedModels> loaded = new CompletableFuture<>();
    private final AtomicBoolean failureLogged = new AtomicBoolean();
    private final AtomicBoolean saturationLogged = new AtomicBoolean();
    private final AtomicLong droppedWindows = new AtomicLong();
    private final Consumer<String> infoLog;
    private final Consumer<String> errorLog;
    private final Consumer<String> warningLog;

    MxAimModelRuntime(Consumer<String> infoLog, Consumer<String> errorLog, Consumer<String> warningLog) {
        this.infoLog = infoLog;
        this.errorLog = errorLog;
        this.warningLog = warningLog;
        inferenceQueue = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(128), runnable -> {
                    Thread thread = new Thread(runnable, "PAC-MX-Aim-Inference");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        inferenceQueue.execute(this::loadModels);
    }

    CompletableFuture<Integer> readyModelCount() {
        return loaded.thenApply(models -> models.legacy().size() + 1);
    }

    long droppedInferenceWindows() { return droppedWindows.get(); }

    CompletableFuture<Prediction> predictLegacy(List<Float> yaw, List<Float> pitch) {
        List<Float> yawWindow = List.copyOf(yaw);
        List<Float> pitchWindow = List.copyOf(pitch);
        if (yawWindow.size() != 600 || pitchWindow.size() != 600)
            return CompletableFuture.failedFuture(new IllegalArgumentException("MX legacy model expects 600 rotations"));
        return submit(models -> analyze(yawWindow, pitchWindow, models.legacy(), models.legacyModules(), "MX legacy"));
    }

    CompletableFuture<Prediction> predictRnn(List<Float> yaw, List<Float> pitch) {
        List<Float> yawWindow = List.copyOf(yaw);
        List<Float> pitchWindow = List.copyOf(pitch);
        if (yawWindow.size() != 150 || pitchWindow.size() != 150)
            return CompletableFuture.failedFuture(new IllegalArgumentException("MX RNN expects 150 rotations"));
        return submit(models -> analyze(yawWindow, pitchWindow,
                List.of(models.rnn()), List.of(models.rnnModule()), "MX RNN"));
    }

    private CompletableFuture<Prediction> submit(java.util.function.Function<LoadedModels, Prediction> work) {
        CompletableFuture<Prediction> result = new CompletableFuture<>();
        try {
            inferenceQueue.execute(() -> {
                try {
                    result.complete(work.apply(loaded.join()));
                } catch (Throwable error) {
                    result.completeExceptionally(error);
                    logFailure(error);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException saturated) {
            long dropped = droppedWindows.incrementAndGet();
            result.completeExceptionally(saturated);
            if (saturationLogged.compareAndSet(false, true))
                warningLog.accept("MX aim inference queue reached capacity; sample windows are being skipped (dropped="
                        + dropped + ")");
        }
        return result;
    }

    private void loadModels() {
        try {
            List<Millennium> legacy = new ArrayList<>(LEGACY_MODEL_NAMES.size());
            List<ModuleML> modules = List.of(new M1Module(), new M2Module(), new M3Module(), new M4Module(),
                    new M5Module(), new MHuge1Module(), new MHuge2Module());
            long parameters = 0;
            for (int i = 0; i < LEGACY_MODEL_NAMES.size(); i++) {
                Millennium model = loadNativeModel(LEGACY_MODEL_NAMES.get(i));
                legacy.add(model);
                parameters += model.parameters();
            }

            RNNModelML rnn = new RNNModelML(16, 48);
            try (InputStream input = resource("m1-rnn.dat")) {
                rnn.load(input);
            }
            parameters += rnn.parameters();
            LoadedModels models = new LoadedModels(List.copyOf(legacy), modules,
                    rnn, new RNN1Module(), parameters);
            loaded.complete(models);
            infoLog.accept("PAC Killaura aim models loaded: 7 legacy + RNN; parameters=" + parameters);
        } catch (Throwable error) {
            loaded.completeExceptionally(error);
            logFailure(error);
        }
    }

    private static Millennium loadNativeModel(String name) throws IOException {
        try (DataInputStream data = new DataInputStream(resource(name))) {
            if (data.readInt() != 0x5041434d || data.readInt() != 1)
                throw new IOException("Unsupported PAC aim model format: " + name);
            int tableCount = boundedCount(data.readInt(), 16, "table count");
            ModelML model = new ModelML(0, 10);
            for (int tableIndex = 0; tableIndex < tableCount; tableIndex++) {
                int statisticCount = boundedCount(data.readInt(), 16, "statistic count");
                DataML table = new DataML(10);
                List<StatisticML> statistics = new ArrayList<>(statisticCount);
                for (int statisticIndex = 0; statisticIndex < statisticCount; statisticIndex++) {
                    int stackSize = boundedCount(data.readInt(), 4096, "stack size");
                    double scale = data.readDouble();
                    if (!Double.isFinite(scale) || scale <= 0)
                        throw new IOException("Invalid scale in PAC aim model: " + name);
                    int patternCount = boundedCount(data.readInt(), 100_000, "pattern count");
                    StatisticML statistic = new StatisticML(scale, stackSize);
                    for (int patternIndex = 0; patternIndex < patternCount; patternIndex++) {
                        StatisticPattern pattern = new StatisticPattern(data.readInt(), data.readInt(), data.readInt(),
                                data.readDouble(), data.readDouble(), data.readInt(), data.readDouble(),
                                data.readDouble(), data.readInt());
                        pattern.setLegit(data.readInt());
                        pattern.setDetected(data.readInt());
                        statistic.getParameters().add(pattern);
                    }
                    statistics.add(statistic);
                }
                table.setStatisticTable(statistics);
                model.getTable().add(table);
            }
            if (data.read() != -1) throw new IOException("Trailing data in PAC aim model: " + name);
            return model;
        }
    }

    private static int boundedCount(int value, int maximum, String field) throws IOException {
        if (value < 0 || value > maximum) throw new IOException("Invalid " + field + " in PAC aim model: " + value);
        return value;
    }

    private static InputStream resource(String name) throws IOException {
        InputStream input = MxAimModelRuntime.class.getResourceAsStream("/ml/" + name);
        if (input == null) throw new IOException("Missing pretrained MX resource: /ml/" + name);
        return input;
    }

    private static Prediction analyze(List<Float> yaw, List<Float> pitch, List<Millennium> models,
                                      List<ModuleML> modules, String architecture) {
        ObjectML yawData = new ObjectML(yaw.stream().map(Float::doubleValue).toList());
        ObjectML pitchData = new ObjectML(pitch.stream().map(Float::doubleValue).toList());
        List<ObjectML> input = List.of(yawData, pitchData);
        ModuleResultML top = null;
        java.util.LinkedHashSet<String> flagged = new java.util.LinkedHashSet<>();
        for (int i = 0; i < models.size(); i++) {
            ResultML result = models.get(i).checkData(input);
            ModuleML module = modules.get(i);
            ModuleResultML candidate = module.getResult(result);
            if (candidate.getType() != FlagType.NORMAL) flagged.add(module.getName());
            if (top == null || candidate.getType().getLevel() > top.getType().getLevel()
                    || (candidate.getType().getLevel() == top.getType().getLevel()
                    && candidate.getPriority() > top.getPriority())) top = candidate;
        }
        if (top == null) return new Prediction(architecture, FlagType.NORMAL, 0, Set.of(), "no model output");
        return new Prediction(architecture, top.getType(), top.getPriority(), Set.copyOf(flagged),
                top.getInfo() == null ? "" : top.getInfo());
    }

    private void logFailure(Throwable error) {
        if (failureLogged.compareAndSet(false, true)) {
            Throwable cause = error instanceof CompletionException && error.getCause() != null
                    ? error.getCause() : error;
            errorLog.accept("PAC Killaura aim inference unavailable; pretrained models failed to load or execute: "
                    + cause.getClass().getSimpleName() + ": " + cause.getMessage());
        }
    }

    @Override public void close() {
        inferenceQueue.shutdownNow();
    }
}
