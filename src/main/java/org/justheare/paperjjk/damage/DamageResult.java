package org.justheare.paperjjk.damage;

/**
 * DamagePipeline.process() 의 결과값.
 */
public class DamageResult {

    public final boolean blocked;       // 완전 차단 (무한 등)
    public final double finalDamage;    // 최종 적용된 체력 데미지
    public final double outputConsumed; // 소모된 주력량
    public final boolean wasBlackFlash;

    public DamageResult(boolean blocked, double finalDamage,
                        double outputConsumed, boolean wasBlackFlash) {
        this.blocked = blocked;
        this.finalDamage = finalDamage;
        this.outputConsumed = outputConsumed;
        this.wasBlackFlash = wasBlackFlash;
    }

    public static DamageResult blocked() {
        return new DamageResult(true, 0, 0, false);
    }
}
