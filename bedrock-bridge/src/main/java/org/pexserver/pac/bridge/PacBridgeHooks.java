package org.pexserver.pac.bridge;

import ac.boar.anticheat.Boar;
import ac.boar.anticheat.alert.AlertViolationListener;
import ac.boar.protocol.PacketEvents;
import ac.boar.protocol.api.CloudburstPacketEvent;
import ac.boar.protocol.api.PacketListener;
import org.cloudburstmc.math.vector.Vector2f;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType;

/** Wires the upstream Bedrock engine to PAC without changing its prediction pipeline. */
public final class PacBridgeHooks {
    private PacBridgeHooks() { }

    public static void install() {
        PacketEvents.getApi().register(new PacketListener() {
            @Override public void onPacketReceived(CloudburstPacketEvent event) {
                if (event.getPacket() instanceof InventoryTransactionPacket transaction
                        && transaction.getActionType() == 1
                        && transaction.getTransactionType() == InventoryTransactionType.ITEM_USE_ON_ENTITY) {
                    PacPaperBridge.forwardAttack(event.getPlayer().getSession().uuid(),
                            (int) transaction.getRuntimeEntityId());
                }
                if (event.getPacket() instanceof PlayerAuthInputPacket input) {
                    var inputs = input.getInputData();
                    boolean directional = inputs.contains(PlayerAuthInputData.UP)
                            || inputs.contains(PlayerAuthInputData.DOWN)
                            || inputs.contains(PlayerAuthInputData.LEFT)
                            || inputs.contains(PlayerAuthInputData.RIGHT)
                            || inputs.contains(PlayerAuthInputData.UP_LEFT)
                            || inputs.contains(PlayerAuthInputData.UP_RIGHT)
                            || inputs.contains(PlayerAuthInputData.DOWN_LEFT)
                            || inputs.contains(PlayerAuthInputData.DOWN_RIGHT)
                            || input.getAnalogMoveVector() != null
                            && (Math.abs(input.getAnalogMoveVector().getX()) > 1.0E-4
                            || Math.abs(input.getAnalogMoveVector().getY()) > 1.0E-4)
                            || input.getRawMoveVector() != null
                            && (Math.abs(input.getRawMoveVector().getX()) > 1.0E-4
                            || Math.abs(input.getRawMoveVector().getY()) > 1.0E-4);
                    boolean jump = inputs.contains(PlayerAuthInputData.JUMP_DOWN)
                            || inputs.contains(PlayerAuthInputData.JUMPING)
                            || inputs.contains(PlayerAuthInputData.START_JUMPING)
                            || inputs.contains(PlayerAuthInputData.JUMP_PRESSED_RAW)
                            || inputs.contains(PlayerAuthInputData.JUMP_CURRENT_RAW);
                    int mode = PacPaperBridge.bedrockInventoryInput(
                            event.getPlayer().getSession().uuid(), directional, jump);
                    if (mode > 0) {
                        inputs.remove(PlayerAuthInputData.UP);
                        inputs.remove(PlayerAuthInputData.DOWN);
                        inputs.remove(PlayerAuthInputData.LEFT);
                        inputs.remove(PlayerAuthInputData.RIGHT);
                        inputs.remove(PlayerAuthInputData.UP_LEFT);
                        inputs.remove(PlayerAuthInputData.UP_RIGHT);
                        inputs.remove(PlayerAuthInputData.DOWN_LEFT);
                        inputs.remove(PlayerAuthInputData.DOWN_RIGHT);
                        inputs.remove(PlayerAuthInputData.ASCEND);
                        inputs.remove(PlayerAuthInputData.DESCEND);
                        inputs.remove(PlayerAuthInputData.SPRINT_DOWN);
                        inputs.remove(PlayerAuthInputData.SPRINTING);
                        inputs.remove(PlayerAuthInputData.START_SPRINTING);
                        inputs.remove(PlayerAuthInputData.JUMP_DOWN);
                        inputs.remove(PlayerAuthInputData.JUMPING);
                        inputs.remove(PlayerAuthInputData.START_JUMPING);
                        inputs.remove(PlayerAuthInputData.JUMP_PRESSED_RAW);
                        inputs.remove(PlayerAuthInputData.JUMP_CURRENT_RAW);
                        input.setMotion(Vector2f.from(0, 0));
                        input.setAnalogMoveVector(Vector2f.from(0, 0));
                        input.setRawMoveVector(Vector2f.from(0, 0));
                        if (mode == 2) {
                            var delta = input.getDelta();
                            if (delta != null) input.setDelta(Vector3f.from(0,
                                    jump && delta.getY() > 0 ? 0 : delta.getY(), 0));
                        }
                    }
                    PacPaperBridge.forwardAuthInput(event.getPlayer().getSession().uuid(), input);
                }
            }
        });

        Boar.getInstance().getViolationRegistry().clear();
        AlertViolationListener fallbackAlerts = new AlertViolationListener();
        Boar.getInstance().getViolationRegistry().register(violation -> {
            if (!PacPaperBridge.forwardViolation(violation)) fallbackAlerts.onViolation(violation);
        });
    }
}
