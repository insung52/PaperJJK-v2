package org.justheare.paperjjk.damage;

/**
 * Technique.defend() 의 결과값.
 */
public class DefenceResult {

    public final boolean fullyBlocked;
    public final double reductionRatio;  // 0.0 ~ 1.0, 부분 방어 시 사용

    private DefenceResult(boolean fullyBlocked, double reductionRatio) {
        this.fullyBlocked = fullyBlocked;
        this.reductionRatio = reductionRatio;
    }

    public static DefenceResult fullyBlocked() {
        return new DefenceResult(true, 1.0);
    }

    public static DefenceResult notBlocked(double reductionRatio) {
        return new DefenceResult(false, reductionRatio);
    }

    public static DefenceResult partialBlock(double reductionRatio) {
        return new DefenceResult(false, Math.min(1.0, reductionRatio));
    }
}
