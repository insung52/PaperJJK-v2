package org.justheare.paperjjk.innate;

import org.justheare.paperjjk.entity.JEntity;

/**
 * 복마어주자(Malevolent Shrine) 생득 영역.
 * 주력 있는 대상 → '팔' 필중.
 * 주력 없는 대상(isOpen=true 시) → '해' 필중.
 */
public class MizushiInnateTerritory extends InnateTerritory {

    public MizushiInnateTerritory(JEntity owner) {
        super(owner);
    }

    @Override
    public void onActiveTick() {
        for (JEntity target : capturedEntities) {
            if (target == owner) {
                applyCasterBuff(owner);
            } else {
                applySureHit(target);
            }
        }
    }

    @Override
    public void applyCasterBuff(JEntity caster) {
        // 복마어주자 내부: 시전자 강화 효과
        // 추후 구체적 수치 설계 후 적용
    }

    @Override
    public void applySureHit(JEntity target) {
        if (target == owner) return;

        boolean hasCursedEnergy = target.cursedEnergy.getMax() > 0;

        if (hasCursedEnergy) {
            // '팔' 필중 적용 — 별도 DamageInfo 생성 후 DamagePipeline 호출
            applyHachi(target);
        } else {
            // '해' 필중 적용 (결계 없는 영역전개 시에만)
            applyKai(target);
        }
    }

    private void applyHachi(JEntity target) {
        // 기존 Mizushi.hachi 로직 이식 예정
    }

    private void applyKai(JEntity target) {
        // 기존 Mizushi.kai 로직 이식 예정
    }
}
