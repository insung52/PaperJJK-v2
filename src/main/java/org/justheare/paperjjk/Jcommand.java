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
        player.sendMessage(Component.text("§6[JJK] 커맨드 목록:", NamedTextColor.GOLD));
        player.sendMessage("§e/jjk basic <tech> [maxCE] §7— 술식 부여");
        player.sendMessage("§e/jjk refill §7— 주력 충전");
        player.sendMessage("§e/jjk save §7— 데이터 저장");
        player.sendMessage("§e/jjk id build §7— 생득 영역 위치 확정");
        player.sendMessage("§e/jjk id destroy §7— 생득 영역 초기화");
    }

    private String formatLoc(org.bukkit.Location loc) {
        return String.format("(%.0f, %.0f, %.0f)", loc.getX(), loc.getY(), loc.getZ());
    }

    @Override
    @Nullable
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("basic", "refill", "save", "id");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("basic")) {
            return List.of("infinity", "mizushi", "physical_gifted", "mahoraga");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("id")) {
            return List.of("build", "destroy");
        }
        return List.of();
    }
}
