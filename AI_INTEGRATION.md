# AI 서비스 연동 (feat/ai-integration)

AI 파트(FastAPI, Team12_consume 레포 `backend/`) 와 Spring 백엔드를 실제로 연결한 변경 사항. 계약 문서는 Team12_consume 의 `backend/F03_INTERFACE.md` 이고, 이 문서는 백엔드 쪽에서 무엇이 바뀌었고 무엇을 설정해야 하는지만 적는다.

## 1. 한눈에

| 흐름 | 전 | 후 |
|---|---|---|
| 캡처 추출 `/extract` | 연결됨 | 그대로. `ownerName` 을 받아 본인 이름을 "본인" 으로 치환 |
| 은행 파일 `/parse` | 없음 | S01 업로드에 xlsx 를 넣으면 `/parse` 로 읽어 달별 거래로 저장 (암호는 `filePassword`) |
| 가맹점 분류 `/classify` | 클라이언트만 있고 호출 안 함 | 추출 직후 한 번 호출해 `Transaction.category/subcategory/categoryConfidence` 에 저장 |
| 패턴 설명 `/narrate` | 존재하지 않는 `/narratives/generate` 호출 → 항상 템플릿 | `AiNarrativeService` 가 `/narrate` 를 부르고 세션·말투·패턴 스냅샷 단위로 캐시. S05 카드, S05-1 상세, S07 보고서가 같은 결과를 쓴다 |
| 알림 문구 `/notify-copy` | 연결됨 (관찰 창 13 고정) | 탐지 결과의 실제 관찰 창·조건을 보낸다 |
| 충전 되돌림 규칙 | 없음 | 그 달에 간편결제 결제 행이 없으면 충전 행을 `expense` 로 되돌림 (`charge_as_expense` 플래그) |
| 이체 처리 | 송금·저금(`transfer+out`) 이 패턴·비중에 포함 | 총지출 합계에는 남기고 패턴 탐지와 보고서 비중에서는 제외 |
| 패턴 수 | 2일 이상 관찰이면 전부 (실데이터 43개) | 3일 이상 관찰, 금액순 상위 10개 (AI 문장 상한과 같음) |
| compose | `ai` 서비스 없음 | `ai` 서비스 추가, `AI_SERVER_URL=http://ai:8001` |
| 실패 로그 | debug | warn + 응답 본문 (422 의 `errors` 가 보인다) |

AI 호출이 실패하면 전부 이전처럼 템플릿 문구·키워드 분류로 떨어진다. 500 이 나지 않는다.

## 2. 설정

### EC2 `~/app/.env`
```
DB_NAME=...
DB_PASSWORD=...
DOCKERHUB_USERNAME=...        # 백엔드와 같은 계정. ai 이미지도 여기서 받는다
ANTHROPIC_API_KEY=sk-ant-...  # AI 서비스만 읽는다. 백엔드 컨테이너에는 안 들어감
F03_MODEL=claude-sonnet-5-5
AI_MAX_CONCURRENT_CALLS=8
```
`.env.example` 참고. `.gitignore` 에 `.env` 를 추가했다.

### AI 이미지
Team12_consume 레포의 `.github/workflows/ai-image.yml` 이 `backend/**` 가 main 에 올라갈 때 `${DOCKERHUB_USERNAME}/line1thon-ai:latest` 를 푸시하고, EC2 에서 `docker compose pull ai && docker compose up -d ai` 를 돌린다. 그 레포 Secrets 에 `DOCKERHUB_USERNAME`, `DOCKERHUB_TOKEN`, `EC2_HOST`, `EC2_SSH_KEY` 가 필요하다 (백엔드 레포와 같은 값).

EC2 에서 처음 한 번은 `cd ~/app && docker compose up -d` 로 `ai` 까지 띄운다. `backend` 는 `ai` 가 `service_started` 면 뜨므로 AI 키가 없어도 백엔드는 올라온다.

### 로컬
```
# AI 서비스 (Team12_consume/backend 에서)
docker build -t line1thon-ai ./backend && docker run --rm --env-file backend/.env -p 127.0.0.1:8001:8001 line1thon-ai
# 백엔드는 기본값 AI_SERVER_URL=http://localhost:8001 그대로
```

## 3. API 변경 (프론트에 알릴 것)

- `POST /api/sessions` (S01), `POST /api/sessions/{id}/files` (E03), `POST /api/sessions/{id}/retry-failed` (E04)
  - `files` 에 캡처 이미지 외에 **xlsx** 도 넣을 수 있다. 확장자로 구분한다.
  - 선택 파라미터 `ownerName` (본인 이름, 캡처 치환용), `filePassword` (xlsx 열기 암호. 저장하지 않음).
  - xlsx 는 세션의 슬롯 달(`lastMonth` 에서 `monthsCount` 개 + 파일의 `slotMonths`)만 가져온다. 그 달에 거래가 없으면 파일이 FAILED 로 표시되고 사유는 "파일에 선택한 달의 거래가 없어요." 암호가 틀리면 AI 서비스의 사유(422)가 그대로 사유가 된다.
- `GET /api/analyses/{id}/patterns/{patternId}` (S05-1): observation/hypothesisQuestion/action 이 S05 카드와 같은 AI 문장이다 (전에는 고정 문구).
- `GET /api/analyses/{id}/result` (S05): 카드가 최대 10개다. `GET /api/sessions/{id}/report` (S07): `categoryRatios` 에 "이체" 가 없고 비중은 이체를 뺀 금액 기준이라 합이 100 이다. `totalExpense` 와 월별 추이는 이체를 포함한다.
- 응답 형태는 바뀌지 않았다.

