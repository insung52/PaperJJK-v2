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

## 경로 정보

| 항목 | 경로 |
|---|---|
| 서버 루트 | `/Users/insung/Documents/mc/server` |
| plugins 폴더 | `/Users/insung/Documents/mc/server/plugins` |
| deploy 스크립트 | `/Users/insung/Documents/mc/server/deploy.sh` |
| 서버 실행 스크립트 | `/Users/insung/Documents/mc/server/start.sh` |
| 맥북 사용자명 | `insung` |

---

## 구성 요소

| 구성 요소 | 위치 | 역할 |
|---|---|---|
| `.github/workflows/deploy.yml` | GitHub 레포 | 빌드 + JAR 업로드 + SSH 배포 |
| GitHub Release (`dev-latest`) | GitHub | JAR 저장 |
| Tailscale | 맥북 + GitHub Actions | 안전한 사설 네트워크 |
| `deploy.sh` | 맥북 서버 폴더 | JAR 교체 + 서버 재시작 |
| `start.sh` | 맥북 서버 폴더 | MC 서버 루프 실행 |
| `com.paperjjk.mcserver.plist` | 맥북 LaunchAgents | 부팅 시 서버 자동 시작 (선택) |

---

## Phase 0 — 맥북: Paper 서버 초기 세팅

### 0-1. Java 설치 확인

```bash
java -version
```

없으면 설치:
```bash
brew install --cask temurin@21
```

### 0-2. 서버 폴더 및 plugins 폴더 생성

```bash
mkdir -p /Users/insung/Documents/mc/server/plugins
```

### 0-3. Paper JAR 다운로드

https://papermc.io/downloads/paper 에서 **1.21.x** 최신 빌드 다운로드 후 서버 폴더에 `paper.jar` 이름으로 저장.

또는 터미널에서 직접 (빌드 번호는 사이트에서 최신 확인):
```bash
cd /Users/insung/Documents/mc/server
curl -o paper.jar "https://api.papermc.io/v2/projects/paper/versions/1.21.4/builds/BUILDNUM/downloads/paper-1.21.4-BUILDNUM.jar"
```

### 0-4. 첫 실행 (EULA 동의)

```bash
cd /Users/insung/Documents/mc/server
java -Xms2G -Xmx8G -jar paper.jar nogui
# → eula=false 오류 뜨면서 종료됨 (정상)

# eula.txt 수정
sed -i '' 's/eula=false/eula=true/' eula.txt

# 다시 실행해서 서버 정상 기동 확인 후 stop
java -Xms2G -Xmx8G -jar paper.jar nogui
```

서버 콘솔에서 `stop` 입력해서 종료.

---

## Phase 1 — Tailscale 설정

### 1-1. 맥북에 Tailscale 설치

```bash
brew install tailscale
sudo tailscaled &
tailscale up
# 브라우저 로그인 후 고정 호스트명 생성됨
# 예: insung-macbook-air.tail1234.ts.net
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

4. 생성 후 나오는 **Client ID** 와 **Audience** 값 복사

### 1-3. ACL에 tag:ci 추가

Tailscale Admin → Access controls 에서 아래 항목 추가:

```json
"tagOwners": {
    "tag:ci": ["autogroup:admin"]
}
```

---

## Phase 2 — 맥북 SSH 설정

### 2-1. SSH 키 생성 (맥북에서 실행)

```bash
ssh-keygen -t ed25519 -C "github-actions-deploy" -f ~/.ssh/deploy_key -N ""
```

생성된 파일:
- `~/.ssh/deploy_key` → GitHub Secret에 등록 (개인키)
- `~/.ssh/deploy_key.pub` → 맥북 authorized_keys에 등록 (공개키)

### 2-2. 맥북에 공개키 등록

```bash
cat ~/.ssh/deploy_key.pub >> ~/.ssh/authorized_keys
chmod 600 ~/.ssh/authorized_keys
```

### 2-3. 개인키 내용 복사 (GitHub Secret 등록용)

```bash
cat ~/.ssh/deploy_key
# -----BEGIN OPENSSH PRIVATE KEY----- 부터 끝까지 전체 복사
```

### 2-4. 맥북 SSH 활성화

시스템 설정 → 일반 → 공유 → **원격 로그인** ON

---

## Phase 3 — GitHub Secrets 등록

GitHub 레포 → Settings → Secrets and variables → Actions → New repository secret

| Secret 이름 | 값 |
|---|---|
| `TS_OAUTH_CLIENT_ID` | Tailscale Trust Credential의 Client ID |
| `TS_AUDIENCE` | `https://tailscale.com` |
| `MAC_HOST` | 맥북 Tailscale 호스트명 (예: `insung-macbook-air.tail1234.ts.net`) |
| `MAC_USER` | `insung` |
| `MAC_SSH_KEY` | `~/.ssh/deploy_key` 파일 전체 내용 (개인키) |

> `GITHUB_TOKEN`은 자동 제공됨 — 등록 불필요

---

## Phase 4 — GitHub Actions 워크플로

파일 경로: `.github/workflows/deploy.yml` (이미 생성됨)

