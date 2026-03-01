package org.justheare.paperjjk.skill.infinity;

import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.*;
import org.bukkit.util.Vector;
import org.bukkit.Location;
import org.justheare.paperjjk.damage.DamageInfo;
import org.justheare.paperjjk.damage.DamageType;
import org.justheare.paperjjk.entity.JEntity;
import org.justheare.paperjjk.entity.JPlayer;
import org.justheare.paperjjk.network.JEntityManager;
import org.justheare.paperjjk.network.JPacketSender;
import org.justheare.paperjjk.skill.ActiveSkill;

import java.util.List;

/**
 * 무한(Infinity) — 창(蒼, Ao) 스킬.
 *
 * 충전 흐름:
 *   - 첫 충전 첫 틱: AO START 패킷 전송 (약한 세기), 충전 파티클/사운드
 *   - 충전 종료(키 뗌): 충전 시간에 비례 remainingPower 확정, AO SYNC
 *   - 발동 중: gaze 추적, 엔티티/블록 처리, 5틱마다 power 감소 + AO SYNC
 *   - 재충전(발동 중 키 누름): phase→CHARGING, 효과는 계속, 충전 완료 시 power 추가
 *   - 종료: AO END 패킷
 */
public class InfinityAo extends ActiveSkill {

    // ── 상수 ──────────────────────────────────────────────────────────────

    /** 틱당 주력 충전 요청량 (CE 시스템과 연동) */
    private static final double PER_TICK_CHARGE = 5.0;

    /** 브로드캐스트 가시 거리 (블록) */
    private static final double VISUAL_RANGE = 1000.0;

    /** N틱마다 remainingPower 감소 + AO SYNC 전송 */
    private static final int SYNC_INTERVAL = 5;

    /** 충전 사운드 재생 주기 (틱) */
    private static final int CHARGE_SOUND_INTERVAL = 5;

    /**
     * 1틱 충전당 파워 증가량.
     * 100틱(5초)에 최대 파워 100 도달.
     */
    private static final double POWER_PER_CHARGE_TICK = 1.0;

    /** 위치 이동 속도 (블록/틱). 목표 위치로 이 속도로 부드럽게 이동. */
    private static final double MOVE_SPEED = 0.5;

    // ── 위치 상태 ──────────────────────────────────────────────────────────

    /** Ao 특이점의 현재 위치. 충전/발동 모두 시선 위치 = aoLocation. */
    private Location aoLocation;

    /** true = 시선 고정 (T 키 토글). false = 매 틱 gaze 갱신. */
    private boolean fixed = false;

    /** 시전자 눈에서의 거리 (스크롤로 조정) */
    private double distance = 3.0;

    // ── 파워 상태 ──────────────────────────────────────────────────────────

    /** 잔여 파워. 충전량에 비례 설정, 0이 되면 종료. */
    private double remainingPower = 0;

    /** 현재 충전 단계의 경과 틱 수 (파워 계산에 사용) */
    private int chargeDurationTicks = 0;

    /** AO START 패킷이 클라이언트로 전송된 상태인가 */
    private boolean aoPacketActive = false;

    /** 재충전 중 (ACTIVE → CHARGING) 플래그 */
    private boolean isRecharging = false;

    private final String uniqueId;

    // ── 내부 카운터 ───────────────────────────────────────────────────────

    private int blockTick = 0;
    private int syncTick = 0;
    private int chargeSoundTick = 0;

    // ── 생성자 ────────────────────────────────────────────────────────────

    public InfinityAo(JEntity caster) {
        super(caster, PER_TICK_CHARGE);
        this.uniqueId = "AO_" + System.nanoTime();
        initAoLocation();
    }

    private void initAoLocation() {
        if (!(caster instanceof JPlayer jp)) return;
        aoLocation = gazeLocation(jp.player);
    }

    private Location gazeLocation(Player p) {
        return p.getEyeLocation().add(p.getEyeLocation().getDirection().multiply(distance));
    }

    /**
     * aoLocation 을 목표(gaze 또는 fixed 위치)로 MOVE_SPEED 블록씩 부드럽게 이동.
     * 목표까지 거리가 MOVE_SPEED 이하면 즉시 합침.
     */
    private void smoothMoveToward(Location target) {
        Vector toTarget = target.toVector().subtract(aoLocation.toVector());
        if (toTarget.length() <= MOVE_SPEED) {
            aoLocation = target;
        } else {
            aoLocation.add(toTarget.normalize().multiply(MOVE_SPEED));
        }
    }