## 4. 코드 변경 파일

| 파일 | 변경 |
|---|---|
| `docker-compose.yml`, `.env.example`, `.gitignore` | `ai` 서비스, 환경변수 |
| `entity/Transaction.java` | `category`, `subcategory`, `categoryConfidence` 컬럼 (ddl-auto: update 로 자동 추가), `updateCategory`, `overrideKind`, `restoreSelfTransfer` |
| `dto/F03ExtractResponse.java` | `AssetDto.slotMonth` (/parse 응답) |
| `service/AiExtractClient.java` | `requestParse` 추가, 4xx 를 `AiRejectedException(한글 사유)` 로 |
| `service/AiServiceClient.java` | DTO 를 명세대로 타입 지정(근거 거래 등), warn 로그, `toKoreanCategory` |
| `service/AiPayloadMapper.java` (새 파일) | 탐지 결과 → `/narrate` 요청 (근거 거래 1~10건, 기간, 요약) |
| `service/AiNarrativeService.java` | `/narrate` 호출 + 캐시 + 템플릿 대체. `Result` 에 `summaryLine`, `report` 추가 |
| `service/AnalysisPatternService.java` | 저장된 AI 분류 사용, `PatternItem` 에 `aiCondition`, `observedWindow`, `observedUnit` 추가 |
| `service/AnalysisSessionService.java` | xlsx → `/parse`, 추출 직후 `/classify`, 충전 되돌림 규칙, `ownerName`/`filePassword` |
| `service/ReportService.java` | 보고서가 저장된 분류와 `AiNarrativeService` 캐시를 쓴다 (매 조회마다 /classify, /narrate 를 다시 부르지 않음) |
| `service/NotificationAndSummaryService.java` | `/notify-copy` 에 구조화 조건·실제 관찰 창 전달 |
| `controller/AnalysisSessionController.java` | `ownerName`, `filePassword` 파라미터 |
| `controller/AnalysisResultController.java` | S05-1 상세가 캐시된 AI 문장을 쓴다 |

## 5. 규칙 설명

**관찰 창.** 요일 패턴은 "데이터 기간 안의 그 요일 수" 를 창으로, 단위 "주" ("13주 중 6회"). 심야·연쇄는 데이터 기간의 일수, 단위 "일". AI 는 이 숫자만 문장에 쓸 수 있다.

**분류 라벨.** AI 카테고리 코드(food, delivery, cafe, shopping, transport, living, other) 와 세부 분류를 백엔드 한글 라벨로 바꾼다 (`AiServiceClient.toKoreanCategory`). 세부 분류가 있으면 그것이 라벨이다: 주점, 택시, 교통, 구독, 편의점, 주거·통신, 의료, 여가, 차량, 이체. 없으면 외식, 배달, 카페, 쇼핑, 생활, 기타. 패턴 탐지와 보고서 비중이 이 라벨로 묶인다. AI 분류가 없는 행(분류 실패)은 예전 키워드 분류로 떨어지되, `kind=transfer` 면 "이체" 로 둔다.

**이체 제외.** 백엔드 원래 규칙(`Transaction.isCountedAsExpense`) 은 `transfer+out`(송금·저금) 을 지출로 센다. 실제 파일에선 이 행이 지출의 73% 라 상위 패턴이 전부 "화요일 이체" 가 됐다. 그래서 "이체" 라벨 행은 총지출 합계·월별 추이에는 남기고(통장과 숫자가 맞게), 패턴 탐지 입력과 보고서 카테고리 비중에서는 뺀다 (`AnalysisPatternService.isTransfer`).

**패턴 수.** 탐지기는 서로 다른 날 3일 이상(`PatternDetector.MIN_OBSERVED_DAYS`) 관찰된 것만 패턴으로 본다 (전에는 2일). 금액순으로 정렬해 상위 10개(`MAX_PATTERNS`) 만 S05 카드로 내보낸다. `/narrate` 가 받는 패턴 상한이 10개라 모든 카드가 AI 문장이 된다.

**충전 되돌림.** AI 는 "네이버페이 충전", "내 계좌 → 카카오페이" 를 본인 이동(self_transfer, 집계 제외) 으로 준다. 백엔드는 달마다 간편결제 회사(네이버파이낸셜·네이버페이·카카오페이·토스페이·페이코) 가 들어간 `expense` 행이 하나도 없으면 그 달의 충전·이동 행을 `expense` 로 바꾸고 `charge_as_expense` 플래그를 남긴다. 나중에 같은 달의 결제 행이 들어오면 다시 self_transfer 로 돌린다. 교통카드(티머니) 충전은 AI 단계에서 이미 expense 다.

**캐시.** `AiNarrativeService` 는 (세션, 말투, 패턴 id·관찰일·합계·조건) 을 키로 AI 결과를 메모리에 둔다. 거래를 수정하거나 말투를 바꾸면 키가 달라져 다시 부른다. 재기동하면 비워진다.

## 6. 확인한 것 / 못 한 것

- `./gradlew compileJava`, 탐지기 단위 테스트 통과.
- AI 서비스 쪽은 `/extract`, `/parse`, `/classify`, `/narrate`, `/notify-copy` 모두 실제 모델 호출로 검증돼 있다 (Team12_consume `backend/EVAL_LOG.md`).
- MySQL 을 붙인 전체 흐름(S01 xlsx 업로드 → S05 카드) 은 이 브랜치에서 직접 돌려보지 않았다. 붙여 보고 문제가 있으면 AI 파트에 알려 달라. 가장 먼저 볼 로그는 `AI /narrate 실패 status=422 body=...` 이다.