```yaml
name: Build & Deploy

on:
  push:
    branches: [ "main" ]

permissions:
  contents: write
  id-token: write

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
            REPO="${{ github.repository }}" bash /Users/insung/Documents/mc/server/deploy.sh
```

---

## Phase 5 — 맥북: 배포 스크립트

파일 경로: `/Users/insung/Documents/mc/server/deploy.sh`

맥북 터미널에서 아래 명령 전체 복붙:

```bash
cat > /Users/insung/Documents/mc/server/deploy.sh << 'EOF'
#!/bin/bash
set -e

JAR_NAME="PaperJJK-v2.jar"
PLUGINS_DIR="/Users/insung/Documents/mc/server/plugins"
SERVER_DIR="/Users/insung/Documents/mc/server"
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
EOF
chmod +x /Users/insung/Documents/mc/server/deploy.sh
```

---

## Phase 6 — 맥북: 서버 실행 스크립트

파일 경로: `/Users/insung/Documents/mc/server/start.sh`

맥북 터미널에서 아래 명령 전체 복붙:

```bash
cat > /Users/insung/Documents/mc/server/start.sh << 'EOF'
#!/bin/bash
cd /Users/insung/Documents/mc/server

while true; do
  java -Xms2G -Xmx8G -jar paper.jar nogui
  echo "[$(date '+%F %T')] Server stopped. Restarting in 3s..."
  sleep 3
done
EOF
chmod +x /Users/insung/Documents/mc/server/start.sh
```

서버 수동 시작:
```bash
screen -dmS mcserver bash /Users/insung/Documents/mc/server/start.sh
```

서버 콘솔 접속:
```bash
screen -r mcserver
# 콘솔에서 나오려면 Ctrl+A → D (서버는 유지됨)
```

---

## Phase 7 — 맥북: 부팅 시 서버 자동 시작 (선택)

파일 경로: `~/Library/LaunchAgents/com.paperjjk.mcserver.plist`

```bash
cat > ~/Library/LaunchAgents/com.paperjjk.mcserver.plist << 'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>com.paperjjk.mcserver</string>

    <key>ProgramArguments</key>
    <array>
        <string>/bin/bash</string>
        <string>/Users/insung/Documents/mc/server/start.sh</string>
    </array>

    <key>RunAtLoad</key><true/>
    <key>KeepAlive</key><true/>

    <key>StandardOutPath</key>
    <string>/Users/insung/Documents/mc/server/server.log</string>
    <key>StandardErrorPath</key>
    <string>/Users/insung/Documents/mc/server/server.log</string>
</dict>
</plist>
EOF
launchctl load ~/Library/LaunchAgents/com.paperjjk.mcserver.plist
```

---

## Phase 8 — build.gradle JAR 파일명 고정

이미 적용됨. `build.gradle` 확인:

```groovy
tasks.jar {
    archiveFileName = 'PaperJJK-v2.jar'
    if (!System.getenv('CI')) {
        destinationDirectory = file('C:\\Users\\rapid\\Desktop\\exfil1.21.11\\plugins')
    }
}
```

- 로컬 빌드: `C:\Users\rapid\Desktop\exfil1.21.11\plugins` 에 복사
- GitHub Actions: `build/libs/PaperJJK-v2.jar` 에 생성

---

## 설치 순서 체크리스트

```
[x] 1. build.gradle JAR 파일명 고정 + CI 분기 처리
[x] 2. .github/workflows/deploy.yml 생성
[ ] 3. 맥북: Paper 서버 초기 세팅 (Phase 0)
[ ] 4. 맥북: Tailscale 설치 및 로그인
[ ] 5. Tailscale Admin: Trust Credential 생성 (auth_keys Write, tag:ci)
[ ] 6. Tailscale Admin: ACL에 tagOwners tag:ci 추가
[ ] 7. 맥북: SSH 키 생성 → authorized_keys 등록
[ ] 8. 맥북: 시스템 설정 → 원격 로그인 ON
[ ] 9. GitHub Secrets 5개 등록
[ ] 10. 맥북: deploy.sh 생성
[ ] 11. 맥북: start.sh 생성
[ ] 12. git push → GitHub Actions 로그 확인
[ ] 13. 맥북: screen -r mcserver 로 서버 콘솔 확인
```

---

## 트러블슈팅

**Tailscale 연결 실패 (tag:ci 오류):**
- ACL `tagOwners`에 `"tag:ci": ["autogroup:admin"]` 확인
- Trust Credential scope가 `auth_keys` Write인지 확인
- workflow `permissions`에 `id-token: write` 있는지 확인

**SSH 연결 거부:**
- 맥북 원격 로그인 ON 확인
- `tailscale status`로 맥북 호스트명 재확인
- 수동 테스트: `ssh -i ~/.ssh/deploy_key insung@<맥북Tailscale호스트명>`

**JAR 다운로드 실패 (404):**
- GitHub Release에 `dev-latest` 태그가 생성됐는지 확인
- 파일명이 `PaperJJK-v2.jar`로 정확히 올라갔는지 확인

**서버가 15초 안에 안 꺼짐:**
- `deploy.sh`의 `sleep 15` 값을 늘리기

**월 1,000분 초과 시:**
- main 대신 태그 푸시에서만 배포하도록 워크플로 조건 변경
