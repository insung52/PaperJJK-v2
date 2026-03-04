package org.justheare.paperjjk;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.justheare.paperjjk.entity.JPlayer;
import org.justheare.paperjjk.network.JEntityManager;
import org.justheare.paperjjk.technique.MahoragaTechnique;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 플레이어 데이터 저장/로드 (playerdata.yml).
 *
 * 흐름:
 *   서버 시작 → loadAllData() → pendingData에 보관
 *   플레이어 접속 → JEvent가 consumePending() → JPlayer에 적용
 *   플레이어 퇴장 → save(JPlayer) → yml에 기록
 */
public class JData {

    // ── 내부 저장용 DTO ───────────────────────────────────────────────────

    public static class PlayerSaveData {
        public String techniqueName = "";
        public double maxCE = 200.0;
        public double currentCE = 200.0;
        public boolean canReverseOutput = false;
        public int blackFlashLifeTimeCount = 0;
        /** -1 = 술식 기본값 사용 (setTechnique 에 위임) */
        public int efficiencyLevel = -1;
        public boolean canGraspAirSurface = false;
        public Map<String, Double> mahoragaAdaptMap = null; // null = 없음
        /** 일반 영역전개 반경 (5~50, 기본 30) */
        public int normalDomainRange    = 30;
        /** 결없영 반경 (5~200, 기본 200) */
        public int noBarrierDomainRange = 200;
        /** 생득 영역 중심 월드 이름 (null = 설정 안됨) */
        public String innateWorld = null;
        public double innateX, innateY, innateZ;
        /** 생득 영역 원본 블록 스냅샷 (null or empty = 없음) */
        public List<String> innateSnapshots = null;
    }

    // ── 상태 ──────────────────────────────────────────────────────────────

    private static File dataFile;
    private static FileConfiguration dataConfig;

    /** 서버 시작 시 로드된 데이터 보관 — 플레이어 접속 시 소비 */
    private static final Map<UUID, PlayerSaveData> pendingData = new HashMap<>();

    // ── 초기화 ────────────────────────────────────────────────────────────

    public static void init(File dataFolder) {
        if (!dataFolder.exists()) dataFolder.mkdirs();
        dataFile = new File(dataFolder, "playerdata.yml");
        if (!dataFile.exists()) {
            try { dataFile.createNewFile(); } catch (IOException e) {
                PaperJJK.log("[JData] Failed to create playerdata.yml: " + e.getMessage());
            }
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
        loadAllData();
    }

    // ── 로드 ──────────────────────────────────────────────────────────────

    private static void loadAllData() {
        if (!dataConfig.contains("players")) return;
        var section = dataConfig.getConfigurationSection("players");
        if (section == null) return;

        int count = 0;
        for (String uuidStr : section.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(uuidStr);
                pendingData.put(uuid, loadPlayerData(uuidStr));
                count++;
            } catch (Exception e) {
                PaperJJK.log("[JData] Failed to load " + uuidStr + ": " + e.getMessage());
            }
        }
        PaperJJK.log("[JData] Loaded " + count + " player(s) into pending.");
    }

    private static PlayerSaveData loadPlayerData(String uuidStr) {
        String p = "players." + uuidStr;
        PlayerSaveData d = new PlayerSaveData();
        d.techniqueName = dataConfig.getString(p + ".technique", "");
        d.maxCE = dataConfig.getDouble(p + ".maxCE", 200.0);
        d.currentCE = dataConfig.getDouble(p + ".currentCE", d.maxCE);
        d.canReverseOutput = dataConfig.getBoolean(p + ".canReverseOutput", false);
        d.blackFlashLifeTimeCount = dataConfig.getInt(p + ".blackFlashLifeTimeCount", 0);
        d.efficiencyLevel = dataConfig.getInt(p + ".efficiencyLevel", -1);
        d.canGraspAirSurface = dataConfig.getBoolean(p + ".canGraspAirSurface", false);
        d.normalDomainRange    = dataConfig.getInt(p + ".normalDomainRange",    30);
        d.noBarrierDomainRange = dataConfig.getInt(p + ".noBarrierDomainRange", 200);

        // 생득 영역 데이터
        if (dataConfig.contains(p + ".innate.world")) {
            d.innateWorld = dataConfig.getString(p + ".innate.world");
            d.innateX     = dataConfig.getDouble(p + ".innate.x");
            d.innateY     = dataConfig.getDouble(p + ".innate.y");
            d.innateZ     = dataConfig.getDouble(p + ".innate.z");
            List<String> snaps = dataConfig.getStringList(p + ".innate.snapshots");
            d.innateSnapshots = snaps.isEmpty() ? null : snaps;
        }

        // Mahoraga 적응 데이터
        String mahoPath = p + ".mahoraga";
        if (dataConfig.contains(mahoPath)) {
            d.mahoragaAdaptMap = new HashMap<>();
            var mahoSection = dataConfig.getConfigurationSection(mahoPath);
            if (mahoSection != null) {
                for (String key : mahoSection.getKeys(false)) {
                    d.mahoragaAdaptMap.put(key, dataConfig.getDouble(mahoPath + "." + key));
                }
            }
        }
        return d;
    }

