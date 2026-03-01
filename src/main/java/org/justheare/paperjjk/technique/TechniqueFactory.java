package org.justheare.paperjjk.technique;

import org.justheare.paperjjk.entity.JEntity;

import javax.annotation.Nullable;

/**
 * 술식 이름 문자열 → Technique 인스턴스 생성 팩토리.
 * JData 로드 및 /jjk basic 커맨드에서 사용.
 */
public class TechniqueFactory {

    private TechniqueFactory() {}

    @Nullable
    public static Technique create(String name, JEntity entity) {
        return create(name, entity, false);
    }

    /**
     * @param sixEyes InfinityTechnique 전용 — 육안 보유 여부
     */
    @Nullable
    public static Technique create(String name, JEntity entity, boolean sixEyes) {
        return switch (name.toLowerCase()) {
            case "infinity"        -> new InfinityTechnique(entity, sixEyes);
            case "mizushi"         -> new MizushiTechnique(entity);
            case "physical_gifted" -> new PhysicalGifted(entity);
            case "mahoraga"        -> new MahoragaTechnique(entity);
            default                -> null;
        };
    }

    /** 술식에 따른 기본 최대 주력량 */
    public static double defaultMaxCE(String name) {
        return switch (name.toLowerCase()) {
            case "infinity"        -> 50_000_000.0;
            case "mizushi"         -> 400_000_000.0;
            case "physical_gifted" -> 0.0;
            case "mahoraga"        -> 5_000_000.0;
            default                -> 200.0;
        };
    }

    /** 술식에 따른 반전술식 사용 가능 여부 */
    public static boolean defaultCanRCT(String name) {
        return switch (name.toLowerCase()) {
            case "infinity", "mizushi" -> true;
            default -> false;
        };
    }

    /**
     * 술식에 따른 기본 주력 효율 레벨 (0~100).
     * 레벨 0: 명목 소모 100%, 레벨 100: 명목 소모 1%.
     * 문서 기준: 육안=100, mizushi=90, 1급=50, 2급=30, 3급=15.
     */
    public static int defaultEfficiencyLevel(String name) {
        return switch (name.toLowerCase()) {
            case "infinity"        -> 100;
            case "mizushi"         -> 90;
            case "mahoraga"        -> 60;
            case "physical_gifted" -> 0;
            default                -> 15;
        };
    }
}
