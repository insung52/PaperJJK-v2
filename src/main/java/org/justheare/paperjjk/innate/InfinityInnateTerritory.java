package org.justheare.paperjjk.innate;

import org.justheare.paperjjk.entity.JEntity;
import org.justheare.paperjjk.status.StatusEffectType;
import org.justheare.paperjjk.status.TimedStatusEffect;

/**
 * 무량공처(Unlimited Void) 생득 영역.
 * 대상자에게 정보 과부하 → 이동/상호작용 불가.
 * 대상 수준에 따라 지속 시간 차이.
 */
public class InfinityInnateTerritory extends InnateTerritory {

    // 수준별 INFORMATION_OVERLOAD 지속 틱
    private static final int OVERLOAD_TICKS_DEFAULT = 20 * 200;  // 일반인: 200초
    private static final int OVERLOAD_TICKS_SORCERER = 20 * 30;  // 주술사: 30초
    private static final int OVERLOAD_TICKS_STRONG = 20 * 5;     // 강자(스쿠나 등): 5초

    public InfinityInnateTerritory(JEntity owner) {
        super(owner);
    }

    @Override
    public void onActiveTick() {
        for (JEntity target : capturedEntities) {
            applySureHit(target);
            if (target == owner) applyCasterBuff(owner);
        }
    }

    @Override
    public void applyCasterBuff(JEntity caster) {
        // 무량공처 내부: 시전자 무한 강화, 출력 상승
        // 실제 수치 적용은 CursedEnergy/BodyReinforcement 에서 배율로 처리
    }

    @Override
    public void applySureHit(JEntity target) {
        if (target == owner) return;
        if (target.status.isFullyStunned()) return; // 이미 적용됨

        // 대상 수준에 따라 지속 시간 결정
        int ticks = getOverloadTicks(target);
        target.status.add(new TimedStatusEffect(StatusEffectType.INFORMATION_OVERLOAD, ticks));
    }

    private int getOverloadTicks(JEntity target) {
        double maxEnergy = target.cursedEnergy.getMax();
        if (maxEnergy >= 1_000_000) return OVERLOAD_TICKS_STRONG;    // 1급 이상
        if (maxEnergy >= 400) return OVERLOAD_TICKS_SORCERER;         // 3급 이상
        return OVERLOAD_TICKS_DEFAULT;
    }
}
