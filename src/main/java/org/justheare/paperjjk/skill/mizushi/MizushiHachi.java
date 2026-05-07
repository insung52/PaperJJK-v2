package org.justheare.paperjjk.skill.mizushi;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.justheare.paperjjk.entity.JEntity;
import org.justheare.paperjjk.entity.JPlayer;
import org.justheare.paperjjk.network.PacketIds;
import org.justheare.paperjjk.skill.ActiveSkill;

import java.util.List;

/**
 * 어주자 스킬 2 — 팔(hachi).
 *
 * 흐름:
 *   키 홀드  → CHARGING: chargeBuffer 에 CE 적립 (최대 ~2초분)
 *   키 뗌    → ACTIVE: storedPower 확정, 5초 타임아웃 시작
 *   접촉     → 0.4초 딜레이 후 HachiStrike 1회 발동 → 종료
 *   타임아웃  → 데미지 없이 종료 (단, 딜레이 카운트다운 중이면 타임아웃 무시)
 */
public class MizushiHachi extends ActiveSkill {

    private static final double POWER_SCALE     = 500.0;
    /** 접촉 대기 최대 틱 (5초) */
    private static final int    CONTACT_TIMEOUT = 100;
    /** 접촉 후 데미지 딜레이 틱 (0.4초) */
    private static final int    DAMAGE_DELAY    = 8;
    /** 접촉 판정 반경 (블록) */
    private static final double CONTACT_RADIUS  = 2.0;

    private static final Particle.DustOptions DUST_CHARGE =
            new Particle.DustOptions(Color.fromRGB(180, 0, 60), 0.4f);

    // ── 상태 ──────────────────────────────────────────────────────────────

    /** onCharged() 에서 확정된 스킬 파워 */
    private double       storedPower     = 0;
    /** 접촉 대기 경과 틱 */
    private int          timeoutTick     = 0;
    /** 접촉 후 데미지 딜레이 카운트다운. -1 = 아직 접촉 없음. */
    private int          damageCountdown = -1;
    /** 접촉한 대상 */
    private LivingEntity contactTarget   = null;

    public MizushiHachi(JEntity caster) {
        super(caster);
    }

    // ── 재충전 비활성화 ───────────────────────────────────────────────────

    @Override
    public void startRecharging() { /* 새 hachi 는 재충전 없음 */ }

    // ── 충전 중 ───────────────────────────────────────────────────────────

    @Override
    protected void onChargingTick() {
        if (!(caster instanceof JPlayer jp)) { end(); return; }
        Player p = jp.player;

        chargeBufferMax = caster.cursedEnergy.getMaxOutput(1.0) * 40.0;

        // 충전 파티클
        Location eye = p.getEyeLocation();
        p.getWorld().spawnParticle(Particle.DUST,
                eye.clone().add(
                        (Math.random() - 0.5) * 0.4,
                        (Math.random() - 0.5) * 0.4,
                        (Math.random() - 0.5) * 0.4),
                1, 0, 0, 0, 0, DUST_CHARGE, true);
    }

    // ── 충전 완료 ─────────────────────────────────────────────────────────

    @Override
    protected void onCharged() {
        if (chargeBuffer <= 0) { end(); return; }
        double efficiency = 1.0 + caster.cursedEnergy.getEfficiencyLevel() * 0.01;
        storedPower = chargeBuffer * efficiency / POWER_SCALE;
        timeoutTick = 0;

        if (caster instanceof JPlayer jp) {
            jp.player.playSound(jp.player.getLocation(),
                    Sound.ITEM_SPEAR_LUNGE_3, SoundCategory.PLAYERS, 1f, 0.6f);
        }
    }

    // ── 발동 중 (접촉 대기) ───────────────────────────────────────────────

    @Override
    protected void onActiveTick() {
        if (!(caster instanceof JPlayer jp)) { end(); return; }
        Player p = jp.player;

        // 딜레이 카운트다운 진행 중 (타임아웃 체크 건너뜀)
        if (damageCountdown > 0) {
            damageCountdown--;
            if (damageCountdown == 0) {
                applyStrike();
                end();
            }
            return;
        }

        // 타임아웃 체크
        timeoutTick++;
        if (timeoutTick >= CONTACT_TIMEOUT) {
            end();
            return;
        }

        // 2블럭 반경 근접 접촉 감지
        Location center = p.getLocation().add(0, p.getHeight() / 2.0, 0);
        List<LivingEntity> nearby = (List<LivingEntity>) center.getNearbyLivingEntities(
                CONTACT_RADIUS, CONTACT_RADIUS, CONTACT_RADIUS);

        for (LivingEntity entity : nearby) {
            if (entity.equals(p)) continue;
            triggerContact(entity);
            break;
        }
    }

    /**
     * 근접 접촉 또는 직접 공격으로 대상이 결정될 때 공통 진입점.
     * 이미 카운트다운 중이면 무시한다.
     */
    private void triggerContact(LivingEntity target) {
        if (damageCountdown != -1) return;
        contactTarget   = target;
        damageCountdown = DAMAGE_DELAY;
        if (caster instanceof JPlayer jp) {
            jp.player.getWorld().playSound(jp.player.getLocation(),
                    Sound.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 0.5f, 0.5f);
        }
    }

    /** 공격 트리거: 직접 타격 시 발동. */
    @Override
    public void onAttackLanded(LivingEntity target) {
        if (!isActive()) return;
        triggerContact(target);
    }

    @Override
    protected void onEnd() {
        if (contactTarget == null && caster instanceof JPlayer jp) {
            jp.player.playSound(jp.player.getLocation(),
                    Sound.BLOCK_RESPAWN_ANCHOR_SET_SPAWN, SoundCategory.PLAYERS, 0.1f, 1.5f);
        }
    }

    private void applyStrike() {
        if (contactTarget == null || !contactTarget.isValid()) return;
        Location hitLoc = contactTarget.getLocation().add(0, contactTarget.getHeight() / 2.0, 0);
        HachiStrike.apply(caster, contactTarget, hitLoc, storedPower);
        contactTarget.addScoreboardTag("hachi");
    }

    // ── HUD ───────────────────────────────────────────────────────────────

    @Override
    public float getGaugePercent() {
        double cap = chargeBufferMax > 0 ? chargeBufferMax : 1;
        return switch (getPhase()) {
            case CHARGING -> (float) Math.min(1.0, chargeBuffer / cap);
            case ACTIVE   -> (float) Math.max(0.0, 1.0 - (double) timeoutTick / CONTACT_TIMEOUT);
            case ENDED    -> 0.0f;
        };
    }

    @Override
    public byte getSlotGaugeState() {
        return switch (phase) {
            case CHARGING -> PacketIds.SlotGaugeState.CHARGING;
            case ACTIVE   -> PacketIds.SlotGaugeState.ACTIVE;
            case ENDED    -> PacketIds.SlotGaugeState.NONE;
        };
    }

    // ── MizushiTechnique.defend() 호환 ────────────────────────────────────

    public double getPower()            { return storedPower; }
    public void reducePower(double amt) { storedPower = Math.max(0, storedPower - amt); }
}
