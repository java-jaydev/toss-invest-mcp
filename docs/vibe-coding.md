# 🌱 비개발자를 위한 초친절 설치 가이드 (바이브코딩)

[← README로 돌아가기](../README.md)

> 이 문서는 **터미널·개발 경험이 거의 없는 분**을 위한 것입니다. 핵심 요령은 하나예요:
> **직접 다 하려 하지 말고, AI(Claude Code·Cursor 등)에게 부탁하세요.** 아래 문장을 그대로
> 복사해서 AI에게 붙여넣으면, AI가 대신 명령어를 실행하고 막히면 고쳐줍니다.

## 🗺️ 큰 그림 (뭘 하게 되나요)

1. **Java 21** 준비 — 이 프로그램을 돌리는 엔진
2. 이 저장소를 **내려받아 빌드** — 실행 파일(jar)을 만듦
3. **토스 API 키 발급** + 내 서버 IP를 토스에 등록
4. AI 도구(Claude 등)에 **연결 설정** 붙여넣기
5. AI에게 **"삼성전자 현재가 알려줘"** 라고 물어보기 🎉

## 🤖 가장 쉬운 길: AI에게 통째로 맡기기

Claude Code나 Cursor를 열고, 이렇게 부탁하세요:

> "https://github.com/java-jaydev/toss-invest-mcp 이 저장소를 클론해서 빌드해줘.
> Java 21이 필요하니 없으면 설치도 안내해줘. 나는 터미널이 처음이라 한 단계씩 알려줘."

AI가 명령을 실행하며 진행합니다. 아래는 각 단계에서 무슨 일이 일어나는지, 그리고 막혔을 때
어디를 봐야 하는지입니다.

## 1️⃣ Java 21 확인

터미널(또는 AI에게 실행 요청)에서:

```bash
java -version
```

- `21` 이상이면 통과 ✅
- 낮거나 "없음"이면 [Adoptium](https://adoptium.net/temurin/releases/?version=21)에서 **Temurin 21**을 설치하세요. (AI에게 "내 OS에 맞는 Java 21 설치법 알려줘"라고 물어도 됩니다.)

## 2️⃣ 내려받아 빌드

```bash
git clone https://github.com/java-jaydev/toss-invest-mcp.git
cd toss-invest-mcp
./gradlew build
```

`BUILD SUCCESSFUL`이 뜨면 `build/libs/toss-invest-mcp-0.1.0.jar`가 만들어진 겁니다.

## 3️⃣ 토스 API 키 발급 + IP 등록

1. [openapi.tossinvest.com](https://openapi.tossinvest.com)에서 **클라이언트 ID·시크릿** 발급.
2. **허용 IP 등록** — 토스는 등록된 IP에서 온 요청만 받습니다. 내 서버(이 프로그램을 돌리는
   컴퓨터)의 **공인 IP**를 확인해서 토스 콘솔에 넣으세요:

   ```bash
   curl https://checkip.amazonaws.com
   ```

   여기서 나온 IP를 토스 허용목록에 등록합니다. (집 인터넷이면 IP가 바뀔 수 있어요. 나중에
   인증이 안 되면 이 명령으로 다시 확인하세요.)

## 4️⃣ AI 도구에 연결 설정 붙여넣기

Claude Code의 MCP 설정(`.mcp.json`)에 아래를 넣습니다. `/절대경로/` 부분은 2단계에서 만든
jar의 실제 경로로 바꾸세요(모르면 AI에게 "이 jar의 절대경로 알려줘"라고 물어보세요).

```json
{
  "mcpServers": {
    "toss-invest": {
      "command": "java",
      "args": ["-jar", "/절대경로/build/libs/toss-invest-mcp-0.1.0.jar"],
      "env": {
        "TOSS_CLIENT_ID": "발급받은_클라이언트_ID",
        "TOSS_CLIENT_SECRET": "발급받은_시크릿"
      }
    }
  }
}
```

설정을 저장하고 AI 도구를 재시작하면 `toss-invest`가 도구로 잡힙니다.

## 5️⃣ 물어보기 🎉

> "삼성전자(005930) 현재가 알려줘" · "애플(AAPL) 지금 얼마야?" · "삼성전자 최근 5일 일봉 보여줘"

## 🩹 자주 막히는 곳 (실제로 겪은 것들)

| 증상 | 원인 & 해결 |
|---|---|
| **`invalid_client` (401)** | 키 파일이 **Windows 줄바꿈(CRLF)**으로 저장돼 값 끝에 보이지 않는 `\r`이 붙은 경우가 많습니다. 키 자체는 멀쩡한데 인증만 실패해요. 파일을 **LF(유닉스 줄끝)**로 저장하거나, AI에게 "이 파일 CRLF 제거해줘"라고 하세요. |
| **`UnsupportedClassVersionError`** | 실행 Java가 21 미만입니다. MCP 설정의 `"command": "java"`가 21+를 가리키게 하세요(절대경로로 지정 가능). |
| **계속 401 / 인증 실패** | 토스 **허용 IP**에 현재 공인 IP가 없을 수 있습니다. `curl https://checkip.amazonaws.com`로 확인 후 콘솔에 등록. |
| **주말·야간에 값이 "옛날 것"** | 정상입니다. 장이 닫히면 API가 **마지막 세션 스냅샷**(예: 금요일 종가)을 돌려줍니다. |
| **빌드 실패** | 대부분 Java 버전 문제입니다. `java -version`부터 확인하세요. |

## 💬 AI에게 그대로 부탁할 문장 모음

- "toss-invest-mcp 빌드하다 `UnsupportedClassVersionError`가 났어. 내 Java 버전 확인하고 21로 맞춰줘."
- "`.env` 파일이 CRLF인지 확인하고 LF로 바꿔줘."
- "내 공인 IP 확인해서 알려줘. 토스 허용목록에 넣을 거야."
- "MCP 설정에 toss-invest 서버 등록하는 JSON을 내 jar 절대경로로 채워서 만들어줘."

막히면 언제든 AI에게 **에러 메시지를 그대로 붙여넣고** "이거 고쳐줘"라고 하세요. 그게 바이브코딩입니다. 🚀
