package org.justheare.paperjjk.damage;

import org.justheare.paperjjk.entity.JEntity;

/**
 * 데미지 처리 3단계 파이프라인.
 * 기존 Jobject.damaget() 의 혼합 로직을 분리.
 *
 * Phase 1 — 공격자 측 수정  : 흑섬 판정, 술식반전 배율
 * Phase 2 — 방어자 측 방어  : technique.defend(), 신체강화 감소
 * Phase 3 — 최종 적용       : 체력 감소, 주력 감소, 상태이상
 */
public class DamagePipeline {

    private DamagePipeline() {}

    public static DamageResult process(DamageInfo info, JEntity victim) {
        // Phase 1
        applyAttackerModifiers(info);

        // Phase 2
        DefenceResult defence = applyDefence(info, victim);
        if (defence.fullyBlocked) return DamageResult.blocked();

        // Phase 3
        return applyFinalDamage(info, defence, victim);
    }

    // ── Phase 1 ───────────────────────────────────────────────────────────

    private static void applyAttackerModifiers(DamageInfo info) {
        if (info.attacker == null) return;

        // 흑섬 판정 (물리 공격에만)
        if (info.type == DamageType.PHYSICAL) {
            if (info.attacker.blackFlash.tryTrigger()) {
                info.attackOutput *= info.attacker.blackFlash.getDamageMultiplier();
                info.isBlackFlash = true;
            }
        }

        // 술식반전 배율 (REVERSED_CURSED 타입)
        if (info.type == DamageType.REVERSED_CURSED && info.attacker.canReverseOutput()) {
            info.attackOutput *= info.attacker.reverseOutput.getRCTMultiplier();
        }
    }

    // ── Phase 2 ───────────────────────────────────────────────────────────

    private static DefenceResult applyDefence(DamageInfo info, JEntity victim) {
        // 술식 방어 (technique.defend)
        if (victim.technique != null) {
            DefenceResult techniqueDefence = victim.technique.defend(info);
            if (techniqueDefence.fullyBlocked) return techniqueDefence;
            info.attackOutput *= (1.0 - techniqueDefence.reductionRatio);
        }

        // 신체강화 감소 (PHYSICAL 계열만)
        if (info.type == DamageType.PHYSICAL) {
            double reduction = victim.bodyReinforcement.getDamageReduction();
            info.attackOutput = Math.max(0, info.attackOutput - reduction);
        }

        // 영역 필중 + BITEN 방어
        if (info.type == DamageType.DOMAIN_SURE_HIT) {
            if (victim.bodyReinforcement.blocksDomainSureHit()) {
                return DefenceResult.fullyBlocked();
            }
        }

        return DefenceResult.notBlocked(0);
    }

    // ── Phase 3 ───────────────────────────────────────────────────────────

    private static DamageResult applyFinalDamage(DamageInfo info,
                                                  DefenceResult defence, JEntity victim) {
        if (info.attackOutput <= 0) return DamageResult.blocked();

        double finalDamage = DamageInfo.outputToDamage(info.attackOutput);

        // 체력 감소
        victim.getLivingEntity().damage(finalDamage, info.attacker != null
                ? info.attacker.getLivingEntity() : null);

        // 주력 감소
        victim.cursedEnergy.forceConsume(info.attackOutput);

        return new DamageResult(false, finalDamage, info.attackOutput, info.isBlackFlash);
    }
}
