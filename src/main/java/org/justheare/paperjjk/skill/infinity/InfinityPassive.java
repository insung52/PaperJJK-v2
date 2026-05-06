package org.justheare.paperjjk.skill.infinity;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.util.Vector;
import org.justheare.paperjjk.damage.DamageInfo;
import org.justheare.paperjjk.damage.DamageType;
import org.justheare.paperjjk.entity.JEntity;
import org.justheare.paperjjk.entity.JPlayer;
import org.justheare.paperjjk.barrier.DomainManager;
import org.justheare.paperjjk.network.JEntityManager;
import org.justheare.paperjjk.network.JPacketSender;
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

    /** 클라이언트 패킷 동기화 간격 (틱) */
    private static final int SYNC_INTERVAL = 5;

    /**
     * 클라이언트 power 정규화 기준 (CE 5천만 풀충전 시 remainingPower 최대값).
     * = sqrt(50_000_000) * 100 / POWER_SCALE ≈ 141.4
     */
    private static final double MAX_REMAINING_POWER = Math.sqrt(50_000_000.0) * 100.0 / POWER_SCALE;

    /**
     * 이 값 이상 remainingPower 가 있을 때만 클라이언트 효과 활성화.
     * 이하에서는 power ≈ 0.005 → 화면에 거의 안 보여 끄나 마나 동일.
     */
    private static final double VISUAL_THRESHOLD = 5.0;

    // ── 상태 ──────────────────────────────────────────────────────────────

    /** 충전으로 쌓인 추가 파워 (0 이하 = 기본 상태) */
    private double remainingPower = 0;

    private int  chargeSoundTick      = 0;
    private int  tickCount            = 0;
    private int  networkSyncTick      = 0;
    private int  collisionCountThisTick = 0;
    private boolean isRecharging      = false;
    private boolean activateSent      = false;
    /** 현재 클라이언트에 배리어가 활성화되어 있는지 추적 */
    private boolean clientVisible     = false;

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
        double efficiency = 1.0 + caster.cursedEnergy.getEfficiencyLevel() * 0.01;
        double ep = BASE_POWER + chargeBuffer * efficiency / POWER_SCALE;
        collisionCountThisTick = 0;
        processEntities(p, p.getEyeLocation(), ep);

        // 클라이언트 네트워크 동기화 (충전량이 충분할 때만)
        syncClientVisibility(p, ep);
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
        collisionCountThisTick = 0;
        processEntities(p, p.getEyeLocation(), ep);

        syncClientVisibility(p, ep);
    }

    @Override
    protected void onEnd() {
        if (caster instanceof JPlayer jp) {
            jp.player.getWorld().playSound(jp.player.getLocation(),
                    Sound.BLOCK_RESPAWN_ANCHOR_SET_SPAWN, 1f, 2f);
            if (clientVisible) {
                clientVisible = false;
                JPacketSender.broadcastInfinityPassiveDeactivate(
                    jp.player, DomainManager.BROADCAST_RANGE);
            }
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
                //spawnBarrierParticle(center, entityCenter, radius);

                // COLLISION 패킷 (틱당 최대 3개)
                if (collisionCountThisTick < 3 && activateSent) {
                    float speed = (float) entity.getVelocity().length();
                    float intensity = Math.min(1.0f, speed * 2.0f + 0.2f);
                    // 충돌 지점 = 배리어 표면 (center → entityCenter 방향)
                    Vector hitDir = entityCenter.toVector().subtract(center.toVector());
                    Location hitPos = center.clone().add(
                        hitDir.length() > 0.01 ? hitDir.normalize().multiply(radius) : new Vector(0, radius, 0));
                    JPacketSender.broadcastInfinityPassiveCollision(
                        user, hitPos, intensity, DomainManager.BROADCAST_RANGE);
                    collisionCountThisTick++;
                }
            } else if (entity instanceof Projectile){
                entity.setVelocity(entity.getVelocity().multiply(0.01));
                //spawnBarrierParticle(center, entityCenter, radius);
            } else {
                entity.setVelocity(entity.getVelocity().multiply(0.7));
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

    /**
     * remainingPower 가 VISUAL_THRESHOLD 이상이면 클라이언트 효과 활성화/동기화.
     * 이하로 떨어지면 DEACTIVATE 전송.
     */
    private void syncClientVisibility(Player p, double ep) {
        boolean shouldBeVisible = ep >= VISUAL_THRESHOLD;

        if (shouldBeVisible) {
            if (!clientVisible) {
                clientVisible = true;
                activateSent  = true;
                JPacketSender.broadcastInfinityPassiveActivate(
                    p, (float) currentRadius(ep), toClientPower(ep), DomainManager.BROADCAST_RANGE);
            } else if (++networkSyncTick % SYNC_INTERVAL == 0) {
                JPacketSender.broadcastInfinityPassiveSync(
                    p, (float) currentRadius(ep), toClientPower(ep), DomainManager.BROADCAST_RANGE);
            }
        } else if (clientVisible) {
            clientVisible = false;
            JPacketSender.broadcastInfinityPassiveDeactivate(p, DomainManager.BROADCAST_RANGE);
        }
    }

    /** ep → 클라이언트 power [0.005, 1.0] */
    private float toClientPower(double ep) {
        double rp = ep - BASE_POWER;
        return (float) Math.max(0.005, Math.min(1.0, rp / MAX_REMAINING_POWER));
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
