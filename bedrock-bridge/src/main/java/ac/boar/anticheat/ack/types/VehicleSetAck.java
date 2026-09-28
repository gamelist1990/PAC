package ac.boar.anticheat.ack.types;

import ac.boar.anticheat.ack.Acknowledgment;

public record VehicleSetAck(long vehicleRuntimeId, long generation) implements Acknowledgment {
}
