package org.pexserver.pac.check.java.action;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class InventoryMoveTracker {
   private static final long INPUT_WINDOW_MILLIS = 250L;
   private final ConcurrentHashMap<UUID, InventoryMoveTracker.State> states = new ConcurrentHashMap<>();

   public void setPlayerInventoryOpen(UUID uuid, boolean open) {
      InventoryMoveTracker.State state = this.states.computeIfAbsent(uuid, ignored -> new InventoryMoveTracker.State());
      synchronized (state) {
         state.playerInventoryOpen = open;
         clearIfClosed(state);
      }
   }

   public void setContainerOpen(UUID uuid, boolean open) {
      InventoryMoveTracker.State state = this.states.computeIfAbsent(uuid, ignored -> new InventoryMoveTracker.State());
      synchronized (state) {
         state.containerOpen = open;
         clearIfClosed(state);
      }
   }

   public boolean inventoryOpen(UUID uuid) {
      InventoryMoveTracker.State state = this.states.get(uuid);
      if (state == null) {
         return false;
      }

      synchronized (state) {
         return open(state);
      }
   }

   public void seedPosition(UUID uuid, InventoryMoveTracker.Position position, long epoch) {
      InventoryMoveTracker.State state = this.states.computeIfAbsent(uuid, ignored -> new InventoryMoveTracker.State());
      synchronized (state) {
         if (state.lastPosition == null && state.movementEpoch == Long.MIN_VALUE) {
            state.lastPosition = position;
            state.movementEpoch = epoch;
         }
      }
   }

   public void movementIntent(UUID uuid, long now) {
      this.movementIntent(uuid, now, true, false);
   }

   public void movementIntent(UUID uuid, long now, boolean directional, boolean jump) {
      InventoryMoveTracker.State state = this.states.computeIfAbsent(uuid, ignored -> new InventoryMoveTracker.State());
      synchronized (state) {
         if (open(state)) {
            state.directionalIntent |= directional;
            state.jumpIntent |= jump;
            state.intentUntil = now + 250L;
         }
      }
   }

   public InventoryMoveTracker.Input input(UUID uuid, InventoryMoveTracker.Input input, long now, boolean suppressMovement) {
      InventoryMoveTracker.State state = this.states.computeIfAbsent(uuid, ignored -> new InventoryMoveTracker.State());
      synchronized (state) {
         boolean active = open(state);
         boolean jumpPressed = input.jump() && !state.jumpDown;
         state.jumpDown = input.jump();
         if (!active) {
            clearIntent(state);
            return input;
         }

         if (input.hasDirectionalInput()) {
            state.directionalIntent = true;
            state.intentUntil = now + 250L;
         }

         if (jumpPressed) {
            state.jumpIntent = true;
            state.intentUntil = now + 250L;
         }

         expireIntent(state, now);
         return !suppressMovement ? input : new InventoryMoveTracker.Input(false, false, false, false, false, input.shift(), false);
      }
   }

   public InventoryMoveTracker.Movement movement(
      UUID uuid, InventoryMoveTracker.Position current, long epoch, boolean externalMotion, boolean enforce, long now
   ) {
      InventoryMoveTracker.State state = this.states.computeIfAbsent(uuid, ignored -> new InventoryMoveTracker.State());
      synchronized (state) {
         if (state.movementEpoch != epoch) {
            state.movementEpoch = epoch;
            state.lastPosition = current;
            clearIntent(state);
            return new InventoryMoveTracker.Movement(false, false, null, 0.0, 0.0, 0.0);
         }

         InventoryMoveTracker.Position previous = state.lastPosition;
         if (previous == null) {
            state.lastPosition = current;
            return new InventoryMoveTracker.Movement(false, false, null, 0.0, 0.0, 0.0);
         }

         double dx = current.x() - previous.x();
         double dy = current.y() - previous.y();
         double dz = current.z() - previous.z();
         if (!open(state)) {
            clearIntent(state);
            state.lastPosition = current;
            return new InventoryMoveTracker.Movement(false, false, null, dx, dy, dz);
         }

         expireIntent(state, now);
         if (externalMotion) {
            state.lastPosition = current;
            clearIntent(state);
            return new InventoryMoveTracker.Movement(false, false, null, dx, dy, dz);
         }

         boolean unauthorizedHorizontal = state.directionalIntent && Math.hypot(dx, dz) > 1.0E-4;
         boolean unauthorizedJump = state.jumpIntent && dy > 0.1;
         boolean violation = unauthorizedHorizontal || unauthorizedJump;
         if (!violation || !enforce) {
            state.lastPosition = current;
         }

         return new InventoryMoveTracker.Movement(violation, violation && enforce, violation ? previous : null, dx, dy, dz);
      }
   }

   public void clearIntent(UUID uuid) {
      InventoryMoveTracker.State state = this.states.get(uuid);
      if (state != null) {
         synchronized (state) {
            clearIntent(state);
         }
      }
   }

   public void forget(UUID uuid) {
      this.states.remove(uuid);
   }

   private static boolean open(InventoryMoveTracker.State state) {
      return state.playerInventoryOpen || state.containerOpen;
   }

   private static void clearIfClosed(InventoryMoveTracker.State state) {
      if (!open(state)) {
         clearIntent(state);
      }
   }

   private static void expireIntent(InventoryMoveTracker.State state, long now) {
      if (now > state.intentUntil) {
         clearIntent(state);
      }
   }

   private static void clearIntent(InventoryMoveTracker.State state) {
      state.directionalIntent = false;
      state.jumpIntent = false;
      state.intentUntil = 0L;
   }

   public record Input(boolean forward, boolean backward, boolean left, boolean right, boolean jump, boolean shift, boolean sprint) {
      public boolean hasDirectionalInput() {
         return this.forward || this.backward || this.left || this.right;
      }

      public boolean hasMovementInput() {
         return this.hasDirectionalInput() || this.jump;
      }
   }

   public record Movement(boolean violation, boolean blocked, InventoryMoveTracker.Position rollback, double dx, double dy, double dz) {
   }

   public record Position(double x, double y, double z) {
   }

   private static final class State {
      boolean playerInventoryOpen;
      boolean containerOpen;
      boolean jumpDown;
      boolean directionalIntent;
      boolean jumpIntent;
      long intentUntil;
      long movementEpoch = Long.MIN_VALUE;
      InventoryMoveTracker.Position lastPosition;
   }
}
