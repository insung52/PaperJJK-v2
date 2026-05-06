package org.justheare.paperjjk.skill.infinity;

import org.bukkit.Location;
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
 * 동작:
 *   - 활성 중 항상 기본 결계 유지 (BASE_POWER 수준), 틱당 소량 CE 소모
 *   - 키 홀드 → 충전: chargeBuffer 증가 → 효과 강화
 *   - 키 릴리즈 → ACTIVE: remainingPower 감소하며 BASE_POWER 로 수렴 (꺼지지 않음)
 *   - 재충전 가능 (ACTIVE 중 키 재홀드)
 *   - Shift+단축키 → 스킬 종료
 *
 * 효과 강도 = BASE_POWER + remainingPower  (remainingPower = 추가 충전분)
 */
public class InfinityPassive extends ActiveSkill {

    /** 충전 CE → 파워 변환 스케일. 튜닝용. */
    private static final double POWER_SCALE = 5000.0;

    /** 기본 활성 파워 (충전 없이도 항상 유지) */
    private static final double BASE_POWER = 1.0;

    /** 발동 중 틱당 추가 파워 감소량. 튜닝용. */
    private static final double DECAY_PER_TICK = 0.5;

    /** 기본 CE 소모율: maxOutput × 이 비율/틱. 튜닝용. */
    private static final double BASE_DRAIN_RATIO = 1;

    private static final int CHARGE_SOUND_INTERVAL = 5;
    private static final int DAMAGE_INTERVAL = 3;

    // ── 상태 ──────────────────────────────────────────────────────────────

    /** 충전으로 쌓인 추가 파워 (0 이하 = 기본 상태) */
    private double remainingPower = 0;

    private int  chargeSoundTick = 0;
    private int  tickCount       = 0;
    private boolean isRecharging = false;

    // ── 생성자 ────────────────────────────────────────────────────────────

    public InfinityPassive(JEntity caster) {
        super(caster);
    }

    // ── 재충전 (ACTIVE 중 키 재홀드) ─────────────────────────────────────

    @Override
    public void startRecharging() {
        isRecharging = true;
        chargeSoundTick = 0;
        if (phase == SkillPhase.ACTIVE) {
            double efficiency = 1.0 + caster.cursedEnergy.getEfficiencyLevel() * 0.01;
            chargeBuffer = remainingPower * POWER_SCALE / efficiency;
            phase = SkillPhase.CHARGING;
        }
    }

    // ── 충전 중 ───────────────────────────────────────────────────────────

    @Override
    protected void onChargingTick() {
        if (!(caster instanceof JPlayer jp)) { end(); return; }
        Player p = jp.player;

        chargeBufferMax = caster.cursedEnergy.getMaxOutput(1.0) * 100.0;
        chargeSoundTick++;
        tickCount++;

        // 기본 CE 소모 (기본 결계 유지 비용)
        caster.cursedEnergy.rawForceConsume(BASE_DRAIN_RATIO);
        if (caster.cursedEnergy.getCurrent() <= 0) { end(); return; }

        // 충전 사운드
        if (chargeSoundTick % CHARGE_SOUND_INTERVAL == 0) {
            double chargeRatio = chargeBufferMax > 0 ? chargeBuffer / chargeBufferMax : 0;
            float vol   = (float) Math.min(1.0, chargeRatio * 0.8);
            float pitch = (float) (chargeRatio * 1.5 + 0.5);
            p.getWorld().playSound(p.getLocation(),
                    Sound.BLOCK_TRIAL_SPAWNER_ABOUT_TO_SPAWN_ITEM, vol, pitch);
        }

        // 결계 효과 (충전 중에도 동작)
        double ep = BASE_POWER + remainingPower + chargeBuffer / POWER_SCALE;
        processEntities(p, p.getEyeLocation(), ep);
    }

    // ── 충전 완료 ─────────────────────────────────────────────────────────

    @Override
    protected void onCharged() {
        isRecharging = false;
        chargeSoundTick = 0;

        double efficiency = 1.0 + caster.cursedEnergy.getEfficiencyLevel() * 0.01;
        remainingPower = chargeBuffer * efficiency / POWER_SCALE;
        // chargeBuffer는 stopCharging()에서 0으로 초기화됨
    }

    // ── 발동 중 ───────────────────────────────────────────────────────────

    @Override
    protected void onActiveTick() {
        if (!(caster instanceof JPlayer jp)) { end(); return; }
        Player p = jp.player;

        // 기본 CE 소모
        caster.cursedEnergy.rawForceConsume(BASE_DRAIN_RATIO);
        if (caster.cursedEnergy.getCurrent() <= 0) { end(); return; }

        // 추가 파워 감소 (0 이하가 되면 기본 상태, 종료하지 않음)
        remainingPower = Math.max(0, remainingPower - DECAY_PER_TICK - remainingPower * 0.004);

        tickCount++;
        double ep = BASE_POWER + remainingPower;
        processEntities(p, p.getEyeLocation(), ep);
    }

    @Override
    protected void onEnd() {
        if (caster instanceof JPlayer jp) {
            jp.player.getWorld().playSound(jp.player.getLocation(),
                    Sound.BLOCK_RESPAWN_ANCHOR_SET_SPAWN, 1f, 2f);
        }
    }

    // ── 결계 로직 ─────────────────────────────────────────────────────────

    private double currentRadius(double power) {
        return 1.0 + Math.sqrt(Math.max(0, power)) * 0.2;
    }

    private void processEntities(Player user, Location center, double power) {
        double radius = currentRadius(power);
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
                Vector pushDir = entityCenter.toVector().subtract(center.toVector());
                if (pushDir.length() > 0.01) {
                    entity.setVelocity(pushDir.normalize().multiply(0.3));
                }
                if (dist < adjustedRadius - 3 && tickCount % DAMAGE_INTERVAL == 0
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
        double cap = chargeBufferMax > 0 ? chargeBufferMax : 1;
        double efficiency = 1.0 + caster.cursedEnergy.getEfficiencyLevel() * 0.01;
        return switch (getPhase()) {
            case CHARGING -> (float) Math.min(1.0, chargeBuffer / cap);
            case ACTIVE   -> (float) Math.max(0.0, Math.min(1.0, remainingPower * POWER_SCALE / efficiency / cap));
            case ENDED    -> 0.0f;
        };
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