    /** 현재 충전 단계의 파워 추정치 (사운드 볼륨/피치 계산용) */
    private double currentPowerEstimate() {
        double chargePower = chargeDurationTicks * POWER_PER_CHARGE_TICK;
        return isRecharging ? Math.min(100, remainingPower + chargePower) : chargePower;
    }

    // ── 생명주기 ──────────────────────────────────────────────────────────

    @Override
    protected void onChargingTick() {
        if (!(caster instanceof JPlayer jp)) return;
        Player p = jp.player;

        chargeDurationTicks++;

        // 시선 추적 (고정이 아닌 경우): 부드럽게 이동
        if (!fixed) {
            smoothMoveToward(gazeLocation(p));
        }

        // 첫 충전 틱: AO START 패킷 전송
        if (!aoPacketActive) {
            float initStrength = powerToStrength(1.0);
            JPacketSender.broadcastInfinityAoStart(aoLocation, initStrength, uniqueId, VISUAL_RANGE);
            aoPacketActive = true;
        }

        // 충전 파티클
        spawnChargingParticles();

        // 충전 사운드 (기존 BLOCK_TRIAL_SPAWNER_ABOUT_TO_SPAWN_ITEM — 파워에 따라 볼륨/피치 변화)
        if (++chargeSoundTick % CHARGE_SOUND_INTERVAL == 0) {
            double cp = currentPowerEstimate();
            float vol   = (float) (cp / 60.0);
            float pitch = (float) (cp / 100.0 * 1.5 + 0.5);
            aoLocation.getWorld().playSound(aoLocation,
                    Sound.BLOCK_TRIAL_SPAWNER_ABOUT_TO_SPAWN_ITEM, vol, pitch);
        }

        // AO SYNC: 충전 중 파워 미리보기
        if (++syncTick % SYNC_INTERVAL == 0) {
            double previewPower = Math.min(100, currentPowerEstimate());
            JPacketSender.broadcastInfinityAoSync(aoLocation, powerToStrength(previewPower), uniqueId, VISUAL_RANGE);
            syncTick = 0;
        }

        // 재충전 중: 발동 효과 계속 실행 (파워는 현재 예상치 기준)
        if (isRecharging) {
            double ep = currentPowerEstimate();
            aoEntityLogic(p, ep);
            aoBlockLogic(ep);

            // 펄럭거리는 소리 — 재충전 중 파워에 비례한 볼륨
            if (syncTick == 0) {
                aoLocation.getWorld().playSound(aoLocation, Sound.ENTITY_ENDER_DRAGON_FLAP,
                        (float) (ep / 10.0), 0.8f);
            }

            // 재충전 중에는 파워 감소 없음 (충전으로 보충 중)
            if (remainingPower <= 0) {
                end();
            }
        }
    }

    @Override
    protected void onCharged() {
        if (!(caster instanceof JPlayer jp)) { end(); return; }
        Player p = jp.player;

        if (!fixed) {
            smoothMoveToward(gazeLocation(p));
        }

        double chargedPower = Math.min(100.0, chargeDurationTicks * POWER_PER_CHARGE_TICK);

        if (isRecharging) {
            // 재충전 완료: 현재 파워에 추가
            remainingPower = Math.min(100.0, remainingPower + chargedPower);
            isRecharging = false;
        } else {
            // 첫 충전 완료: 충전량으로 초기 파워 결정
            remainingPower = Math.max(1.0, chargedPower);
        }

        chargeDurationTicks = 0;
        chargeSoundTick = 0;
        syncTick = 0;

        // 충전 완료 사운드
        aoLocation.getWorld().playSound(aoLocation, Sound.BLOCK_BEACON_POWER_SELECT, 0.8f, 1.5f);

        // 최종 파워로 SYNC
        JPacketSender.broadcastInfinityAoSync(aoLocation, powerToStrength(remainingPower), uniqueId, VISUAL_RANGE);
    }

    /**
     * 발동 중 재충전 시작 (SkillKeyMap 이 호출).
     * AO 는 종료하지 않고 충전 단계로 돌아감.
     */
    @Override
    public void startRecharging() {
        isRecharging = true;
        chargeDurationTicks = 0;
        chargeSoundTick = 0;
        syncTick = 0;
        super.startRecharging(); // phase = CHARGING, accumulatedCharge = 0
    }

