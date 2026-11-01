package org.pexserver.pac.check.java.movement;

import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPing;
import org.pexserver.pac.check.core.*;
import org.pexserver.pac.movement.*;
import org.pexserver.pac.packet.ExternalMotionTracker;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Validates responses to actual outbound velocities, not Bukkit event timing. */
public final class VelocityResponseCheck extends AbstractCheck implements PacketCheck {
    private static final class State {
        final KnockbackResponseWindow window=new KnockbackResponseWindow();
        final LinkedHashMap<Integer,Long> replies=new LinkedHashMap<>();
        long sequence,epoch=-1,lastAttackAt;
        int currentReply;
        boolean attackPending;
    }
    private final Map<UUID,State> states=new ConcurrentHashMap<>();
    private final SecureRandom random=new SecureRandom();
    @Override public String key() { return "velocity-response"; }
    @Override public boolean supportsBedrock() { return false; }
    @Override public boolean automaticBanEligible() { return false; }
    @Override public boolean automaticKickEligible() { return false; }
    public void sent(UUID uuid,ExternalMotionTracker.Impulse impulse,long epoch) {
        if(uuid==null||impulse==null)return;
        var state=states.computeIfAbsent(uuid,id->new State());
        synchronized(state) {
            if(state.sequence==impulse.sequence())return;
            if(state.epoch>=0&&state.epoch!=epoch)state.window.invalidateBaseline();
            state.epoch=epoch;state.sequence=impulse.sequence();
            state.window.sent(impulse,System.currentTimeMillis());
        }
    }
    /** Must be called after the motion packet is written on this connection. */
    public void afterSend(User user,long sequence) {
        if(user.getClientVersion().isOlderThan(ClientVersion.V_1_17))return;
        var state=states.get(user.getUUID());if(state==null)return;
        synchronized(state) {
            if(state.sequence!=sequence)return;
            int id;do{id=random.nextInt();}while(state.replies.containsKey(id));
            state.currentReply=id;state.replies.put(id,sequence);
            while(state.replies.size()>128)state.replies.remove(state.replies.keySet().iterator().next());
            user.sendPacket(new WrapperPlayServerPing(id));
        }
    }
    public boolean reply(UUID uuid,int id) {
        var state=states.get(uuid);if(state==null)return false;
        synchronized(state) {
            Long sequence=state.replies.remove(id);if(sequence==null)return false;
            if(id==state.currentReply&&sequence==state.sequence)state.window.acknowledge();
            return true;
        }
    }
    public void attack(UUID uuid) {
        var state=states.get(uuid);if(state==null)return;
        synchronized(state) {
            long now=System.currentTimeMillis();
            // Do not let arbitrary ATTACK spam multiply horizontal velocity by
            // .6 on every movement frame. Modern sprint attacks have a cooldown.
            if(now-state.lastAttackAt>=400) { state.lastAttackAt=now;state.attackPending=true; }
        }
    }
    @Override public void inspect(PacketContext context) {
        if(context.event().isCancelled())return;
        var state=states.computeIfAbsent(context.uuid(),id->new State());
        synchronized(state) {
            var p=context.location();long now=System.currentTimeMillis();
            if(state.epoch>=0&&state.epoch!=context.movementEpoch()) { state.window.invalidateBaseline();state.replies.clear(); }
            state.epoch=context.movementEpoch();
            boolean uncertain=context.timingUncertain()||context.plugin().environment().movementSuppressed(context.uuid())
                    ||context.plugin().environment().authorizedFlightMovement(context.uuid())
                    ||context.plugin().waterMotion().wetNear(context.uuid(),p.getX(),p.getY(),p.getZ(),now)
                    ||context.plugin().environment().collisionChangeNear(context.uuid(),p.getX(),p.getY(),p.getZ(),p.getX(),p.getY(),p.getZ(),now);
            var env=context.plugin().environment().get(context.uuid());
            var frame=MultiStepMotionPredictor.frame(p.getYaw(),context.inputs(),env,context.plugin().environment().collisions(context.uuid()));
            var finding=state.window.sample(context.flying().hasPositionChanged(),p.getX(),p.getY(),p.getZ(),frame,now,
                    uncertain,state.attackPending&&now-state.lastAttackAt<=400);
            state.attackPending=false;
            if(finding.confirmed()) {
                flagLimited(context,"acknowledged server motion has no legal input/collision response (velocity suppression)");
                if(context.plugin().cancel(this,context.uuid())) {
                    context.cancel(this);
                    if(env!=null)context.plugin().correctJavaMovement(context.uuid(),env.x(),env.y(),env.z());
                }
            }
            if(finding.expired()&&context.plugin().cancel(this,context.uuid()))context.cancel(this);
        }
    }
    @Override public void forget(UUID uuid) { super.forget(uuid);states.remove(uuid); }
}
