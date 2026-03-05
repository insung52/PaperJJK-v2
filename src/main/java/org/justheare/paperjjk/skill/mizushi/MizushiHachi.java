package org.justheare.paperjjk.skill.mizushi;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.util.Vector;
import org.justheare.paperjjk.damage.DamageInfo;
import org.justheare.paperjjk.entity.JEntity;
import org.justheare.paperjjk.entity.JPlayer;
import org.justheare.paperjjk.network.PacketIds;
import org.justheare.paperjjk.skill.ActiveSkill;

import java.util.List;

/**
 * 어주자 스킬 2 — 팔(hachi).
 *
 * InfinityPassive 와 유사하게 동작하지만,
 * - MIN_POWER 없음: 파워가 0까지 줄어들어 자동 종료
 * - 범위 내 적 접촉 시 참격 데미지 (CE 우열 반영)
 * - 적의 공격을 방어 (MizushiTechnique.defend() 연동)
 * - 피격 대상에 "hachi" 태그 부여
 *
 * rechargeable=true: 키 재홀드로 파워 보충 가능.
 */
public class MizushiHachi extends ActiveSkill {

    /** 재충전 틱당 파워 증가 */
    private static final double POWER_PER_CHARGE_TICK = 3.0;
    /** 발동 중 틱당 파워 감소 (InfinityPassive 와 달리 0까지 감소) */
    private static final double POWER_DECAY_PER_TICK  = 0.1;
    private static final double MAX_POWER             = 100.0;
    /** CE 소모: 파워 1당 틱당 */
    private static final double CE_PER_POWER_PER_TICK = 0.05;
    /** 데미지 간격 (틱) */
    private static final int    DAMAGE_INTERVAL       = 3;
    private static final int    CHARGE_SOUND_INTERVAL = 5;

    // ── 상태 ──────────────────────────────────────────────────────────────

    private double power = 0.0;
    private int    chargeDurationTicks = 0;
    private int    chargeSoundTick     = 0;
    private int    tickCount           = 0;
    private boolean isRecharging       = false;

    public MizushiHachi(JEntity caster) {
        // perTickChargeRequest=0: CE 는 consume() 으로 직접 소모
        super(caster, 0);
    }

    // ── 재충전 ────────────────────────────────────────────────────────────

    @Override
    public void startRecharging() {
        isRecharging = true;
        chargeDurationTicks = 0;
        chargeSoundTick = 0;
        super.startRecharging();
    }

    // ── 충전 중 ───────────────────────────────────────────────────────────

    @Override
    protected void onChargingTick() {
        if (!(caster instanceof JPlayer jp)) { end(); return; }
        Player p = jp.player;

        chargeDurationTicks++;
        chargeSoundTick++;

        if (isRecharging) {
            power = Math.min(MAX_POWER, power + POWER_PER_CHARGE_TICK);
        } else {
            // 최초 충전: 틱 비례로 파워 증가
            power = Math.min(MAX_POWER, chargeDurationTicks * POWER_PER_CHARGE_TICK);
        }

        if (!drainCE(jp)) return;

        if (chargeSoundTick % CHARGE_SOUND_INTERVAL == 0) {
            float vol   = (float)(power / 100.0);
            float pitch = (float)(power / 100.0 * 1.5 + 0.5);
            //p.getWorld().playSound(p.getLocation(), Sound.BLOCK_TRIAL_SPAWNER_ABOUT_TO_SPAWN_ITEM, vol, pitch);
        }

        tickCount++;
        processEntities(p);
    }

    // ── 충전 완료 ─────────────────────────────────────────────────────────

    @Override
    protected void onCharged() {
        isRecharging = false;
        chargeDurationTicks = 0;
        chargeSoundTick = 0;
        if (power <= 0) power = 1.0;
    }

    // ── 발동 중 ───────────────────────────────────────────────────────────

    @Override
    protected void onActiveTick() {
        if (!(caster instanceof JPlayer jp)) { end(); return; }
        Player p = jp.player;

        power -= POWER_DECAY_PER_TICK;
        // InfinityPassive 와 달리 0 이하이면 종료 (MIN_POWER 없음)
        if (power <= 0) {
            end();
            return;
        }

        if (!drainCE(jp)) return;

        tickCount++;
        processEntities(p);
    }

    @Override
    protected void onEnd() {
        if (caster instanceof JPlayer jp) {
            //jp.player.getWorld().playSound(jp.player.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_SET_SPAWN, 1f, 2f);
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

    // ── 결계·참격 로직 ────────────────────────────────────────────────────

    private double currentRadius() {
        return 1.0 + Math.sqrt(power) * 0.4;
    }

    private void processEntities(Player user) {
        Location center = user.getEyeLocation();
        double radius = currentRadius()/3;

        List<Entity> nearby = (List<Entity>) center.getNearbyEntities(
                radius + 2, radius + 2, radius + 2);

        for (Entity entity : nearby) {
            if (entity.equals(user)) continue;

            double entityHeight  = entity.getHeight();
            double entityWidth   = entity.getWidth();
            Location entityCenter = entity.getLocation().add(0, entityHeight / 2, 0);
            double dist = entityCenter.distance(center);
            double adjustedRadius = radius + entityHeight / 3 + entityWidth / 3;

            if (dist > adjustedRadius) continue;

            if (dist <= adjustedRadius) {
                // 안쪽: 밀어내기 + 참격 데미지
                Vector pushDir = entityCenter.toVector().subtract(center.toVector());
                if (pushDir.length() > 0.01 && entity instanceof Projectile) {
                    entity.setVelocity( entity.getVelocity().add( pushDir.normalize().multiply(1) ) );
                }
                if (tickCount % DAMAGE_INTERVAL == 0 && entity instanceof LivingEntity living) {
                    HachiStrike.apply(caster, living, entityCenter, power);
                    living.addScoreboardTag("hachi");
                }
                power -= 5;
            }
        }
    }


    // ── HUD ──────────────────────────────────────────────────────────────

    @Override
    public float getGaugePercent() {
        return (float)(power / MAX_POWER);
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

    // ── 공개 접근자 (MizushiTechnique.defend() 용) ──────────────────────

    public double getPower() { return power; }

    /** MizushiTechnique.defend() 에서 피격 흡수 시 파워 차감 */
    public void reducePower(double amount) {
        power = Math.max(0, power - amount);
    }
}