    @Override
    protected void onActiveTick() {
        if (!(caster instanceof JPlayer jp)) { end(); return; }
        Player p = jp.player;

        // 시선 추적 (고정이 아닌 경우): 부드럽게 이동
        if (!fixed) {
            smoothMoveToward(gazeLocation(p));
        }

        // 발동 중 파티클
        Particle.DustOptions dust = new Particle.DustOptions(Color.BLUE,
                (float) (Math.pow(remainingPower, 0.5) / 7));
        aoLocation.getWorld().spawnParticle(Particle.DUST, aoLocation, 1,
                0.02, 0.02, 0.02, 0.5, dust, true);

        // 파워 감소 + SYNC
        if (++syncTick % SYNC_INTERVAL == 0) {
            remainingPower--;
            JPacketSender.broadcastInfinityAoSync(aoLocation, powerToStrength(remainingPower), uniqueId, VISUAL_RANGE);
            aoLocation.getWorld().playSound(aoLocation, Sound.ENTITY_ENDER_DRAGON_FLAP,
                    (float) (remainingPower / 10.0), 0.8f);
            syncTick = 0;
        }

        aoEntityLogic(p, remainingPower);
        aoBlockLogic(remainingPower);

        if (remainingPower <= 0) end();
    }

    @Override
    protected void onEnd() {
        if (aoPacketActive && aoLocation != null) {
            JPacketSender.broadcastInfinityAoEnd(aoLocation, uniqueId, VISUAL_RANGE);
            aoPacketActive = false;
            aoLocation.getWorld().playSound(aoLocation, Sound.BLOCK_RESPAWN_ANCHOR_SET_SPAWN, 3, 2);
        }
    }

    // ── 엔티티 처리 ───────────────────────────────────────────────────────

    private void aoEntityLogic(Player user, double power) {
        double radius = 5 + Math.pow(power, 0.7);
        List<Entity> targets = (List<Entity>) aoLocation.getNearbyEntities(radius, radius, radius);

        for (Entity tentity : targets) {
            if (tentity.equals(user)) continue;

            Vector dVec = aoLocation.toVector().subtract(tentity.getLocation().toVector());
            double dist = dVec.length();
            if (dist > radius) continue;

            double pullRes = getPullResistance(tentity);
            if (pullRes <= 0) continue;

            if (Math.random() < pullRes) {
                tentity.setVelocity(
                        tentity.getVelocity().add(
                                dVec.normalize().multiply(
                                        Math.pow(power, 0.7) / 2.0
                                        / Math.pow(dist + 3, 1.5)
                                )
                        ).multiply(pullRes)
                );
            }

            if (Math.random() < 0.25) {
                if (tentity instanceof LivingEntity living) {
                    if (dist <= 1 + Math.pow(power, 0.3)) {
                        applyAoDamage(living, power);
                    }
                } else if ((tentity instanceof Item || tentity instanceof FallingBlock)
                        && Math.random() < 0.1) {
                    tentity.remove();
                }
            }
        }
    }

    private double getPullResistance(Entity entity) {
        if (JEntityManager.instance == null) return 1.0;
        JEntity jEntity = JEntityManager.instance.get(entity.getUniqueId());
        if (jEntity == null) return 1.0;
        if (jEntity.technique != null && !jEntity.technique.isDomainTarget()) return 0.0;
        return 1.0;
    }

    private void applyAoDamage(LivingEntity living, double power) {
        double output = Math.pow(power, 0.3);
        JEntity targetEntity = JEntityManager.instance != null
                ? JEntityManager.instance.get(living.getUniqueId()) : null;
        if (targetEntity != null) {
            targetEntity.receiveDamage(
                    DamageInfo.skillHit(caster, DamageType.CURSED, output, "infinity_ao"));
        } else {
            living.damage(DamageInfo.outputToDamage(output));
        }
    }

    // ── 블록 이동 ─────────────────────────────────────────────────────────

