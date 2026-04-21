# PaperJJK-v2 빌드-배포 자동화 계획

## 검증된 구성

- **Tailscale GitHub Action**: 공식 지원, 동작 확인
- **무료 플랜 제한**: ephemeral 리소스(CI runner) 월 1,000분 → 빌드당 약 2-3분 = 월 300~400회 배포 가능 (개인 개발 충분)
- **비상업적 개인 사용**에 한해 무료

---

## 전체 흐름

```
개발 PC (Windows)
  └─ git push → GitHub

GitHub Actions
  ├─ 1. Gradle 빌드 → JAR 생성
  ├─ 2. GitHub Release (dev-latest) 에 JAR 업로드
  ├─ 3. Tailscale으로 맥북과 같은 VPN 네트워크 합류
  └─ 4. SSH로 맥북 접속 → deploy.sh 실행
           └─ plugins/ 에 새 JAR 복사
           └─ screen에 stop 명령 전송
           └─ start.sh 재시작 (루프로 자동 재기동)
```

**핵심:**
- 맥북에 공개 IP / 도메인 / webhook 서버 불필요
- GitHub Actions runner가 Tailscale VPN으로 맥북에 직접 SSH

---

## 구성 요소

| 구성 요소 | 위치 | 역할 |
|---|---|---|
| `.github/workflows/deploy.yml` | GitHub 레포 | 빌드 + JAR 업로드 + SSH 배포 |
| GitHub Release (`dev-latest`) | GitHub | JAR 저장 |
| Tailscale | 맥북 + GitHub Actions | 안전한 사설 네트워크 |
| `deploy.sh` | 맥북 `~/mc-server/` | JAR 교체 + 서버 재시작 |
| `start.sh` | 맥북 `~/mc-server/` | MC 서버 루프 실행 |
| `com.paperjjk.mcserver.plist` | 맥북 LaunchAgents | 부팅 시 서버 자동 시작 |

---

## Phase 1 — Tailscale 설정

### 1-1. 맥북에 Tailscale 설치

```bash
brew install tailscale
sudo tailscaled &
tailscale up
# 브라우저 로그인 후 고정 호스트명 생성됨
# 예: macbook.tail1234.ts.net
tailscale status   # 호스트명 확인
```

### 1-2. Tailscale Admin에서 Trust Credential 생성

1. https://login.tailscale.com/admin/settings/trust-credentials 접속
2. **"credential 새로 만들기"** 클릭
3. 아래 값 입력:

| 항목 | 값 |
|---|---|
| 타입 | **OpenID Connect** |
| Issuer | `https://token.actions.githubusercontent.com` |
| Audience | `https://tailscale.com` |
| Subject | `repo:깃허브유저명/PaperJJK-v2:ref:refs/heads/main` |
| Scope | **auth_keys → Write** |
| Tags | `tag:ci` |

4. 생성 후 나오는 **Client ID** 와 **Audience** 값 복사 (Secret 없음, 이게 신방식)

TgyNemds8421CNTRL-khPdissLqx11CNTRL

https://tailscale.com

### 1-3. ACL에 tag:ci 추가

Tailscale Admin → Access controls 에서 아래 항목 추가:

```json
"tagOwners": {
    "tag:ci": []
}
```

---

## Phase 2 — 맥북 SSH 설정

### 2-1. SSH 키 생성 (개발 PC 또는 맥북에서)

```bash
ssh-keygen -t ed25519 -C "github-actions-deploy" -f ~/.ssh/deploy_key -N ""
```

생성된 파일:
- `deploy_key` → GitHub Secret에 등록 (개인키)
- `deploy_key.pub` → 맥북에 등록 (공개키)

### 2-2. 맥북에 공개키 등록

```bash
# 맥북 터미널 어디서든 실행 가능 (절대경로 사용)
cat ~/.ssh/deploy_key.pub >> ~/.ssh/authorized_keys
chmod 600 ~/.ssh/authorized_keys
```

### 2-3. 맥북 SSH 활성화

시스템 설정 → 일반 → 공유 → **원격 로그인** ON

---

## Phase 3 — GitHub Secrets 등록

GitHub 레포 → Settings → Secrets and variables → Actions → New repository secret

| Secret 이름 | 값 |
|---|---|
| `TS_OAUTH_CLIENT_ID` | Tailscale Trust Credential의 Client ID |
| `TS_AUDIENCE` | Tailscale Trust Credential의 Audience (`https://tailscale.com`) |
| `MAC_HOST` | 맥북 Tailscale 호스트명 (`macbook.tail1234.ts.net`) |
| `MAC_USER` | 맥북 사용자명 |
| `MAC_SSH_KEY` | `deploy_key` 파일 전체 내용 (개인키) |
| `GITHUB_TOKEN` | 자동 제공됨 (등록 불필요) |

> `TS_OAUTH_SECRET`은 신방식(Trust Credentials)에서 불필요 — Client ID + Audience로 대체됨

---

## Phase 4 — GitHub Actions 워크플로

파일 경로: `.github/workflows/deploy.yml`

```yaml
name: Build & Deploy

on:
  push:
    branches: [ "main" ]

permissions:
  contents: write
  id-token: write   # Trust Credentials (Workload Identity) 필수

jobs:
  build-and-deploy:
    runs-on: ubuntu-latest

    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Setup Java 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'

      - name: Build with Gradle
        run: ./gradlew build

      - name: Upload to GitHub Release (dev-latest)
        uses: softprops/action-gh-release@v2
        with:
          tag_name: dev-latest
          name: "Dev Latest Build"
          files: build/libs/PaperJJK-v2.jar
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}

      - name: Connect to Tailscale
        uses: tailscale/github-action@v3
        with:
          oauth-client-id: ${{ secrets.TS_OAUTH_CLIENT_ID }}
          audience: ${{ secrets.TS_AUDIENCE }}
          tags: tag:ci

      - name: Deploy to Mac server
        uses: appleboy/ssh-action@v1
        with:
          host: ${{ secrets.MAC_HOST }}
          username: ${{ secrets.MAC_USER }}
          key: ${{ secrets.MAC_SSH_KEY }}
          script: |
            bash ~/mc-server/deploy.sh
```

