package org.pexserver.pac.check.java.movement;

import org.pexserver.pac.check.core.*;
import org.pexserver.pac.movement.FalseGroundClaimWindow;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class NoFallCheck extends AbstractCheck implements PacketCheck {
    private static final class State {
        final long epoch;
        final FalseGroundClaimWindow window=new FalseGroundClaimWindow();
        State(long epoch) { this.epoch=epoch; }
    }
    private final ConcurrentHashMap<UUID,State> states=new ConcurrentHashMap<>();
    @Override public String key() { return "no-fall"; }
    @Override public boolean supportsBedrock() { return false; }
    @Override public boolean automaticBanEligible() { return false; }
    @Override public boolean automaticKickEligible() { return false; }
    @Override public void inspect(PacketContext context) {
        if(context.event().isCancelled())return;
        var state=states.compute(context.uuid(),(id,old)->old==null||old.epoch!=context.movementEpoch()?new State(context.movementEpoch()):old);
        synchronized(state) {
            var p=context.location();
            boolean uncertain=context.timingUncertain()||context.plugin().environment().movementSuppressed(context.uuid())
                    ||context.plugin().environment().authorizedFlightMovement(context.uuid())
                    ||context.plugin().waterMotion().wetNear(context.uuid(),p.getX(),p.getY(),p.getZ(),System.currentTimeMillis())
                    ||context.plugin().environment().collisionChangeNear(context.uuid(),p.getX(),p.getY(),p.getZ(),p.getX(),p.getY(),p.getZ(),System.currentTimeMillis());
            var finding=state.window.accept(context.flying().hasPositionChanged(),p.getX(),p.getY(),p.getZ(),
                    context.flying().isOnGround(),context.plugin().environment().get(context.uuid()),
                    context.plugin().environment().collisions(context.uuid()),System.currentTimeMillis(),uncertain);
            if(finding.confirmed())flagLimited(context,"repeated true on-ground claim without server-confirmed support");
            if(finding.repair()&&context.plugin().cancel(this,context.uuid())) {
                context.flying().setOnGround(false);context.flying().write();
            }
        }
    }
    @Override public void forget(UUID uuid) { super.forget(uuid);states.remove(uuid); }
}
