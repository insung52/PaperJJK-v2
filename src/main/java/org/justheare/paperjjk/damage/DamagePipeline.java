package org.justheare.paperjjk.damage;

import org.justheare.paperjjk.PaperJJK;
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
        if (defence.fullyBlocked) {
            org.justheare.paperjjk.PaperJJK.logDamage("[DBG-PIPE] BLOCKED by phase2 (technique.defend)"
                    + " victim.tech=" + (victim.technique != null ? victim.technique.getKey() : "none")
                    + " sureHit=" + info.sureHit + " canBeBlocked=" + info.canBeBlocked);
            return DamageResult.blocked();
        }

        // Phase 3
        org.justheare.paperjjk.PaperJJK.logDamage("[DBG-PIPE] phase2 passed, attackOutput="
                + info.attackOutput + " victim.tech=" + (victim.technique != null ? victim.technique.getKey() : "none"));
        DamageResult result = applyFinalDamage(info, defence, victim);

        // 물리 타격 성공 시 공격자 술식 passive 호출 (타격 시 효과)
        if (!result.blocked && info.type == DamageType.PHYSICAL
                && info.attacker != null && info.attacker.technique != null) {
            info.attacker.technique.onAttack(victim, info);
        }

        return result;
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

        // 신체강화 방어 (PHYSICAL 계열 + 신체강화 활성화 시)
        // absorbed = min(공격주력, 신체강화주력) → 신체강화가 실제로 흡수한 양만큼 소모
        // remaining = max(0, 공격주력 - 신체강화주력) → 통과한 피해
        if (info.type == DamageType.PHYSICAL && victim.bodyReinforcement.isActive()) {
            double bodyReinCurrent = victim.bodyReinforcement.getCurrent();
            double absorbed  = Math.min(info.attackOutput, bodyReinCurrent);
            double remaining = Math.max(0, info.attackOutput - bodyReinCurrent);
            victim.bodyReinforcement.consume(absorbed); // 흡수한 만큼 신체강화 소모
            PaperJJK.logDamage(absorbed+" : "+ bodyReinCurrent+" : " + remaining);
            info.attackOutput = remaining;
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

        // NMS i-frame 체크 통과를 위해 current만 0으로 리셋
        int beforeTick = victim.getLivingEntity().getNoDamageTicks();
        int beforeMax  = victim.getLivingEntity().getMaximumNoDamageTicks();
        victim.getLivingEntity().setNoDamageTicks(0);
        org.justheare.paperjjk.PaperJJK.logDamage("[DBG-PIPE] applyFinalDamage"
                + " dmg=" + finalDamage
                + " noDmgTicks(before)=" + beforeTick + "/" + beforeMax
                + " suppress=true, calling entity.damage()");
        victim.suppressDamageEvent = true;
        victim.getLivingEntity().damage(finalDamage, info.attacker != null
                ? info.attacker.getLivingEntity() : null);
        // 이벤트가 발생하지 않은 경우를 대비한 safety net
        boolean wasStillSet = victim.suppressDamageEvent;
        victim.suppressDamageEvent = false;
        org.justheare.paperjjk.PaperJJK.logDamage("[DBG-PIPE] after entity.damage()"
                + " suppressWasStillTrue=" + wasStillSet
                + " noDmgTicks(after)=" + victim.getLivingEntity().getNoDamageTicks());

        // 주력 감소 — 피해로 인한 CE 감소는 효율 미적용 (rawForceConsume)
        victim.cursedEnergy.rawForceConsume(info.attackOutput);

        return new DamageResult(false, finalDamage, info.attackOutput, info.isBlackFlash);
    }
}
