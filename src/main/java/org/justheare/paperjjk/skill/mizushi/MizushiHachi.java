package org.justheare.paperjjk.skill.mizushi;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.util.Vector;
import org.justheare.paperjjk.entity.JEntity;
import org.justheare.paperjjk.entity.JPlayer;
import org.justheare.paperjjk.network.PacketIds;
import org.justheare.paperjjk.skill.ActiveSkill;
import org.justheare.paperjjk.skill.SkillPhase;

import java.util.List;

/**
 * 어주자 스킬 2 — 팔(hachi).
 *
 * CE 흐름:
 *   - 충전 중(키 홀드): distribution 이 chargeBuffer 를 채움
 *   - 발동 중(키 뗌): chargeBuffer 틱마다 decay, 0이면 종료
 *   - 재충전(키 재홀드): chargeBuffer 다시 채움
 *   - 적 타격 시 추가 decay
 *
 * 결계 강도 = chargeBuffer / POWER_SCALE
 */
public class MizushiHachi extends ActiveSkill {

    /** 충전 CE → 파워 변환 스케일. 튜닝용. */
    private static final double POWER_SCALE    = 500.0;
    /** 발동 중 틱당 decay (CE 단위). 튜닝용. */
    private static final double DECAY_PER_TICK = 2.0;
    /** 적 타격 시 추가 decay (CE 단위). 튜닝용. */
    private static final double DECAY_ON_HIT   = 100.0;
    private static final int    DAMAGE_INTERVAL = 3;

    // ── 상태 ──────────────────────────────────────────────────────────────

    private int  tickCount    = 0;
    private boolean isRecharging = false;

    public MizushiHachi(JEntity caster) {
        super(caster);
        this.chargeBufferMax = Double.MAX_VALUE;
    }

    // ── 재충전 ────────────────────────────────────────────────────────────

    @Override
    public void startRecharging() {
        isRecharging = true;
        if (phase == SkillPhase.ACTIVE) {
            phase = SkillPhase.CHARGING;
        }
    }

    // ── 충전 중 ───────────────────────────────────────────────────────────

    @Override
    protected void onChargingTick() {
        if (!(caster instanceof JPlayer jp)) { end(); return; }
        Player p = jp.player;

        tickCount++;
        processEntities(p);
    }

    // ── 충전 완료 ─────────────────────────────────────────────────────────

    @Override
    protected void onCharged() {
        isRecharging = false;
        if (chargeBuffer <= 0) { end(); }
    }

    // ── 발동 중 ───────────────────────────────────────────────────────────

    @Override
    protected void onActiveTick() {
        if (!(caster instanceof JPlayer jp)) { end(); return; }
        Player p = jp.player;

        chargeBuffer = Math.max(0, chargeBuffer - DECAY_PER_TICK);
        if (chargeBuffer <= 0) { end(); return; }

        tickCount++;
        processEntities(p);
    }

    // ── 결계·참격 로직 ────────────────────────────────────────────────────

    private double currentRadius() {
        double power = chargeBuffer / POWER_SCALE;
        return (1.0 + Math.sqrt(Math.max(0, power)) * 0.4) / 3.0;
    }

    private void processEntities(Player user) {
        Location center = user.getEyeLocation();
        double radius = currentRadius();

        List<LivingEntity> nearby = (List<LivingEntity>) center.getNearbyLivingEntities(
                radius + 2, radius + 2, radius + 2);

        for (LivingEntity entity : nearby) {
            if (entity.equals(user)) continue;

            double entityHeight  = entity.getHeight();
            double entityWidth   = entity.getWidth();
            Location entityCenter = entity.getLocation().add(0, entityHeight / 2, 0);
            double dist = entityCenter.distance(center);
            double adjustedRadius = radius + entityHeight / 3 + entityWidth / 3;

            if (dist > adjustedRadius) continue;

            Vector pushDir = entityCenter.toVector().subtract(center.toVector());
            if (pushDir.length() > 0.01 && entity instanceof Projectile) {
                entity.setVelocity(entity.getVelocity().add(pushDir.normalize()));
            }
            if (tickCount % DAMAGE_INTERVAL == 0 && entity instanceof LivingEntity living) {
                HachiStrike.apply(caster, living, entityCenter, chargeBuffer / POWER_SCALE);
                living.addScoreboardTag("hachi");
                chargeBuffer = Math.max(0, chargeBuffer - DECAY_ON_HIT);
            }
        }
    }

    // ── HUD ──────────────────────────────────────────────────────────────

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

    public double getPower() { return chargeBuffer / POWER_SCALE; }

    /** MizushiTechnique.defend() 에서 피격 흡수 시 파워 차감 */
    public void reducePower(double amount) {
        chargeBuffer = Math.max(0, chargeBuffer - amount * POWER_SCALE);
    }
}
