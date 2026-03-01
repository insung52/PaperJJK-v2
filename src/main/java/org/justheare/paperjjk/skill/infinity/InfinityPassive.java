package org.justheare.paperjjk.skill.infinity;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.justheare.paperjjk.damage.DamageInfo;
import org.justheare.paperjjk.damage.DamageType;
import org.justheare.paperjjk.entity.JEntity;
import org.justheare.paperjjk.entity.JPlayer;
import org.justheare.paperjjk.network.JEntityManager;
import org.justheare.paperjjk.network.PacketIds;
import org.justheare.paperjjk.skill.ActiveSkill;

import java.util.List;

/**
 * 무한(Infinity) 패시브 — 상시 결계(Barrier).
 *
 * chargeable=true, rechargeable=true.
 *
 * 최초 활성화 시 파워 1로 즉시 시작.
 * 재충전(키 홀드) 중: 파워 상승, 충전 사운드 재생.
 * 발동 중(키 뗌): 파워 서서히 감소 → MIN_POWER(1) 유지.
 * 항상 현재 파워에 비례해 CE 소모. CE 고갈 시 자동 종료.
 *
 * 반경 = 1 + sqrt(power) * 0.4 블록 (파워 1 ≈ 1.4m, 파워 100 ≈ 5m).
 */
public class InfinityPassive extends ActiveSkill {

    // ── 상수 ──────────────────────────────────────────────────────────────

    /** 재충전 틱당 파워 증가 (100틱 = 5초에 최대 파워 도달) */
    private static final double POWER_PER_CHARGE_TICK = 3.0;

    /** 발동 중 틱당 파워 감소 (파워 100 → 1: 약 33초) */
    private static final double POWER_DECAY_PER_TICK  = 1.5;

    private static final double MIN_POWER = 1.0;
    private static final double MAX_POWER = 100.0;

    /** 파워 1당 틱당 CE 소모 (항상 적용) */
    private static final double CE_PER_POWER_PER_TICK = 0.05;

    /** 충전 사운드 재생 주기 (틱) */
    private static final int CHARGE_SOUND_INTERVAL = 5;

    // ── 상태 ──────────────────────────────────────────────────────────────

    /** 현재 파워 (1~100) */
    private double power = MIN_POWER;

    /** 현재 충전 단계의 경과 틱 */
    private int chargeDurationTicks = 0;

    private int chargeSoundTick = 0;
    private int tickCount = 0;
    private boolean isRecharging = false;

    // ── 생성자 ────────────────────────────────────────────────────────────

    /**
     * perTickChargeRequest=0: CE 분배 시스템은 사용하지 않고
     * consume() 직접 호출로 파워 비례 소모 처리.
     */
    public InfinityPassive(JEntity caster) {
        super(caster, 0);
    }

    // ── 재충전 ────────────────────────────────────────────────────────────

    @Override
    public void startRecharging() {
        isRecharging = true;
        chargeDurationTicks = 0;
        chargeSoundTick = 0;
        super.startRecharging(); // phase = CHARGING, accumulatedCharge = 0
    }

    // ── 생명주기 ──────────────────────────────────────────────────────────

    @Override
    protected void onChargingTick() {
        if (!(caster instanceof JPlayer jp)) { end(); return; }
        Player p = jp.player;

        chargeDurationTicks++;
        chargeSoundTick++;

        // 파워 증가
        if (isRecharging) {
            power = Math.min(MAX_POWER, power + POWER_PER_CHARGE_TICK);
        } else {
            // 최초 충전: 틱 비례 파워 (빠른 탭 → 1에 고정)
            power = Math.max(MIN_POWER, chargeDurationTicks * POWER_PER_CHARGE_TICK);
        }

        // CE 소모 (파워 비례, 항상)
        if (!drainCE(jp)) return;

        // 충전 사운드
        if (chargeSoundTick % CHARGE_SOUND_INTERVAL == 0) {
            float vol   = (float) (power / 100.0);
            float pitch = (float) (power / 100.0 * 1.5 + 0.5);
            p.getWorld().playSound(p.getLocation(),
                    Sound.BLOCK_TRIAL_SPAWNER_ABOUT_TO_SPAWN_ITEM, vol, pitch);
        }

        // 결계 효과 (충전 중에도 동작)
        tickCount++;
        Location eyeLoc = p.getEyeLocation();
        //if (tickCount % 4 == 0) clearFireLava(eyeLoc);
        processEntities(p, eyeLoc);
    }

    @Override
    protected void onCharged() {
        isRecharging = false;
        chargeDurationTicks = 0;
        chargeSoundTick = 0;
        if (power < MIN_POWER) power = MIN_POWER;
    }

