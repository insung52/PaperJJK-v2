package org.justheare.paperjjk.skill;

import org.justheare.paperjjk.entity.JEntity;
import org.justheare.paperjjk.skill.mizushi.MizushiFuga;
import org.justheare.paperjjk.skill.mizushi.MizushiHachi;
import org.justheare.paperjjk.skill.mizushi.MizushiKai;

import javax.annotation.Nullable;

/**
 * skillId 문자열로 ActiveSkill 인스턴스를 생성하는 팩토리.
 * 새 스킬 추가 시 여기에 등록.
 */
public class SkillFactory {

    private SkillFactory() {}

    @Nullable
    public static ActiveSkill create(String skillId, JEntity caster) {
        return switch (skillId) {
            case "infinity_passive" -> new org.justheare.paperjjk.skill.infinity.InfinityPassive(caster);
            case "infinity_ao"      -> new org.justheare.paperjjk.skill.infinity.InfinityAo(caster);
            case "infinity_aka"     -> new org.justheare.paperjjk.skill.infinity.InfinityAka(caster);
            case "mizushi_kai"      -> new MizushiKai(caster);
            case "mizushi_hachi"    -> new MizushiHachi(caster);
            case "mizushi_fuga"     -> new MizushiFuga(caster);
            default -> null;
        };
    }
}
