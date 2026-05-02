package org.justheare.paperjjk.cursed;

import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

/**
 * 흑섬(Black Flash) 확률 및 Zone 상태 관리.
 *
 * 확률 구조:
 *   - baseProbability  : 평소 발동 확률
 *   - zoneProbability  : 흑섬 발동 직후 확률
 *   - 발동 시 currentZoneTick = MAX_ZONE_TICKS (재발동 시 중첩 없이 초기화)
 *   - 매 틱 currentZoneTick 감소 → 실제 확률이 zone → base 로 선형 감소
 *   - zone 중 실패 → sessionCount 초기화
 *
 * Zone 이펙트 (구 플러그인 black_flash() 충실 재현):
 *   - 발동 후 첫 20틱(버스트): spread 0→19 로 퍼지는 ENTITY_EFFECT + FLASH
 *     파티클 수 = sessionCount 비례 (최대 10배)
 *   - 항상: 검은 ENTITY_EFFECT 주변 파티클 1개
 *   - 54틱마다: BLOCK_CONDUIT_AMBIENT 사운드 (플레이어에게만, zone 비율 비례 볼륨)
 */
public class BlackFlashState {

    // ── 확률 ──────────────────────────────────────────────────────────────

    private double baseProbability;
    private double zoneProbability;

    private static final double BASE_DAMAGE_MULTIPLIER = 2.5;

    // ── Zone 상태 ─────────────────────────────────────────────────────────

    private int currentZoneTick = 0;
    private static final int MAX_ZONE_TICKS  = 20 * 60; // 60초
    private static final int BURST_DURATION  = 20;       // 버스트 파티클 지속 틱

    private static final double ZONE_OUTPUT_MULTIPLIER = 2.0;
    private static final double ZONE_BODY_REIN_BONUS   = 1.5;

    // ── 성장 추적 ─────────────────────────────────────────────────────────

    private int lifeTimeCount = 0;
    /** 현재 zone 세션 연속 발동 수. zone 중 실패 시 초기화. */
    private int sessionCount  = 0;

    // ── 생성자 ────────────────────────────────────────────────────────────

    public BlackFlashState(double baseProbability) {
        this(baseProbability, 0.4);
    }

    public BlackFlashState(double baseProbability, double zoneProbability) {
        this.baseProbability = baseProbability;
        this.zoneProbability = zoneProbability;
    }

    // ── 핵심 동작 ─────────────────────────────────────────────────────────

    /**
     * 흑섬 발동 판정. 신체강화 활성화 시 DamagePipeline/JEvent 에서 호출.
     * - 발동: sessionCount++, currentZoneTick 초기화
     * - zone 중 실패: sessionCount 초기화
     */
    public boolean tryTrigger() {
        double current = currentProbability();
        if (Math.random() < current) {
            lifeTimeCount++;
            sessionCount++;
            currentZoneTick = MAX_ZONE_TICKS;
            return true;
        }
        // zone 중 실패 → 연속 횟수 초기화
        if (isInZone()) {
            sessionCount = 0;
        }
        return false;
    }

    /**
     * 매 틱 호출 (JEntity.onTick() 에서 getLivingEntity() 넘겨 호출).
     * Zone 감소 + 파티클·사운드 이펙트.
     */
    public void onTick(LivingEntity entity) {
        if (currentZoneTick <= 0) return;
        currentZoneTick--;

        if (currentZoneTick == 0) return;

        int    burstElapsed = MAX_ZONE_TICKS - currentZoneTick; // 1 ~ MAX
        double ratio        = getZoneRatio();

        // ── 버스트 파티클 (발동 후 첫 20틱, 퍼짐 반경 0→19) ──────────────
        if (burstElapsed <= BURST_DURATION) {
            double spread   = burstElapsed - 1.0; // 0 → 19
            int    mincount = Math.max(1, Math.min(sessionCount, 10));

            entity.getWorld().spawnParticle(
                    Particle.ENTITY_EFFECT, entity.getLocation(),
                    40 * mincount, spread, spread, spread, 0.5,
                    Color.BLACK);
            entity.getWorld().spawnParticle(
                    Particle.ENTITY_EFFECT, entity.getLocation(),
                    40 * mincount, spread, spread, spread, 0.5,
                    Color.fromRGB(128, 0, 0));
            entity.getWorld().spawnParticle(
                    Particle.FLASH, entity.getLocation(),
                    3 * mincount, spread, spread, spread, 10.0,
                    Color.fromARGB(128, 255, 50, 50));
            entity.getWorld().spawnParticle(
                    Particle.FLASH, entity.getLocation(),
                    3 * mincount, spread, spread, spread, 10.0,
                    Color.fromARGB(128, 255, 255, 255));
        }

        // ── 항상: 검은 ENTITY_EFFECT 주변 파티클 ─────────────────────────
        entity.getWorld().spawnParticle(
                Particle.ENTITY_EFFECT, entity.getLocation(),
                1, 0.5, 0.5, 0.5, 0.5,
                Color.BLACK);

        // ── 54틱마다: CONDUIT 사운드 (플레이어에게만, zone 비율 비례 볼륨) ─
        if (currentZoneTick % 54 == 40 && entity instanceof Player player) {
            float vol = (float)(ratio * 1.2f);
            if (vol > 0.05f) {
                player.playSound(player, Sound.BLOCK_CONDUIT_AMBIENT, vol, 1.5f);
            }
        }
    }

    // ── 확률 계산 ─────────────────────────────────────────────────────────

    /** 현재 실제 발동 확률: base + (zone - base) × zoneRatio */
    private double currentProbability() {
        return baseProbability + (zoneProbability - baseProbability) * getZoneRatio();
    }

    /** Zone 비율. 0.0 = zone 없음, 1.0 = 방금 발동. */
    public double getZoneRatio() {
        return (double) currentZoneTick / MAX_ZONE_TICKS;
    }

    // ── 데미지 배율 ───────────────────────────────────────────────────────

    public double getDamageMultiplier() {
        return BASE_DAMAGE_MULTIPLIER + Math.min(sessionCount - 1, 5) * 0.1;
    }

    // ── Zone 연동 배율 ────────────────────────────────────────────────────

    public boolean isInZone()          { return currentZoneTick > 0; }

    public double getOutputMultiplier() {
        return isInZone() ? ZONE_OUTPUT_MULTIPLIER : 1.0;
    }

    public double getBodyReinBonus() {
        return isInZone() ? ZONE_BODY_REIN_BONUS : 1.0;
    }

    // ── 조회/설정 ─────────────────────────────────────────────────────────

    public int    getLifeTimeCount()      { return lifeTimeCount; }
    public int    getSessionCount()       { return sessionCount; }
    public double getBaseProbability()    { return baseProbability; }
    public double getZoneProbability()    { return zoneProbability; }

    public void setLifeTimeCount(int count)     { this.lifeTimeCount = count; }
    public void setBaseProbability(double prob) { this.baseProbability = Math.max(0, prob); }
    public void setZoneProbability(double prob) { this.zoneProbability = Math.max(0, prob); }
}
