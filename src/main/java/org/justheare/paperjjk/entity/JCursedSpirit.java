package org.justheare.paperjjk.entity;

import org.bukkit.entity.LivingEntity;

/**
 * 주령(Cursed Spirit). JEntity 구현체.
 * 특성:
 * - 반전술식 사용 불가 (reverseOutput = null 고정)
 * - REVERSED_CURSED 타입 데미지에 추가 취약
 * - 기본 주력으로 신체 회복 가능 (반전술식 없이)
 * - 주술적으로 생물 취급 → 영역전개 필중 대상
 */
public class JCursedSpirit extends JEntity {

    private final LivingEntity livingEntity;

    /** REVERSED_CURSED 데미지 추가 배율 */
    public static final double REVERSED_CURSE_VULNERABILITY = 2.0;

    public JCursedSpirit(LivingEntity entity, double maxCursedEnergy) {
        super(entity, maxCursedEnergy, false /* 반전술식 불가 */, 0.005);
        this.livingEntity = entity;
    }

    // ── 틱 처리 ───────────────────────────────────────────────────────────

    @Override
    public void onTick() {
        super.onTick();

        // 주령은 기본 주력으로 신체 회복 가능 (반전술식 없이)
        // 조건: 체력이 최대체력 미만이고 주력 충분
        if (getHealthPercent() < 1.0 && cursedEnergy.getCurrent() > 0) {
            applySpiritSelfHeal();
        }
    }

    /** 주령 고유 자연 회복 — 주력 소모 → 체력 회복 */
    private void applySpiritSelfHeal() {
        double healCost = cursedEnergy.getMax() * 0.0001;
        if (!cursedEnergy.consume(healCost)) return;
        double healAmount = healCost * 0.001;
        double newHealth = Math.min(livingEntity.getMaxHealth(),
                livingEntity.getHealth() + healAmount);
        livingEntity.setHealth(newHealth);
    }

    // ── JEntity 추상 메서드 구현 ──────────────────────────────────────────

    @Override
    public double getHealthPercent() {
        return livingEntity.getHealth() / livingEntity.getMaxHealth();
    }

    @Override
    public LivingEntity getLivingEntity() {
        return livingEntity;
    }
}