    private void aoBlockLogic(double power) {
        double sphereRadius = Math.pow(power, 0.6) + 1;
        int outerLoop = (int) power;

        for (int r1 = 0; r1 < outerLoop; r1++) {
            double rr1 = Math.random() * Math.PI * 2;
            for (int r2 = 0; r2 < outerLoop; r2++) {
                double rr2 = Math.random() * Math.PI * 2;
                double rx = Math.sin(rr1) * Math.sin(rr2);
                double ry = Math.cos(rr2);
                double rz = Math.cos(rr1) * Math.sin(rr2);

                Vector rv = new Vector(rx, ry, rz).multiply(sphereRadius);
                double t = Math.pow(Math.random(), 3);
                Location aLoc = aoLocation.clone().add(rv.clone().multiply(t));
                if (aLoc.getBlock().isEmpty()) {
                    if (Math.random() < 0.05) {
                        Particle.DustOptions dust = new Particle.DustOptions(Color.BLUE, 1F);
                        aoLocation.getWorld().spawnParticle(Particle.DUST, aLoc, 1,
                                0.02, 0.02, 0.02, 0.5, dust, true);
                    }
                    continue;
                }

                Material aBlockType = aLoc.getBlock().getType();
                BlockData aBlockData = aLoc.getBlock().getBlockData();
                Location bLoc = aLoc.clone().add(rv.normalize().multiply(-1.2));

                double hardnessThreshold = power / 3.0;
                boolean aOk = aLoc.getBlock().isLiquid()
                        || (aBlockType.getHardness() < hardnessThreshold && aBlockType.getHardness() >= 0);
                boolean bOk = bLoc.getBlock().isLiquid()
                        || (bLoc.getBlock().getType().getHardness() < hardnessThreshold
                        && bLoc.getBlock().getType().getHardness() >= 0);

                if (aOk && bOk) {
                    blockTick++;
                    if (blockTick >= 500) {
                        aLoc.getWorld().spawnFallingBlock(bLoc, aBlockData);
                        aLoc.getBlock().setType(Material.AIR);
                        blockTick = 0;
                    } else {
                        bLoc.getBlock().setType(aBlockType);
                        bLoc.getBlock().setBlockData(aBlockData);
                        aLoc.getBlock().setType(Material.AIR);
                    }
                }
            }
        }
    }

    // ── 파티클 유틸 ───────────────────────────────────────────────────────

    private void spawnChargingParticles() {
        double cp = Math.max(1.0, chargeDurationTicks * POWER_PER_CHARGE_TICK);
        Particle.DustOptions dust = new Particle.DustOptions(Color.BLUE, 0.2F);
        aoLocation.getWorld().spawnParticle(Particle.DUST, aoLocation,
                (int) Math.pow(cp, 0.8),
                Math.log(cp + 1) / 5, Math.log(cp + 1) / 10, Math.log(cp + 1) / 10,
                0, dust, true);
        dust = new Particle.DustOptions(Color.BLUE, (float) (Math.pow(cp, 0.5) / 10));
        aoLocation.getWorld().spawnParticle(Particle.DUST, aoLocation, 1,
                0.1, 0.1, 0.1, 0.5, dust, true);
    }

    // ── 제어 (패킷 수신) ──────────────────────────────────────────────────

    /** SKILL_DISTANCE (스크롤) → 거리 조정 (충전 중에도 즉시 반영) */
    @Override
    public void onScrollDistance(int delta) {
        distance += delta * 3.0;
        if (distance < 1)  distance = 1;
        if (distance > 50) distance = 50;
    }

    /** SKILL_CONTROL (T 키) → 시선 고정 토글 */
    @Override
    public void onControl() {
        fixed = !fixed;
    }

    // ── HUD 게이지 / 슬롯 표시 ───────────────────────────────────────────

    /**
     * 이 스킬의 실제 파워 비율(0~1)을 반환.
     * CHARGING/RECHARGING: 현재까지 쌓인 예상 파워 / 100
     * ACTIVE: 잔여 파워 / 100 (소진될수록 줄어듦)
     */
    @Override
    public float getGaugePercent() {
        return switch (getPhase()) {
            case CHARGING -> (float) Math.min(1.0, currentPowerEstimate() / 100.0);
            case ACTIVE   -> (float) Math.max(0.0, remainingPower / 100.0);
            case ENDED    -> 0.0f;
        };
    }

    /** 시선 고정(fixed) 상태이면 자물쇠 표시 */
    @Override
    public boolean isLocked() { return fixed; }

    /** 현재 설정된 거리값 (예: "3m", "50m") */
    @Override
    public String getSlotLabel() { return String.format("%.0fm", distance); }

    // ── 유틸 ─────────────────────────────────────────────────────────────

    /** remainingPower(0~100) → 클라이언트 strength(0.051~5.0) */
    private float powerToStrength(double power) {
        return (float) (power * 0.049 + 0.051);
    }

    // ── Murasaki 트리거용 공개 게터 ──────────────────────────────────────

    public Location getAoLocation() { return aoLocation; }
    public double getRemainingPower() { return remainingPower; }
}