---

## Phase 5 — 맥북: 배포 스크립트

파일 경로: `~/mc-server/deploy.sh`

```bash
#!/bin/bash
set -e

REPO="your-github-username/PaperJJK-v2"
JAR_NAME="PaperJJK-v2.jar"
PLUGINS_DIR="$HOME/mc-server/plugins"
SERVER_DIR="$HOME/mc-server"
SCREEN_NAME="mcserver"

echo "[deploy] Downloading latest JAR..."
curl -fsSL \
  -H "Accept: application/octet-stream" \
  -o "$PLUGINS_DIR/$JAR_NAME" \
  "https://github.com/$REPO/releases/latest/download/$JAR_NAME"

echo "[deploy] Stopping server..."
if screen -ls | grep -q "$SCREEN_NAME"; then
  screen -S "$SCREEN_NAME" -X stuff "stop$(printf '\r')"
  sleep 15
fi

echo "[deploy] Starting server..."
screen -dmS "$SCREEN_NAME" bash "$SERVER_DIR/start.sh"

echo "[deploy] Done."
```

```bash
chmod +x ~/mc-server/deploy.sh
```

---

## Phase 6 — 맥북: 서버 실행 스크립트

파일 경로: `~/mc-server/start.sh`

```bash
#!/bin/bash
cd "$(dirname "$0")"

while true; do
  java -Xms2G -Xmx8G -jar paper.jar nogui
  echo "[$(date '+%F %T')] Server stopped. Restarting in 3s..."
  sleep 3
done
```

```bash
chmod +x ~/mc-server/start.sh
```

- `stop` 명령 후 루프가 재시작 → 새 JAR 자동 적용
- 크래시도 자동 복구

---

## Phase 7 — 맥북: 부팅 시 서버 자동 시작 (선택)

파일 경로: `~/Library/LaunchAgents/com.paperjjk.mcserver.plist`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>com.paperjjk.mcserver</string>

    <key>ProgramArguments</key>
    <array>
        <string>/bin/bash</string>
        <string>/Users/유저명/mc-server/start.sh</string>
    </array>

    <key>RunAtLoad</key><true/>
    <key>KeepAlive</key><true/>

    <key>StandardOutPath</key>
    <string>/Users/유저명/mc-server/server.log</string>
    <key>StandardErrorPath</key>
    <string>/Users/유저명/mc-server/server.log</string>
</dict>
</plist>
```

```bash
launchctl load ~/Library/LaunchAgents/com.paperjjk.mcserver.plist
```

---

## Phase 8 — build.gradle JAR 파일명 고정

`build.gradle` 또는 `build.gradle.kts`에 추가:

```groovy
// build.gradle
tasks.jar {
    archiveFileName = 'PaperJJK-v2.jar'
}
```

```kotlin
// build.gradle.kts
tasks.jar {
    archiveFileName.set("PaperJJK-v2.jar")
}
```

버전 번호가 파일명에 붙으면 curl 다운로드 URL이 매번 달라지므로 고정 필수.

---

## 설치 순서 체크리스트

```
[ ] 1. build.gradle에서 JAR 파일명 고정
[ ] 2. 맥북에 Tailscale 설치 및 로그인
[ ] 3. Tailscale Admin에서 OAuth Client 생성 (auth_keys scope, tag:ci)
[ ] 4. Tailscale Admin ACL에 "tagOwners": {"tag:ci": []} 추가
[ ] 5. SSH 키 생성 → 공개키 맥북 authorized_keys 등록
[ ] 6. 맥북 시스템 설정 → 원격 로그인 ON
[ ] 7. GitHub Secrets 5개 등록
[ ] 8. .github/workflows/deploy.yml 추가
[ ] 9. ~/mc-server/deploy.sh 작성 (REPO 변수 실제 값으로 수정)
[ ] 10. ~/mc-server/start.sh 작성
[ ] 11. git push → GitHub Actions 로그 확인
[ ] 12. 맥북에서 screen -r mcserver 로 서버 콘솔 확인
```

---

## 트러블슈팅

**Tailscale 연결 실패 (tag:ci 오류):**
- ACL에 `"tagOwners": {"tag:ci": []}` 추가했는지 확인
- Trust Credential의 scope가 `auth_keys` Write인지 확인
- workflow permissions에 `id-token: write` 있는지 확인

**SSH 연결 거부:**
- 맥북 원격 로그인 ON 확인
- `tailscale status`로 맥북 호스트명 재확인
- `ssh -i deploy_key 유저명@macbook.tail1234.ts.net` 로 수동 테스트

**JAR 다운로드 실패 (404):**
- GitHub Release에 `dev-latest` 태그가 생성됐는지 확인
- 파일명이 `PaperJJK-v2.jar`로 정확히 올라갔는지 확인

**서버가 15초 안에 안 꺼짐:**
- `deploy.sh`의 `sleep 15` 값을 월드 크기에 맞게 늘리기

**월 1,000분 초과 시:**
- Tailscale 무료 플랜 한도 → Premium 업그레이드 또는 빌드 횟수 줄이기
- main 대신 태그 푸시에서만 배포하도록 워크플로 조건 변경 가능
