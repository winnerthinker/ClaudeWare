package com.atl.module.modules;

import com.atl.event.PacketEvent;
import com.atl.mixin.IMinecraft;
import com.atl.module.Claude;
import com.atl.module.management.BooleanSetting;
import com.atl.module.management.Category;
import com.atl.module.management.ModeSetting;
import com.atl.module.management.Module;
import com.atl.module.management.NumberSetting;
import com.atl.util.BlockUtil;
import com.atl.util.RandomUtil;
import com.atl.util.RotationUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.util.Arrays;
import java.util.List;

/**
 * Automatic telly bridge.
 *
 * Emulates the exact legitimate telly inputs: the player does all movement
 * (sprint / jump / keys), this module only spins the real camera and fires
 * right-clicks through the vanilla key pipeline. After a sprint-jump the
 * module turns 180 degrees, looks down and places a flat line of blocks one
 * level above the jump origin (first block on the top face of the jumped-from
 * block, then side-face extensions) to catch the fall, then turns back.
 * The bridge goes up 1 block per jump.
 */
public class TellyBridge extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();

    // Sub-block hit-vec scan offsets (same set scaffold/Clutch use)
    private static final double[] PLACE_OFFSETS = {
            0.03125, 0.09375, 0.15625, 0.21875, 0.28125, 0.34375,
            0.40625, 0.46875, 0.53125, 0.59375, 0.65625, 0.71875,
            0.78125, 0.84375, 0.90625, 0.96875
    };

    // ── Settings ─────────────────────────────────────────────────────────────
    private final NumberSetting turnDelay   = new NumberSetting("Turn delay", 0, 0, 500, 5);
    private final NumberSetting tellyLength = new NumberSetting("Telly length", 6, 1, 16, 1);
    private final NumberSetting pitch       = new NumberSetting("Pitch", 70, 40, 89, 1);
    private final NumberSetting rotMinSpeed = new NumberSetting("Rot speed min", 30, 10, 120, 1);
    private final NumberSetting rotMaxSpeed = new NumberSetting("Rot speed max", 40, 10, 120, 1);
    private final ModeSetting turnDirection = new ModeSetting("Turn direction", "Fastest", "Fastest", "Left", "Right");
    private final NumberSetting cps         = new NumberSetting("CPS", 0, 0, 3, 1);
    private final BooleanSetting autoSlot   = new BooleanSetting("Auto slot", true);
    private final BooleanSetting requireForward = new BooleanSetting("Require forward", true);

    private enum State { IDLE, TURN, PLACE, TURNBACK }

    private State state = State.IDLE;

    // ── Cycle state ──────────────────────────────────────────────────────────
    private BlockPos cycleOrigin;
    private BlockPos lastPlaced;
    private BlockPos pendingSupport;
    private EnumFacing pendingFace;
    private int placedCount;
    private float travelYaw;
    private float originalYaw, originalPitch;
    private EnumFacing travelFacing;
    private int turnDelayTicks;
    private boolean wasAirborne;
    private boolean shuttingDown;
    private int originalSlot = -1;

    // ── Rotation state ───────────────────────────────────────────────────────
    private float curYaw, curPitch;
    private float aimYaw, aimPitch;
    private float spinStartYaw, spinTarget, spinFinalYaw, spinPitchTarget;
    private float spinProgress;
    private float spinSign; // 0 = fastest, -1 = left, +1 = right

    public TellyBridge() {
        super("TellyBridge", "Automatic telly bridge: spins around and places a catch line after your sprint-jump", Category.MOVEMENT);
        addSettings(turnDelay, tellyLength, pitch, rotMinSpeed, rotMaxSpeed, turnDirection, cps, autoSlot, requireForward);
    }

    @Override
    public boolean handleSetCommand(String[] parts) {
        if (parts.length < 4) return false;
        switch (parts[2].toLowerCase()) {
            case "turndelay":
                turnDelay.setValue(Double.parseDouble(parts[3]));
                return true;
            case "length":
            case "tellylength":
                tellyLength.setValue(Double.parseDouble(parts[3]));
                return true;
            case "pitch":
                pitch.setValue(Double.parseDouble(parts[3]));
                return true;
            case "rotspeedmin":
                rotMinSpeed.setValue(Double.parseDouble(parts[3]));
                return true;
            case "rotspeedmax":
                rotMaxSpeed.setValue(Double.parseDouble(parts[3]));
                return true;
            case "turndirection":
                if (parts[3].equalsIgnoreCase("fastest")) turnDirection.index = 0;
                else if (parts[3].equalsIgnoreCase("left")) turnDirection.index = 1;
                else if (parts[3].equalsIgnoreCase("right")) turnDirection.index = 2;
                else return false;
                return true;
            case "cps":
                cps.setValue(Double.parseDouble(parts[3]));
                return true;
            case "autoslot":
                autoSlot.enabled = Boolean.parseBoolean(parts[3]);
                return true;
            case "requireforward":
                requireForward.enabled = Boolean.parseBoolean(parts[3]);
                return true;
        }
        return false;
    }

    @Override
    public List<String> getSettings() {
        String cpsLabel;
        switch ((int) cps.value) {
            case 0: cpsLabel = "20"; break;
            case 1: cpsLabel = "15"; break;
            case 2: cpsLabel = "10"; break;
            default: cpsLabel = "5"; break;
        }
        return Arrays.asList(
                "State: " + state.name(),
                "Telly length: " + (int) tellyLength.value + " blocks",
                "Pitch: " + (int) pitch.value + " degrees",
                "Turn delay: " + (int) turnDelay.value + "ms",
                "Turn direction: " + turnDirection.getValue(),
                "CPS: " + cpsLabel
        );
    }

    // ── Tick ─────────────────────────────────────────────────────────────────

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        if (!isEnabled() && !shuttingDown) return;
        if (mc.thePlayer == null || mc.theWorld == null) { hardAbort(); return; }
        if (mc.thePlayer.isDead) { hardAbort(); return; }
        if (mc.currentScreen != null || mc.thePlayer.capabilities.isFlying) { hardAbort(); return; }

        boolean airborne = !mc.thePlayer.onGround;
        tickMachine(airborne);
        wasAirborne = airborne;

        if (shuttingDown && state == State.IDLE) {
            shuttingDown = false;
            fullReset();
        }
    }

    private void tickMachine(boolean airborne) {
        switch (state) {
            case IDLE:
                // Fresh ground -> air transition starts a cycle at the block under the feet
                if (airborne && !wasAirborne) {
                    lastPlaced = null;
                    tryStartCycle(blockUnderFeet());
                }
                break;
            case TURN:
                if (turnDelayTicks > 0) {
                    turnDelayTicks--;
                    return;
                }
                if (spinDone()) {
                    state = State.PLACE;
                } else {
                    advanceSpin();
                }
                break;
            case PLACE:
                if (!airborne || placedCount >= (int) tellyLength.value) {
                    beginTurnBack();
                } else {
                    placeTick();
                }
                break;
            case TURNBACK:
                if (spinDone()) {
                    finishCycle(airborne);
                } else {
                    advanceSpin();
                }
                break;
        }
    }

    // ── Cycle lifecycle ──────────────────────────────────────────────────────

    private boolean tryStartCycle(BlockPos origin) {
        if (origin == null) return false;
        if (requireForward.isEnabled()
                && !Keyboard.isKeyDown(mc.gameSettings.keyBindForward.getKeyCode())) return false;
        if (BlockUtil.isReplaceable(origin)) return false;        // no solid block under the jump
        if (!BlockUtil.isReplaceable(origin.up())) return false;  // spawn spot occupied
        if (mc.thePlayer.getDistance(origin.getX() + 0.5, origin.getY() + 0.5, origin.getZ() + 0.5)
                > mc.playerController.getBlockReachDistance() + 0.5) return false;

        if (!isHoldingBlock()) ensureBlockSlot();
        if (!isHoldingBlock()) return false;

        cycleOrigin = origin;
        lastPlaced = null;
        pendingSupport = null;
        pendingFace = null;
        placedCount = 0;
        travelYaw = mc.thePlayer.rotationYaw;
        originalYaw = mc.thePlayer.rotationYaw;
        originalPitch = mc.thePlayer.rotationPitch;
        travelFacing = facingFromYaw(travelYaw);

        curYaw = mc.thePlayer.rotationYaw;
        curPitch = mc.thePlayer.rotationPitch;
        spinStartYaw = curYaw;
        spinTarget = spinFinalYaw = RotationUtil.quantizeAngle(MathHelper.wrapAngleTo180_float(travelYaw + 180.0f));
        spinPitchTarget = (float) pitch.value;
        spinProgress = 0;
        spinSign = turnDirection.getValue().equals("Fastest") ? 0.0f
                : (turnDirection.getValue().equals("Left") ? -1.0f : 1.0f);
        turnDelayTicks = msToTicks(turnDelay.value);
        state = State.TURN;
        return true;
    }

    private void beginTurnBack() {
        syncUseKey();
        pendingSupport = null;
        pendingFace = null;
        spinStartYaw = curYaw;
        spinFinalYaw = originalYaw;
        spinPitchTarget = originalPitch;
        spinProgress = 0;
        state = State.TURNBACK;
    }

    private void finishCycle(boolean airborne) {
        // Player re-jumped mid turn-back: chain straight off the last placed block
        if (airborne && Keyboard.isKeyDown(mc.gameSettings.keyBindForward.getKeyCode())
                && lastPlaced != null && tryStartCycle(lastPlaced)) {
            return;
        }
        lastPlaced = null;
        state = State.IDLE;
    }

    private void hardAbort() {
        shuttingDown = false;
        fullReset();
    }

    // ── Placement ────────────────────────────────────────────────────────────

    private void placeTick() {
        applyClickTimer();

        BlockPos support;
        EnumFacing face;
        if (lastPlaced == null) {
            support = cycleOrigin;
            face = EnumFacing.UP;
        } else {
            support = lastPlaced;
            face = travelFacing;
        }

        BlockPos spawn = support.offset(face);
        if (!BlockUtil.isReplaceable(spawn) || BlockUtil.isInteractable(support)
                || !inReach(support)) {
            pendingSupport = null;
            pendingFace = null;
            syncUseKey();
            return;
        }

        Vec3 hitVec = findSupportHitVec(support, face);
        if (hitVec == null) {
            pendingSupport = null;
            pendingFace = null;
            syncUseKey();
            return;
        }

        smoothRotate(aimYaw, aimPitch);

        // Only click when the real camera (and thus the vanilla ray) is on the face
        if (!cameraOnFace(support, face)) {
            pendingSupport = null;
            pendingFace = null;
            syncUseKey();
            return;
        }

        int useKey = mc.gameSettings.keyBindUseItem.getKeyCode();
        KeyBinding.setKeyBindState(useKey, true);
        KeyBinding.onTick(useKey);
        pendingSupport = support;
        pendingFace = face;
    }

    @SubscribeEvent
    public void onSendPacket(PacketEvent.Send event) {
        if (!isEnabled() && !shuttingDown) return;
        if (state != State.PLACE || pendingSupport == null || pendingFace == null) return;
        if (!(event.getPacket() instanceof C08PacketPlayerBlockPlacement)) return;

        C08PacketPlayerBlockPlacement packet = (C08PacketPlayerBlockPlacement) event.getPacket();
        if (packet.getPosition().equals(pendingSupport)
                && packet.getPlacedBlockDirection() == pendingFace.getIndex()) {
            lastPlaced = pendingSupport.offset(pendingFace);
            placedCount++;
            pendingSupport = null;
            pendingFace = null;
        }
    }

    private Vec3 findSupportHitVec(BlockPos support, EnumFacing face) {
        double[] xs = PLACE_OFFSETS, ys = PLACE_OFFSETS, zs = PLACE_OFFSETS;
        switch (face) {
            case NORTH: zs = new double[]{0.0}; break;
            case EAST:  xs = new double[]{1.0}; break;
            case SOUTH: zs = new double[]{1.0}; break;
            case WEST:  xs = new double[]{0.0}; break;
            case DOWN:  ys = new double[]{0.0}; break;
            case UP:    ys = new double[]{1.0}; break;
        }

        float bestYaw = 0.0f, bestPitch = 0.0f, bestDiff = Float.MAX_VALUE;
        Vec3 bestVec = null;
        double reach = mc.playerController.getBlockReachDistance();

        for (double dx : xs) {
            for (double dy : ys) {
                for (double dz : zs) {
                    double relX = support.getX() + dx - mc.thePlayer.posX;
                    double relY = support.getY() + dy - mc.thePlayer.posY - mc.thePlayer.getEyeHeight();
                    double relZ = support.getZ() + dz - mc.thePlayer.posZ;

                    float[] rots = RotationUtil.getRotationsTo(relX, relY, relZ, curYaw, curPitch);
                    MovingObjectPosition mop = RotationUtil.rayTrace(rots[0], rots[1], reach, 1.0f);
                    if (mop != null
                            && mop.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                            && mop.getBlockPos().equals(support)
                            && mop.sideHit == face) {
                        float diff = Math.abs(rots[0] - curYaw) + Math.abs(rots[1] - curPitch);
                        if (diff < bestDiff) {
                            bestYaw = rots[0];
                            bestPitch = rots[1];
                            bestDiff = diff;
                            bestVec = mop.hitVec;
                        }
                    }
                }
            }
        }

        if (bestVec != null) {
            aimYaw = bestYaw;
            aimPitch = bestPitch;
        }
        return bestVec;
    }

    private boolean cameraOnFace(BlockPos support, EnumFacing face) {
        if (Math.abs(MathHelper.wrapAngleTo180_float(mc.thePlayer.rotationYaw - curYaw)) > 1.5f) return false;
        if (Math.abs(mc.thePlayer.rotationPitch - curPitch) > 1.5f) return false;

        MovingObjectPosition mop = RotationUtil.rayTrace(
                mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch,
                mc.playerController.getBlockReachDistance(), 1.0f);
        return mop != null
                && mop.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                && mop.getBlockPos().equals(support)
                && mop.sideHit == face;
    }

    // ── Rotation ─────────────────────────────────────────────────────────────

    private void advanceSpin() {
        if (spinSign == 0.0f) {
            spinTarget = spinFinalYaw;
        } else {
            float step = RandomUtil.nextFloat((float) rotMinSpeed.value, (float) rotMaxSpeed.value);
            spinProgress += step;
            if (spinProgress >= 180.0f) {
                spinTarget = spinFinalYaw;
            } else {
                spinTarget = RotationUtil.quantizeAngle(
                        MathHelper.wrapAngleTo180_float(spinStartYaw + spinSign * spinProgress));
            }
        }
        smoothRotate(spinTarget, spinPitchTarget);
    }

    private boolean spinDone() {
        return Math.abs(MathHelper.wrapAngleTo180_float(spinTarget - curYaw)) < 1.5f
                && Math.abs(spinPitchTarget - curPitch) < 1.5f;
    }

    private void smoothRotate(float wantYaw, float wantPitch) {
        float tolerance = RandomUtil.nextFloat((float) rotMinSpeed.value, (float) rotMaxSpeed.value);

        float yawDiff = RotationUtil.wrapAngleDiff(wantYaw - curYaw, mc.thePlayer.rotationYaw);
        if (Math.abs(yawDiff) > tolerance) {
            curYaw = RotationUtil.quantizeAngle(curYaw + RotationUtil.clampAngle(yawDiff, tolerance));
        } else {
            curYaw = RotationUtil.quantizeAngle(wantYaw);
        }

        float pitchDiff = RotationUtil.wrapAngleDiff(wantPitch - curPitch, mc.thePlayer.rotationPitch);
        if (Math.abs(pitchDiff) > tolerance) {
            curPitch = RotationUtil.quantizeAngle(curPitch + RotationUtil.clampAngle(pitchDiff, tolerance));
        } else {
            curPitch = RotationUtil.quantizeAngle(wantPitch);
        }
        curPitch = MathHelper.clamp_float(curPitch, -89.9f, 89.9f);

        Claude.rotationManager.setRotation(curYaw, curPitch, 3, false);
    }

    // ── Utilities ────────────────────────────────────────────────────────────

    private void applyClickTimer() {
        IMinecraft accessor = (IMinecraft) mc;
        int target;
        switch ((int) cps.value) {
            case 0: target = 0; break;
            case 1: target = (mc.thePlayer.ticksExisted % 4 == 0) ? 1 : 0; break;
            case 2: target = 1; break;
            case 3: target = 3; break;
            default: return;
        }
        if (accessor.getRightClickDelayTimer() > target) {
            accessor.setRightClickDelayTimer(target);
        }
    }

    private void syncUseKey() {
        int useKey = mc.gameSettings.keyBindUseItem.getKeyCode();
        boolean pressed;
        if (useKey < 0) {
            // Mouse bindings are stored as button - 100 (RMB = -99)
            pressed = Mouse.isButtonDown(useKey + 100);
        } else {
            pressed = Keyboard.isKeyDown(useKey);
        }
        KeyBinding.setKeyBindState(useKey, pressed);
    }

    private boolean inReach(BlockPos pos) {
        return mc.thePlayer.getDistance(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)
                <= mc.playerController.getBlockReachDistance() + 0.5;
    }

    private boolean ensureBlockSlot() {
        if (!autoSlot.isEnabled()) return false;
        int slot = findBlockSlot();
        if (slot == -1) return false;
        if (originalSlot == -1) originalSlot = mc.thePlayer.inventory.currentItem;
        mc.thePlayer.inventory.currentItem = slot;
        mc.playerController.updateController();
        return true;
    }

    private int findBlockSlot() {
        for (int i = 0; i < 9; i++) {
            ItemStack s = mc.thePlayer.inventory.getStackInSlot(i);
            if (s != null && s.getItem() instanceof ItemBlock
                    && ((ItemBlock) s.getItem()).getBlock().isFullCube()) {
                return i;
            }
        }
        return -1;
    }

    private boolean isHoldingBlock() {
        ItemStack held = mc.thePlayer.getHeldItem();
        return held != null && held.getItem() instanceof ItemBlock;
    }

    private BlockPos blockUnderFeet() {
        return new BlockPos(
                MathHelper.floor_double(mc.thePlayer.posX),
                MathHelper.floor_double(mc.thePlayer.posY) - 1,
                MathHelper.floor_double(mc.thePlayer.posZ));
    }

    private EnumFacing facingFromYaw(float yaw) {
        float rad = (float) Math.toRadians(yaw);
        double fx = -Math.sin(rad);
        double fz = Math.cos(rad);
        if (Math.abs(fx) > Math.abs(fz)) {
            return fx > 0 ? EnumFacing.WEST : EnumFacing.EAST;
        }
        return fz > 0 ? EnumFacing.SOUTH : EnumFacing.NORTH;
    }

    private int msToTicks(double ms) {
        double raw = ms / 50.0;
        int base = (int) raw;
        return base + (RandomUtil.nextDouble(0.0, 1.0) < (raw - base) ? 1 : 0);
    }

    private void fullReset() {
        syncUseKey();
        if (originalSlot != -1 && mc.thePlayer != null) {
            mc.thePlayer.inventory.currentItem = originalSlot;
            mc.playerController.updateController();
        }
        originalSlot = -1;
        state = State.IDLE;
        cycleOrigin = null;
        lastPlaced = null;
        pendingSupport = null;
        pendingFace = null;
        placedCount = 0;
        wasAirborne = false;
    }

    @Override
    public void onDisable() {
        // Never self-disable mid-cycle: wind down through the turn-back instead
        if (state != State.IDLE) {
            shuttingDown = true;
            return;
        }
        fullReset();
    }
}