    /**
     * 플레이어 접속 시 호출 — pending 데이터를 꺼내고 맵에서 제거.
     * null 반환 시 신규 플레이어.
     */
    public static PlayerSaveData consumePending(UUID uuid) {
        return pendingData.remove(uuid);
    }

    // ── 저장 ──────────────────────────────────────────────────────────────

    public static void save(JPlayer jp) {
        String p = "players." + jp.uuid.toString();
        String techName = jp.technique != null ? jp.technique.getClass().getSimpleName()
                .replace("Technique", "").replace("Gifted", "_gifted")
                .toLowerCase() : "";
        // getSimpleName() → "InfinityTechnique" → "infinity", "PhysicalGifted" → "physical_gifted"
        techName = toTechniqueId(jp);

        dataConfig.set(p + ".technique", techName);
        dataConfig.set(p + ".maxCE", jp.cursedEnergy.getMax());
        dataConfig.set(p + ".currentCE", jp.cursedEnergy.getCurrent());
        dataConfig.set(p + ".canReverseOutput", jp.reverseOutput != null);
        dataConfig.set(p + ".blackFlashLifeTimeCount", jp.blackFlash.getLifeTimeCount());
        dataConfig.set(p + ".efficiencyLevel", jp.cursedEnergy.getEfficiencyLevel());
        dataConfig.set(p + ".canGraspAirSurface", jp.canGraspAirSurface);
        dataConfig.set(p + ".normalDomainRange",    jp.normalDomainRange);
        dataConfig.set(p + ".noBarrierDomainRange", jp.noBarrierDomainRange);

        // 생득 영역 데이터
        if (jp.innateTerritory != null && jp.innateTerritory.isReady()) {
            var loc = jp.innateTerritory.getCenterLocation();
            if (loc != null && loc.getWorld() != null) {
                dataConfig.set(p + ".innate.world", loc.getWorld().getName());
                dataConfig.set(p + ".innate.x", loc.getX());
                dataConfig.set(p + ".innate.y", loc.getY());
                dataConfig.set(p + ".innate.z", loc.getZ());
                var builder = jp.innateTerritory.getInnateBuilder();
                dataConfig.set(p + ".innate.snapshots",
                        builder != null ? builder.serializeSnapshots() : new ArrayList<String>());
            }
        } else {
            dataConfig.set(p + ".innate", null);
        }

        // Mahoraga 적응 데이터
        if (jp.technique instanceof MahoragaTechnique mt) {
            String mahoPath = p + ".mahoraga";
            dataConfig.set(mahoPath, null); // 초기화
            for (Map.Entry<String, Double> entry : mt.getAdaptationMap().entrySet()) {
                dataConfig.set(mahoPath + "." + entry.getKey(), entry.getValue());
            }
        }

        flush();
    }

    /** 서버 종료 시 온라인 플레이어 전체 저장 */
    public static void saveAll() {
        if (JEntityManager.instance == null) return;
        int count = 0;
        for (var entity : JEntityManager.instance.all()) {
            if (entity instanceof JPlayer jp) {
                save(jp);
                count++;
            }
        }
        PaperJJK.log("[JData] Saved " + count + " player(s).");
    }

    private static void flush() {
        try {
            dataConfig.save(dataFile);
        } catch (IOException e) {
            PaperJJK.log("[JData] Save failed: " + e.getMessage());
        }
    }

    // ── 유틸 ──────────────────────────────────────────────────────────────

    public static String toTechniqueIdPublic(JPlayer jp) { return toTechniqueId(jp); }

    private static String toTechniqueId(JPlayer jp) {
        if (jp.technique == null) return "";
        return switch (jp.technique.getClass().getSimpleName()) {
            case "InfinityTechnique"  -> "infinity";
            case "MizushiTechnique"   -> "mizushi";
            case "PhysicalGifted"     -> "physical_gifted";
            case "MahoragaTechnique"  -> "mahoraga";
            default -> jp.technique.getClass().getSimpleName().toLowerCase();
        };
    }
}
