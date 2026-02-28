package org.justheare.paperjjk.technique;

import org.bukkit.ChatColor;
import org.justheare.paperjjk.barrier.DomainExpansion;
import org.justheare.paperjjk.damage.DamageInfo;
import org.justheare.paperjjk.damage.DefenceResult;
import org.justheare.paperjjk.entity.JEntity;
import org.justheare.paperjjk.innate.InnateTerritory;
import org.justheare.paperjjk.skill.SkillKey;
import org.justheare.paperjjk.skill.SkillSlot;

import java.util.Map;

/**
 * 천여주박 — 피지컬 기프티드.
 * 주력 = 0, 술식 없음.
 *
 * 특성:
 * - isDomainTarget() = false → 영역전개 필중 대상 아님
 * - 장막/결계 자유 통과 (BarrierArts.canEnter() 에서 처리)
 * - canBeBlocked 공격을 반사 (reflex)
 * - REVERSED_CURSED, CURSED 계열 공격 저항
 */
public class PhysicalGifted extends Technique {

    /** reflex 발동 여부 (주구 장착 시 활성화) */
    private boolean reflexEnabled = false;
    private double reflexInteractValue = 0;

    public PhysicalGifted(JEntity owner) {
        super(owner);
    }

    // ── Technique 구현 ────────────────────────────────────────────────────

    @Override
    public void onAttack(JEntity target, DamageInfo damageInfo) {
        // 피지컬 기프티드는 주술적 타격 시 효과 없음
    }

    @Override
    public DefenceResult defend(DamageInfo incoming) {
        // 주술 계열 공격에 저항 (부분 방어)
        if (incoming.type == org.justheare.paperjjk.damage.DamageType.REVERSED_CURSED
                || incoming.type == org.justheare.paperjjk.damage.DamageType.CURSED) {
            return DefenceResult.partialBlock(0.5); // 50% 감소
        }

        // reflex — canBeBlocked 공격 반사 (주구 장착 시)
        if (reflexEnabled && incoming.canBeBlocked && !incoming.sureHit) {
            reflexInteractValue += 1;
            return DefenceResult.fullyBlocked();
        }

        return DefenceResult.notBlocked(0);
    }

    @Override
    public boolean isDomainTarget() {
        // 영역전개 필중 대상 아님
        return false;
    }

    @Override
    public InnateTerritory createTerritory() { return null; }

    @Override
    public DomainExpansion createDomain() { return null; }

    @Override
    public Map<SkillKey, SkillSlot> getDefaultBindings() {
        return Map.of(
            SkillKey.X, new SkillSlot("physical_dash", true, false)
        );
    }

    @Override
    public String getDisplayName() {
        return ChatColor.LIGHT_PURPLE + "Physical Gifted.";
    }

    @Override
    public String getKey() { return "physical_gifted"; }

    // ── 조회 / 설정 ───────────────────────────────────────────────────────

    public void setReflexEnabled(boolean enabled) { this.reflexEnabled = enabled; }
    public boolean isReflexEnabled() { return reflexEnabled; }
    public double getReflexInteractValue() { return reflexInteractValue; }
    public void resetReflexInteract() { reflexInteractValue = 0; }
}
