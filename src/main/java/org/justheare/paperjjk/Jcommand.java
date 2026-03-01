package org.justheare.paperjjk;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.justheare.paperjjk.entity.JPlayer;
import org.justheare.paperjjk.network.JEntityManager;
import org.justheare.paperjjk.network.JPacketSender;
import org.justheare.paperjjk.technique.Technique;
import org.justheare.paperjjk.technique.TechniqueFactory;

import java.util.List;

/**
 * /jjk 커맨드 핸들러.
 *
 * 사용법 (OP 전용):
 *   /jjk basic <tech> [maxCE]   — 술식 부여 및 주력 설정
 *   /jjk refill                 — 주력 최대치로 보충
 *   /jjk save                   — 데이터 즉시 저장
 *   /jjk id build               — 생득 영역 위치 확정
 *   /jjk id destroy             — 생득 영역 위치 초기화
 */
public class Jcommand implements TabExecutor {

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("플레이어만 사용 가능합니다.");
            return true;
        }
        if (!player.isOp()) {
            player.sendMessage(Component.text("권한이 없습니다.", NamedTextColor.RED));
            return true;
        }
        if (args.length == 0) {
            sendHelp(player);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "basic"  -> handleBasic(player, args);
            case "refill" -> handleRefill(player);
            case "save"   -> handleSave(player);
            case "id"     -> handleId(player, args);
            case "set"    -> handleSet(player, args);
            default       -> sendHelp(player);
        }
        return true;
    }

    // ── /jjk basic <tech> [maxCE] ─────────────────────────────────────────

    private void handleBasic(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(Component.text(
                    "사용법: /jjk basic <infinity|mizushi|physical_gifted|mahoraga> [maxCE]",
                    NamedTextColor.YELLOW));
            return;
        }

        String techName = args[1].toLowerCase();
        double maxCE = TechniqueFactory.defaultMaxCE(techName);

        // 선택 플래그 파싱 (순서 무관)
        boolean sixEyes = false;
        for (int i = 2; i < args.length; i++) {
            if (args[i].equalsIgnoreCase("sixeyes") || args[i].equalsIgnoreCase("6eyes")) {
                sixEyes = true;
            } else {
                try {
                    maxCE = Double.parseDouble(args[i]);
                } catch (NumberFormatException e) {
                    player.sendMessage(Component.text(
                            "알 수 없는 인수: " + args[i], NamedTextColor.RED));
                }
            }
        }

        boolean canRCT = TechniqueFactory.defaultCanRCT(techName);
        final boolean finalSixEyes = sixEyes;

        // 기존 JPlayer 가져오기
        JPlayer jp = getJPlayer(player);
        if (jp == null) {
            player.sendMessage(Component.text("JPlayer 데이터가 없습니다. 재접속 해주세요.", NamedTextColor.RED));
            return;
        }

        // 실행 중인 스킬 종료
        for (var skill : jp.getActiveSkills()) {
            skill.end();
        }

        // canRCT 가 현재 JPlayer와 다를 경우 → 재생성 필요
        boolean currentCanRCT = jp.reverseOutput != null;
        if (currentCanRCT != canRCT || jp.cursedEnergy.getMax() != maxCE) {
            // JPlayer 재생성 (reverseOutput은 final이라 재생성 필요)
            JPlayer newJp = new JPlayer(player, maxCE, canRCT);
            newJp.cursedEnergy.fill();
            newJp.blackFlash.setLifeTimeCount(jp.blackFlash.getLifeTimeCount()); // 흑섬 기록 유지

            Technique tech = TechniqueFactory.create(techName, newJp, finalSixEyes);
            if (tech == null) {
                player.sendMessage(Component.text("알 수 없는 술식: " + techName, NamedTextColor.RED));
                return;
            }
            newJp.setTechnique(tech);

            JEntityManager.instance.unregister(player.getUniqueId());
            JEntityManager.instance.register(newJp);

            player.setAllowFlight(maxCE > 1000);
            player.sendMessage(Component.text(
                    "술식 설정 완료 (재생성): " + techName + " CE=" + (long) maxCE, NamedTextColor.GREEN));
            JPacketSender.sendPlayerInfoResponse(player, newJp);
            JPacketSender.sendCEUpdate(player, newJp);
        } else {
            // CE만 바꾸고 술식만 교체
            jp.cursedEnergy.setMax(maxCE);
            jp.cursedEnergy.fill();

            Technique tech = TechniqueFactory.create(techName, jp, finalSixEyes);
            if (tech == null) {
                player.sendMessage(Component.text("알 수 없는 술식: " + techName, NamedTextColor.RED));
                return;
            }
            jp.forceTechnique(tech);

            player.setAllowFlight(maxCE > 1000);
            player.sendMessage(Component.text(
                    "술식 설정 완료: " + techName + " CE=" + (long) maxCE, NamedTextColor.GREEN));
            JPacketSender.sendPlayerInfoResponse(player, jp);
            JPacketSender.sendCEUpdate(player, jp);
        }
    }

    // ── /jjk set <var> <value> ────────────────────────────────────────────
    // 지원 변수:
    //   maxce <double>       — 최대 주력량
    //   currentce <double>   — 현재 주력량
    //   efficiency <0-100>   — 주력 효율 레벨
    //   rct <true|false>     — 반전술식 사용 가능 여부
    //   airgrasp <true|false>— 공기의 면 포착 여부
    //   domainlevel <int>    — 결계술 레벨 (추후 구현, 현재는 저장만)

    private static final List<String> SET_VARS = List.of(
            "maxce", "currentce", "efficiency", "rct", "airgrasp", "domainlevel");

    private void handleSet(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(Component.text(
                    "사용법: /jjk set <변수> <값>", NamedTextColor.YELLOW));
            player.sendMessage("§7변수 목록: " + String.join(", ", SET_VARS));
            return;
        }

        JPlayer jp = getJPlayer(player);
        if (jp == null) { noData(player); return; }

        String var = args[1].toLowerCase();
        String rawVal = args[2];

        switch (var) {
            case "maxce" -> {
                double val = parseDouble(player, rawVal); if (val < 0) return;
                jp.cursedEnergy.setMax(val);
                jp.updateMaxHealth();
                jp.syncBodyReinMax();
                player.sendMessage(Component.text(
                        "최대 주력량 → " + (long) val, NamedTextColor.AQUA));
                JPacketSender.sendCEUpdate(player, jp);
            }
            case "currentce" -> {
                double val = parseDouble(player, rawVal); if (val < 0) return;
                jp.cursedEnergy.setCurrent(val);
                player.sendMessage(Component.text(
                        "현재 주력량 → " + (long) val, NamedTextColor.AQUA));
                JPacketSender.sendCEUpdate(player, jp);
            }
            case "efficiency" -> {
                int val = parseInt(player, rawVal, 0, 100); if (val < 0) return;
                jp.cursedEnergy.setEfficiencyLevel(val);
                player.sendMessage(Component.text(
                        "주력 효율 레벨 → " + val
                        + String.format(" (소모 %.1f%%)", 100.0 - val * 0.99),
                        NamedTextColor.AQUA));
                JPacketSender.sendPlayerInfoResponse(player, jp);
            }
            case "rct" -> {
                boolean val = parseBoolean(player, rawVal);
                if (val == (jp.reverseOutput != null)) {
                    player.sendMessage(Component.text(
                            "이미 rct=" + val + " 상태입니다.", NamedTextColor.YELLOW));
                    return;
                }
                // reverseOutput 이 final 이므로 JPlayer 재생성
                JPlayer newJp = new JPlayer(player, jp.cursedEnergy.getMax(), val);
                newJp.cursedEnergy.setCurrent(jp.cursedEnergy.getCurrent());
                newJp.cursedEnergy.setEfficiencyLevel(jp.cursedEnergy.getEfficiencyLevel());
                newJp.blackFlash.setLifeTimeCount(jp.blackFlash.getLifeTimeCount());
                newJp.canGraspAirSurface = jp.canGraspAirSurface;
                if (jp.technique != null) {
                    var tech = TechniqueFactory.create(
                            org.justheare.paperjjk.JData.toTechniqueIdPublic(jp), newJp);
                    if (tech != null) newJp.forceTechnique(tech);
                }
                JEntityManager.instance.unregister(player.getUniqueId());
                JEntityManager.instance.register(newJp);
                player.sendMessage(Component.text(
                        "반전술식 → " + val, NamedTextColor.AQUA));
                JPacketSender.sendPlayerInfoResponse(player, newJp);
                JPacketSender.sendCEUpdate(player, newJp);
            }
            case "airgrasp" -> {
                boolean val = parseBoolean(player, rawVal);
                jp.canGraspAirSurface = val;
                player.sendMessage(Component.text(
                        "공기의 면 포착 → " + val, NamedTextColor.AQUA));
            }
            case "domainlevel" -> {
                int val = parseInt(player, rawVal, 0, 100); if (val < 0) return;
                // 결계술 레벨은 추후 구현 — 현재는 안내만
                player.sendMessage(Component.text(
                        "결계술 레벨 설정은 아직 구현되지 않았습니다.", NamedTextColor.RED));
            }
            default -> {
                player.sendMessage(Component.text(
                        "알 수 없는 변수: " + var, NamedTextColor.RED));
                player.sendMessage("§7변수 목록: " + String.join(", ", SET_VARS));
            }
        }
    }

    // ── 파싱 유틸 ─────────────────────────────────────────────────────────

    /** 실패 시 에러 메시지 출력 후 -1 반환 */
    private double parseDouble(Player player, String s) {
        try {
            double v = Double.parseDouble(s);
            if (v < 0) { player.sendMessage(Component.text("값은 0 이상이어야 합니다.", NamedTextColor.RED)); return -1; }
            return v;
        } catch (NumberFormatException e) {
            player.sendMessage(Component.text("숫자를 입력해주세요: " + s, NamedTextColor.RED));
            return -1;
        }
    }

    /** 실패 시 에러 메시지 출력 후 -1 반환 */
    private int parseInt(Player player, String s, int min, int max) {
        try {
            int v = Integer.parseInt(s);
            if (v < min || v > max) {
                player.sendMessage(Component.text(min + "~" + max + " 범위로 입력해주세요.", NamedTextColor.RED));
                return -1;
            }
            return v;
        } catch (NumberFormatException e) {
            player.sendMessage(Component.text("정수를 입력해주세요: " + s, NamedTextColor.RED));
            return -1;
        }
    }

    private boolean parseBoolean(Player player, String s) {
        return s.equalsIgnoreCase("true") || s.equals("1");
    }

    // ── /jjk refill ───────────────────────────────────────────────────────

    private void handleRefill(Player player) {
        JPlayer jp = getJPlayer(player);
        if (jp == null) { noData(player); return; }
        jp.cursedEnergy.fill();
        player.sendMessage(Component.text("주력 최대치로 보충했습니다.", NamedTextColor.AQUA));
    }

    // ── /jjk save ─────────────────────────────────────────────────────────

    private void handleSave(Player player) {
        JData.saveAll();
        player.sendMessage(Component.text("전체 플레이어 데이터를 저장했습니다.", NamedTextColor.GREEN));
    }

    // ── /jjk id <build|destroy> ───────────────────────────────────────────

    private void handleId(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(Component.text("사용법: /jjk id <build|destroy>", NamedTextColor.YELLOW));
            return;
        }

        JPlayer jp = getJPlayer(player);
        if (jp == null) { noData(player); return; }

        if (jp.technique == null) {
            player.sendMessage(Component.text("술식이 없습니다. /jjk basic 먼저 설정해주세요.", NamedTextColor.RED));
            return;
        }

        switch (args[1].toLowerCase()) {
            case "build" -> {
                // 아직 생득 영역이 없으면 생성
                if (jp.innateTerritory == null) {
                    jp.innateTerritory = jp.technique.createTerritory();
                }
                if (jp.innateTerritory == null) {
                    player.sendMessage(Component.text("이 술식은 생득 영역이 없습니다.", NamedTextColor.RED));
                    return;
                }
                jp.innateTerritory.setLocation(player.getLocation());
                player.sendMessage(Component.text(
                        "생득 영역 위치 확정: " + formatLoc(player.getLocation()), NamedTextColor.GREEN));
            }
            case "destroy" -> {
                if (jp.innateTerritory == null) {
                    player.sendMessage(Component.text("설정된 생득 영역이 없습니다.", NamedTextColor.RED));
                    return;
                }
                jp.innateTerritory = null;
                player.sendMessage(Component.text("생득 영역을 초기화했습니다.", NamedTextColor.YELLOW));
            }
            default -> player.sendMessage(Component.text(
                    "사용법: /jjk id <build|destroy>", NamedTextColor.YELLOW));
        }
    }

    // ── 유틸 ──────────────────────────────────────────────────────────────

    private JPlayer getJPlayer(Player player) {
        return JEntityManager.instance != null
                ? JEntityManager.instance.getPlayer(player.getUniqueId())
                : null;
    }

    private void noData(Player player) {
        player.sendMessage(Component.text("JPlayer 데이터가 없습니다.", NamedTextColor.RED));
    }

    private void sendHelp(Player player) {
        player.sendMessage(Component.text("[JJK] 커맨드 목록:", NamedTextColor.GOLD));
        player.sendMessage("§e/jjk basic <tech> [maxCE] §7— 술식 부여");
        player.sendMessage("§e/jjk refill §7— 주력 충전");
        player.sendMessage("§e/jjk save §7— 데이터 저장");
        player.sendMessage("§e/jjk id build §7— 생득 영역 위치 확정");
        player.sendMessage("§e/jjk id destroy §7— 생득 영역 초기화");
        player.sendMessage("§e/jjk set <변수> <값> §7— 수치 변경");
        player.sendMessage("§7  변수: maxce, currentce, efficiency, rct, airgrasp, domainlevel");
    }

    private String formatLoc(org.bukkit.Location loc) {
        return String.format("(%.0f, %.0f, %.0f)", loc.getX(), loc.getY(), loc.getZ());
    }

    @Override
    @Nullable
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("basic", "refill", "save", "id", "set");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("basic")) {
            return List.of("infinity", "mizushi", "physical_gifted", "mahoraga");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("id")) {
            return List.of("build", "destroy");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("set")) {
            return SET_VARS;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("set")) {
            return switch (args[1].toLowerCase()) {
                case "rct", "airgrasp" -> List.of("true", "false");
                case "efficiency"      -> List.of("0", "15", "30", "50", "60", "90", "100");
                case "maxce"           -> List.of("200", "5000000", "50000000", "400000000");
                default                -> List.of();
            };
        }
        if(args.length == 3){
            if(args[0].equalsIgnoreCase("basic")){
                return List.of(TechniqueFactory.defaultMaxCE(args[1])+"");
            }
        }
        if(args.length == 4){
            if(args[0].equalsIgnoreCase("basic")){
                if(args[1].equalsIgnoreCase("infinity")){
                    return List.of("true","false");
                }
            }
        }
        return List.of();
    }
}
