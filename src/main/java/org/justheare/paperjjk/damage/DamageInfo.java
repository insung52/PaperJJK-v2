package org.justheare.paperjjk.damage;

import org.justheare.paperjjk.entity.JEntity;

/**
 * 데미지 발생 시 필요한 모든 정보를 담는 컨테이너.
 * DamagePipeline.process() 에 전달되어 3단계를 거쳐 처리됨.
 */
public class DamageInfo {

    // ── 입력값 (생성 시 확정) ──────────────────────────────────────────────

    public final JEntity attacker;
    public final DamageType type;

    /**
     * 공격 주력 출력값.
     * 일반 데미지와의 변환: attackOutput = damage * 10
     */
    public double attackOutput;

    /**
     * 스킬 식별자. 일반 타격은 빈 문자열.
     * ex) "infinity_ao", "mizushi_kai"
     */
    public final String skillId;

    /** 영역전개 필중 등 — true면 무한/피지컬 기프티드 방어 불가 */
    public final boolean sureHit;

    /** false면 무한, 피지컬 기프티드 등이 차단/반사 가능 */
    public final boolean canBeBlocked;

    // ── Pipeline 진행 중 채워지는 값 ──────────────────────────────────────

    /** DamagePipeline Phase 1에서 흑섬 판정 후 세팅 */
    public boolean isBlackFlash = false;

    // ── 생성자 ────────────────────────────────────────────────────────────

    public DamageInfo(JEntity attacker, DamageType type, double attackOutput,
                      String skillId, boolean sureHit, boolean canBeBlocked) {
        this.attacker = attacker;
        this.type = type;
        this.attackOutput = attackOutput;
        this.skillId = skillId;
        this.sureHit = sureHit;
        this.canBeBlocked = canBeBlocked;
    }

    /** 일반 물리 타격용 편의 생성자 */
    public static DamageInfo physicalHit(JEntity attacker, double attackOutput) {
        return new DamageInfo(attacker, DamageType.PHYSICAL, attackOutput, "", false, true);
    }

    /** 술식 공격용 편의 생성자 */
    public static DamageInfo skillHit(JEntity attacker, DamageType type,
                                      double attackOutput, String skillId) {
        return new DamageInfo(attacker, type, attackOutput, skillId, false, true);
    }

    /** 영역전개 필중용 편의 생성자 */
    public static DamageInfo domainSureHit(JEntity attacker, double attackOutput, String skillId) {
        return new DamageInfo(attacker, DamageType.DOMAIN_SURE_HIT, attackOutput, skillId, true, false);
    }

    /** damage(체력) → attackOutput(주력) 변환 */
    public static double damageToOutput(double damage) { return damage * 10.0; }

    /** attackOutput(주력) → damage(체력) 변환 */
    public static double outputToDamage(double attackOutput) { return attackOutput / 10.0; }
}