    @Override
    protected void onActiveTick() {
        if (!(caster instanceof JPlayer jp)) { end(); return; }
        Player p = jp.player;

        // 파워 감소 → MIN_POWER 유지
        if (power > MIN_POWER) {
            power = Math.max(MIN_POWER, power - POWER_DECAY_PER_TICK);
        }

        // CE 소모
        if (!drainCE(jp)) return;

        tickCount++;
        Location eyeLoc = p.getEyeLocation();
        //if (tickCount % 4 == 0) clearFireLava(eyeLoc);
        processEntities(p, eyeLoc);
    }

    @Override
    protected void onEnd() {
        if (caster instanceof JPlayer jp) {
            jp.player.getWorld().playSound(jp.player.getLocation(),
                    Sound.BLOCK_RESPAWN_ANCHOR_SET_SPAWN, 1f, 2f);
        }
    }

    // ── CE 소모 ───────────────────────────────────────────────────────────

    private boolean drainCE(JPlayer jp) {
        if (!jp.cursedEnergy.consume(power * CE_PER_POWER_PER_TICK)) {
            end();
            return false;
        }
        return true;
    }

    // ── 결계 로직 ─────────────────────────────────────────────────────────

    /** 반경 = 1 + sqrt(power) * 0.4 (파워 1≈1.4, 파워100≈5) */
    private double currentRadius() {
        return 1.0 + Math.sqrt(power) * 0.4;
    }

    private void clearFireLava(Location center) {
        for (int rx = -2; rx <= 2; rx++) {
            for (int ry = -2; ry <= 2; ry++) {
                for (int rz = -2; rz <= 2; rz++) {
                    Location bl = center.clone().add(rx, ry, rz);
                    Material m = bl.getBlock().getType();
                    if (m == Material.LAVA || m == Material.FIRE) {
                        bl.getBlock().setType(Material.AIR);
                    }
                }
            }
        }
    }

    private void processEntities(Player user, Location center) {
        double radius = currentRadius();
        List<Entity> nearby = (List<Entity>) center.getNearbyEntities(radius + 1, radius + 1, radius + 1);

        for (Entity entity : nearby) {
            if (entity.equals(user)) continue;

            double entityHeight = entity.getHeight();
            double entityWidth  = entity.getWidth();
            Location entityCenter = entity.getLocation().add(0, entityHeight / 2, 0);
            double dist = entityCenter.distance(center);
            double adjustedRadius = radius + entityHeight / 3 + entityWidth / 3;

            if (dist > adjustedRadius + 1) continue;

            if (dist < adjustedRadius - 1) {
                // 안쪽 영역: 바깥으로 밀기
                Vector pushDir = entityCenter.toVector().subtract(center.toVector());
                if (pushDir.length() > 0.01) {
                    entity.setVelocity(pushDir.normalize().multiply(0.3));
                }
                // 중심에 매우 가까우면 주력 데미지 (3틱마다)
                if (dist < adjustedRadius - 3 && tickCount % 3 == 0
                        && entity instanceof LivingEntity living) {
                    applyPassiveDamage(living, dist);
                }
                spawnBarrierParticle(center, entityCenter, radius);
            } else {
                // 경계 구간: 속도 감쇄
                entity.setVelocity(entity.getVelocity().multiply(0.2));
                spawnBarrierParticle(center, entityCenter, radius);
            }
        }
    }

    private void applyPassiveDamage(LivingEntity living, double dist) {
        double output = (Math.pow(dist + 0.5, -0.5) + 1) * Math.pow(power, 0.3);
        JEntity targetEntity = JEntityManager.instance != null
                ? JEntityManager.instance.get(living.getUniqueId()) : null;
        if (targetEntity != null) {
            targetEntity.receiveDamage(
                    DamageInfo.skillHit(caster, DamageType.CURSED, output, "infinity_passive"));
        } else {
            living.damage(DamageInfo.outputToDamage(output));
        }
    }

    /** 결계 표면 위 엔티티 방향에 ELECTRIC_SPARK 파티클 */
    private void spawnBarrierParticle(Location center, Location entityCenter, double radius) {
        Vector dir = entityCenter.toVector().subtract(center.toVector());
        if (dir.length() < 0.01) return;
        Location particleLoc = center.clone().add(dir.normalize().multiply(radius));
        center.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, particleLoc, 5, 0.2, 0.2, 0.2, 0.01);
    }

    // ── HUD ───────────────────────────────────────────────────────────────

    /** 현재 파워 비율 (0~1) */
    @Override
    public float getGaugePercent() {
        return (float) (power / MAX_POWER);
    }

    @Override
    public byte getSlotGaugeState() {
        return switch (getPhase()) {
            case CHARGING -> isRecharging
                    ? PacketIds.SlotGaugeState.RECHARGING
                    : PacketIds.SlotGaugeState.CHARGING;
            case ACTIVE   -> PacketIds.SlotGaugeState.ACTIVE;
            case ENDED    -> PacketIds.SlotGaugeState.NONE;
        };
    }
}
