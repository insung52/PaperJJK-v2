package org.justheare.paperjjk.technique;

import org.bukkit.ChatColor;
import org.justheare.paperjjk.barrier.DomainExpansion;
import org.justheare.paperjjk.damage.DamageInfo;
import org.justheare.paperjjk.damage.DefenceResult;
import org.justheare.paperjjk.entity.JEntity;
import org.justheare.paperjjk.innate.InnateTerritory;
import org.justheare.paperjjk.innate.MizushiInnateTerritory;
import org.justheare.paperjjk.skill.SkillKey;
import org.justheare.paperjjk.skill.SkillSlot;

import java.util.Map;

/**
 * 어주자(Mizushi) 생득술식.
 *
 * 타격 시: 참격 추가 피해.
 * 스킬: 해(kai), 팔(hachi), 조(fuga)
 * 영역전개: 복마어주자
 */
public class MizushiTechnique extends Technique {

    public MizushiTechnique(JEntity owner) {
        super(owner);
    }

    // ── Technique 구현 ────────────────────────────────────────────────────

    @Override
    public void onAttack(JEntity target, DamageInfo damageInfo) {
        // 타격 시 참격 추가 피해
        // 기존 코드: 타격 시 kai 를 동시에 날리는 효과
        // → 별도 DamageInfo 생성해서 DamagePipeline 호출
        // 추후 구현
    }

    @Override
    public DefenceResult defend(DamageInfo incoming) {
        // 어주자는 별도 방어 메커니즘 없음
        return DefenceResult.notBlocked(0);
    }

    @Override
    public InnateTerritory createTerritory() {
        return new MizushiInnateTerritory(owner);
    }

    @Override
    public DomainExpansion createDomain() {
        InnateTerritory territory = owner instanceof org.justheare.paperjjk.entity.JPlayer jp
                ? jp.innateTerritory : null;
        if (territory == null) return null;
        // 복마어주자 (MizushiDomainExpansion) — 추후 구현
        return null;
    }

    @Override
    public Map<SkillKey, SkillSlot> getDefaultBindings() {
        return Map.of(
            SkillKey.X, new SkillSlot("mizushi_kai",    true,  false),
            SkillKey.C, new SkillSlot("mizushi_hachi",  true,  true),   // rechargeable
            SkillKey.V, new SkillSlot("mizushi_fuga",   true,  false),
            SkillKey.B, new SkillSlot("mizushi_domain", false, false)
        );
    }

    @Override
    public String getDisplayName() {
        return ChatColor.RED + "the Mizushi.";
    }

    @Override
    public String getKey() { return "mizushi"; }
}
