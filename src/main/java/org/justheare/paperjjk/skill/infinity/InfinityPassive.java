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
import org.justheare.paperjjk.skill.SkillPhase;

import java.util.List;

/**
 * 무한(Infinity) 패시브 — 상시 결계(Barrier).
 *
 * CE 흐름:
 *   - 충전 중(키 홀드): distribution 시스템이 chargeBuffer 를 채움
 *   - 발동 중(키 뗌): chargeBuffer 가 틱마다 감소, 0이 되면 종료
 *   - 재충전(키 재홀드): chargeBuffer 다시 채움 (기존 버퍼에 누적)
 *
 * 결계 강도 = chargeBuffer / POWER_SCALE (0~100 스케일 기준)
 *
 * POWER_SCALE 튜닝: grade3 풀충전(≈2000CE) 시 강도≈100 이 되도록 설정.
 */
public class InfinityPassive extends ActiveSkill {

    /** 충전 CE → 파워 변환 스케일. 튜닝용. */
    private static final double POWER_SCALE = 5000.0;

    /** 발동 중 틱당 chargeBuffer 감소량 (CE 단위). 튜닝용. */
    private static final double DECAY_PER_TICK = 30.0;

    private static final int CHARGE_SOUND_INTERVAL = 5;

    // ── 상태 ──────────────────────────────────────────────────────────────

    private int  chargeSoundTick = 0;
    private int  tickCount       = 0;
    private boolean isRecharging = false;

    // ── 생성자 ────────────────────────────────────────────────────────────

    public InfinityPassive(JEntity caster) {
        super(caster);
        this.chargeBufferMax = Double.MAX_VALUE;
    }

    // ── 재충전 ────────────────────────────────────────────────────────────

    @Override
    public void startRecharging() {
        isRecharging = true;
        chargeSoundTick = 0;
        // chargeBuffer 초기화 없이 그대로 유지 → 기존 버퍼에 추가 충전
        if (phase == SkillPhase.ACTIVE) {
            phase = SkillPhase.CHARGING;
        }
    }

    // ── 생명주기 ──────────────────────────────────────────────────────────

    @Override
    protected void onChargingTick() {
        if (!(caster instanceof JPlayer jp)) { end(); return; }
        Player p = jp.player;

        chargeSoundTick++;

        double power = chargeBuffer / POWER_SCALE;

        // 충전 사운드
        if (chargeSoundTick % CHARGE_SOUND_INTERVAL == 0) {
            float vol   = (float) Math.min(1.0, power / 100.0);
            float pitch = (float) (Math.min(1.0, power / 100.0) * 1.5 + 0.5);
            p.getWorld().playSound(p.getLocation(),
                    Sound.BLOCK_TRIAL_SPAWNER_ABOUT_TO_SPAWN_ITEM, vol, pitch);
        }

        // 결계 효과 (충전 중에도 동작)
        tickCount++;
        Location eyeLoc = p.getEyeLocation();
        processEntities(p, eyeLoc);
    }

    @Override
    protected void onCharged() {
        isRecharging = false;
        chargeSoundTick = 0;
    }

    @Override
    protected void onActiveTick() {
        if (!(caster instanceof JPlayer jp)) { end(); return; }
        Player p = jp.player;

        // chargeBuffer 감소 → 0이면 종료
        chargeBuffer = Math.max(0, chargeBuffer - DECAY_PER_TICK);
        if (chargeBuffer <= 0) {
            end();
            return;
        }

        tickCount++;
        Location eyeLoc = p.getEyeLocation();
        processEntities(p, eyeLoc);
    }

    @Override
    protected void onEnd() {
        if (caster instanceof JPlayer jp) {
            jp.player.getWorld().playSound(jp.player.getLocation(),
                    Sound.BLOCK_RESPAWN_ANCHOR_SET_SPAWN, 1f, 2f);
        }
    }

    // ── 결계 로직 ─────────────────────────────────────────────────────────

    private double currentRadius() {
        double power = chargeBuffer / POWER_SCALE;
        return 1.0 + Math.sqrt(Math.max(0, power)) * 0.4;
    }

    private void processEntities(Player user, Location center) {
        double radius = currentRadius();
        List<Entity> nearby = (List<Entity>) center.getNearbyEntities(radius + 1, radius + 1, radius + 1);
        double power = chargeBuffer / POWER_SCALE;

        for (Entity entity : nearby) {
            if (entity.equals(user)) continue;

            double entityHeight = entity.getHeight();
            double entityWidth  = entity.getWidth();
            Location entityCenter = entity.getLocation().add(0, entityHeight / 2, 0);
            double dist = entityCenter.distance(center);
            double adjustedRadius = radius + entityHeight / 3 + entityWidth / 3;

            if (dist > adjustedRadius + 1) continue;

            if (dist < adjustedRadius - 1) {
                Vector pushDir = entityCenter.toVector().subtract(center.toVector());
                if (pushDir.length() > 0.01) {
                    entity.setVelocity(pushDir.normalize().multiply(0.3));
                }
                if (dist < adjustedRadius - 3 && tickCount % 3 == 0
                        && entity instanceof LivingEntity living) {
                    applyPassiveDamage(living, dist, power);
                }
                spawnBarrierParticle(center, entityCenter, radius);
            } else {
                entity.setVelocity(entity.getVelocity().multiply(0.2));
                spawnBarrierParticle(center, entityCenter, radius);
            }
        }
    }

    private void applyPassiveDamage(LivingEntity living, double dist, double power) {
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

    private void spawnBarrierParticle(Location center, Location entityCenter, double radius) {
        Vector dir = entityCenter.toVector().subtract(center.toVector());
        if (dir.length() < 0.01) return;
        Location particleLoc = center.clone().add(dir.normalize().multiply(radius));
        center.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, particleLoc, 5, 0.2, 0.2, 0.2, 0.01);
    }

    // ── HUD ───────────────────────────────────────────────────────────────

    @Override
    public float getGaugePercent() {
        return (float) Math.min(1.0, chargeBuffer / POWER_SCALE / 100.0);
    }

    @Override
    public byte getSlotGaugeState() {
        return switch (phase) {
            case CHARGING -> isRecharging
                    ? PacketIds.SlotGaugeState.RECHARGING
                    : PacketIds.SlotGaugeState.CHARGING;
            case ACTIVE   -> PacketIds.SlotGaugeState.ACTIVE;
            case ENDED    -> PacketIds.SlotGaugeState.NONE;
        };
    }
}
