package ac.boar.anticheat.check.impl.timer;

import ac.boar.anticheat.check.api.BaseCheck;
import ac.boar.anticheat.player.BoarPlayer;
import ac.boar.api.anticheat.annotations.CheckInfo;
import ac.boar.api.anticheat.annotations.Experimental;

@Experimental
@CheckInfo(name = "Timer")
public final class Timer extends BaseCheck {
    private final InputTimingWindow timing = new InputTimingWindow();

    public Timer(BoarPlayer player) { super(player); }

    public boolean isInvalid() {
        boolean exempt = player.inLoadingScreen || player.sinceLoadingScreen < 100
                || player.getTeleportUtil().isTeleporting() || player.insideUnloadedChunk;
        return timing.observe(player.tick, System.nanoTime(), exempt);
    }
}
