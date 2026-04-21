# Crafty Controller 설정 가이드

## Crafty란?

브라우저 기반 마인크래프트 서버 관리 패널.
터미널 명령어 없이 웹 UI에서 모든 것을 관리할 수 있음.

| 기능 | screen 방식 | Crafty |
|---|---|---|
| 서버 시작/정지 | 터미널 명령어 | 버튼 클릭 |
| 콘솔 | `screen -r` | 브라우저 |
| 로그 | `tail -f` | 브라우저 탭 |
| RAM/CPU 그래프 | btop 별도 설치 | 브라우저 내장 |
| 파일 관리 | Finder/터미널 | 브라우저 내장 |

---

## Phase 1 — Docker Desktop 설치

1. https://www.docker.com/products/docker-desktop/ 접속
2. **Mac Apple Silicon** 버전 다운로드
3. 설치 후 Docker Desktop 실행 (상단 바에 고래 아이콘 뜨면 정상)

---

## Phase 2 — Crafty 실행

Crafty 컨테이너 내부는 Java 25(Ubuntu 24.04)만 있어서 Paper와 충돌함.
맥북의 Java 21을 컨테이너 안에 마운트해서 사용.

맥북 터미널:

```bash
docker run -d \
  --name crafty \
  -p 8443:8443 \
  -p 27462:27462 \
  -v /Users/insung/Documents/mc/crafty:/data \
  -v $(/usr/libexec/java_home -v 21):/java21:ro \
  --restart unless-stopped \
  registry.gitlab.com/crafty-controller/crafty-4:latest
```

초기 로그인 정보 확인:
```bash
docker logs crafty 2>&1 | grep -A3 "username\|password\|admin"
```

---

## Phase 3 — 웹 UI 초기 설정

1. 브라우저에서 `https://localhost:8443` 접속
   - 인증서 경고 뜨면 **고급 → 계속 진행** 클릭 (자체서명 인증서라 정상)
2. 로그인 후 비밀번호 변경
3. 좌측 메뉴 → **Servers** → **Import Existing Server** 클릭
4. 아래 값 입력:

| 항목 | 값 |
|---|---|
| Server Name | PaperJJK-v2 |
| Server Directory | `/Users/insung/Documents/mc/server` |
| Executable | `paper.jar` |
| Server Type | Paper |
| Memory (Min) | `2048` |
| Memory (Max) | `10240` |
| Java Executable Path | `/java21/bin/java` |

5. **Import** 클릭 → 서버 목록에 뜨면 완료

---

## Phase 4 — 서버 ID 확인

Crafty 웹 UI → Servers → 서버 클릭 → 주소창 URL 확인:
```
https://localhost:8443/#/server/xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx/dashboard
```
`xxxxxxxx-xxxx-...` 부분이 서버 ID — 복사해 두기

> **API 키 UI 관련:** Crafty의 API 키 생성 UI는 키 값을 표시하지 않는 버그가 있음.
> 대신 로그인 API로 토큰을 매번 발급받는 방식 사용 (아이디/비번 기반).

---

## Phase 5 — deploy.sh 수정

기존 screen 방식 → Crafty API 방식으로 교체.

맥북 터미널에서 아래 명령 전체 복붙:

```bash
cat > /Users/insung/Documents/mc/server/deploy.sh << 'EOF'
#!/bin/bash
set -e

JAR_NAME="PaperJJK-v2.jar"
PLUGINS_DIR="/Users/insung/Documents/mc/server/plugins"
CRAFTY_URL="https://localhost:8443"
CRAFTY_USER="admin"
CRAFTY_PASS="여기에_Crafty_비밀번호"
CRAFTY_SERVER_ID="여기에_서버_ID"

echo "[deploy] Getting Crafty token..."
TOKEN=$(curl -sk -X POST "$CRAFTY_URL/api/v2/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"$CRAFTY_USER\",\"password\":\"$CRAFTY_PASS\"}" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['token'])")

echo "[deploy] Stopping server..."
curl -sk -X POST "$CRAFTY_URL/api/v2/servers/$CRAFTY_SERVER_ID/action/stop_server" \
  -H "Authorization: Bearer $TOKEN"
sleep 20

echo "[deploy] Downloading latest JAR..."
curl -fsSL \
  -H "Accept: application/octet-stream" \
  -o "$PLUGINS_DIR/$JAR_NAME" \
  "https://github.com/$REPO/releases/latest/download/$JAR_NAME"

echo "[deploy] Starting server..."
curl -sk -X POST "$CRAFTY_URL/api/v2/servers/$CRAFTY_SERVER_ID/action/start_server" \
  -H "Authorization: Bearer $TOKEN"

echo "[deploy] Done."
EOF
chmod +x /Users/insung/Documents/mc/server/deploy.sh
```

`CRAFTY_PASS` 와 `CRAFTY_SERVER_ID` 를 실제 값으로 교체.

---

## Phase 6 — start.sh 제거 (선택)

Crafty가 서버 시작/재시작을 담당하므로 기존 `start.sh` 루프는 더 이상 필요 없음.

기존 screen 세션이 살아있으면 종료:
```bash
screen -S mcserver -X quit 2>/dev/null || true
```

---

## Phase 7 — launchd 제거 (선택)

Crafty 컨테이너가 `--restart unless-stopped` 옵션으로 맥북 재부팅 시 자동 시작되므로 기존 launchd plist 불필요.

```bash
launchctl unload ~/Library/LaunchAgents/com.paperjjk.mcserver.plist 2>/dev/null || true
rm ~/Library/LaunchAgents/com.paperjjk.mcserver.plist
```

---

## 일상적인 사용법

| 작업 | 방법 |
|---|---|
| 서버 시작/정지 | 브라우저 `https://localhost:8443` → 버튼 클릭 |
| 콘솔 명령 입력 | Crafty 웹 UI → Console 탭 |
| 로그 확인 | Crafty 웹 UI → Logs 탭 |
| RAM/CPU 모니터링 | Crafty 웹 UI → Dashboard |
| 파일 수정 | Crafty 웹 UI → Files 탭 |
| Crafty 재시작 | `docker restart crafty` |
| Crafty 로그 확인 | `docker logs crafty` |

---

## 트러블슈팅

**브라우저에서 접속 안 됨:**
- Docker Desktop 실행 중인지 확인
- `docker ps` 로 crafty 컨테이너 상태 확인

**서버 시작 시 SIGSEGV / JVM crash:**
- Crafty 컨테이너 기본 Java(25)와 Paper 충돌
- 컨테이너를 Java 21 마운트 버전으로 재생성해야 함 (Phase 2 참고)
- 재생성 후 Crafty → 서버 Config → Java Executable Path: `/java21/bin/java` 설정

**서버 Import 후 시작이 안 됨:**
- Java Executable Path가 `/java21/bin/java` 로 설정됐는지 확인
- `paper.jar` 파일이 서버 폴더에 있는지 확인

**deploy.sh API 호출 실패:**
- `CRAFTY_PASS` 가 현재 Crafty 로그인 비밀번호와 일치하는지 확인
- 서버 ID가 URL에서 복사한 값과 일치하는지 확인
- `curl -sk` 의 `-k` 는 자체서명 인증서 무시 옵션 (필수)
- 토큰 발급 테스트:
  ```bash
  curl -sk -X POST "https://localhost:8443/api/v2/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"username":"admin","password":"비밀번호"}' | python3 -m json.tool
  ```
  응답에 `data.token` 값이 있으면 정상
