# HorizonAI Optimizer

Plugin Paper/Purpur 1.21.11 (Java 21) giám sát hiệu suất server, phát hiện máy Redstone gây quá tải và xử lý theo hai cấp: **cảnh báo** rồi **cách ly có thể hoàn tác**. AI là lớp cố vấn tùy chọn; hệ thống cục bộ vẫn hoạt động khi AI tắt hoặc mất kết nối.

> **Trạng thái build:** mã nguồn đã được kiểm tra cú pháp và kiểm thử logic thuần (xem mục *Kiểm thử*). Môi trường phát triển không tải được Paper API nên **chưa biên dịch JAR thật**. Hãy chạy `gradle build` trên máy của bạn trước khi triển khai.

---

## 1. Phạm vi

### Đã triển khai
| Nhóm | Nội dung |
|---|---|
| Thu thập | TPS, MSPT, CPU, RAM, số người chơi, chunk đã tải, entity; Lag Spike Detector (baseline EWMA) |
| Redstone | Ghi nhận sự kiện theo block với giới hạn bộ nhớ, thống kê chu kỳ (Welford), phân cụm máy, ID ổn định |
| Phân tích | Rule-based + Statistical Anomaly + Performance Correlation (Pearson), Clock Detector |
| Cảnh báo L1 | Chỉ gửi cho người chơi gần máy, ưu tiên người kích hoạt, có cooldown theo máy và theo người chơi |
| Cách ly L2 | Chỉ hủy piston / dispenser / hopper **trong hộp của đúng một máy**; có Restore; snapshot trạng thái; lưu DB để phục hồi sau khi restart |
| AI | Provider OpenAI-compatible, đổi qua config; API key từ biến môi trường hoặc `ai.api-key` trong config; timeout, retry có backoff, rate limit theo giờ, circuit breaker; AI chỉ **phủ quyết** cách ly, không tự kích hoạt |
| Chunk / Entity / Hopper | Quét tăng dần (incremental), chỉ **cảnh báo**: entity, item rơi, mob farm theo ngưỡng từng loại, hopper |
| Hành động | Mọi đề xuất (AI hoặc cục bộ) đi qua `ActionValidator`; chỉ whitelist mới tự thực hiện |
| Lệnh | `status`, `analyze`, `report`, `redstone list/inspect/isolate/restore`, `optimize`, `history`, `whitelist`, `reload` |
| Lưu trữ | SQLite (WAL), ghi bất đồng bộ, audit log, lịch sử hiệu suất, dọn dữ liệu theo `retention-days` |

### Chưa triển khai (ghi rõ để không hiểu nhầm)
- **Dashboard web** (biểu đồ thời gian thực, xác thực, phân quyền API). Đây là phần lớn nhất còn thiếu; sẽ làm ở bản sau vì cần thiết kế riêng về bảo mật.
- **Performance Prediction** (dự báo quá tải từ lịch sử).
- **Cách ly máy chỉ dùng dust / repeater / observer** (xem *Giới hạn Paper API*).

---

## 2. Kiến trúc

```
com.horizon.ai
├── HorizonAI.java              Điểm vào, khởi tạo/reload toàn bộ thành phần
├── utils/                      Settings (config đã kẹp giá trị), Region, BlockPos, Severity, Text
├── performance/                PerformanceMonitor (mẫu hiệu suất, lag spike), PerfSample
├── redstone/
│   ├── ActivityTracker         Ghi nhận sự kiện theo block, giới hạn cứng số block
│   ├── ActorTracker            Người đặt / người tương tác gần nhất (LRU), không suy đoán
│   ├── MachineIdentifier       Phân cụm block thành máy, đánh dấu oversized / ambiguous
│   ├── RedstoneAnalyzer        Logic đánh giá (thuần Java, có unit test)
│   ├── Machine, MachineTracker Trạng thái, lịch sử, quét thành phần có giới hạn
│   ├── MachineIsolation        Cách ly / khôi phục, snapshot, vùng bất biến cho listener
│   └── RedstoneGuardian        Vòng lặp mỗi cửa sổ: thu thập → phân cụm (async) → quyết định
├── events/                     RedstoneEventListener (ghi nhận), IsolationListener (chặn trong hộp)
├── ai/                         AIProvider, OpenAIProvider, AIAnalyzer, AIResponseParser, CircuitBreaker
├── analyzer/                   LocalAnalyzer (không cần AI), HealthReport
├── optimizer/                  ActionValidator, WarningService
├── chunk/                      ChunkHealthScanner (entity, item, mob farm, hopper)
├── storage/                    Database (SQLite, luồng riêng)
└── commands/                   HorizonCommand
```

**Luồng dữ liệu một cửa sổ (mặc định 10 giây):**
1. *Main Thread*: rút snapshot block hoạt động (đã giới hạn số block).
2. *Async*: phân cụm block thành máy (không chạm Bukkit API).
3. *Main Thread*: khớp cụm với máy đã biết, quét thành phần (tối đa 2 lần quét/cửa sổ), phân tích, ra quyết định L1/L2.
4. Định kỳ: gửi snapshot JSON cho AI (async). Kết quả chỉ được áp dụng sau khi đi qua `ActionValidator`.

---

## 3. Yêu cầu và build

- JDK 21
- Gradle 8.x (hoặc tương thích Kotlin DSL)
- Paper hoặc Purpur 1.21.11

```bash
cd horizonai-optimizer
gradle clean build        # biên dịch, chạy unit test, đóng gói JAR
gradle test               # chỉ chạy kiểm thử đơn vị
```

JAR tạo tại `build/libs/HorizonAI-Optimizer-1.0.0.jar`.

Jackson và SQLite JDBC **không được đóng gói vào JAR**. Chúng được khai báo trong `plugin.yml` (mục `libraries`) và Paper tự tải khi khởi động. Lần chạy đầu cần Internet tới Maven Central.

---

## 4. Cài đặt

1. Copy JAR vào `plugins/`.
2. Khởi động server một lần để tạo `plugins/HorizonAI/config.yml` và `horizonai.db`.
3. Chỉnh `config.yml` theo nhu cầu, sau đó `/horizonai reload`.

---

## 5. Cấu hình AI

AI là **tùy chọn**. Nếu không có API key, plugin ghi cảnh báo trong console và chạy bằng phân tích cục bộ.

**Bước 1: đặt API key bằng biến môi trường** (không ghi vào config):

```bash
# Linux / macOS, đặt trong script khởi động
export HORIZONAI_AI_KEY="sk-..."
java -Xms4G -Xmx4G -jar paper.jar nogui
```

Với systemd: `Environment=HORIZONAI_AI_KEY=sk-...` trong unit file.

**Bước 2: chọn provider và model trong `config.yml`:**

```yaml
ai:
  enabled: true
  provider: openai               # openai | openai-compatible
  model: gpt-4o-mini
  endpoint: https://api.openai.com/v1/chat/completions
  api-key-env: HORIZONAI_AI_KEY  # TÊN biến môi trường, không phải key
  analysis-interval-seconds: 300
  max-requests-per-hour: 12
```

Dùng nhà cung cấp tương thích OpenAI khác: đổi `endpoint` sang địa chỉ HTTPS của họ và đặt `provider: openai-compatible`. Endpoint không HTTPS sẽ bị từ chối (trừ localhost).

**Cách 2: dán key trực tiếp vào `config.yml`** (dùng khi không đặt được biến môi trường, ví dụ trên panel hosting):

```yaml
ai:
  api-key: "DÁN_KEY_CỦA_BẠN"
```

Thứ tự ưu tiên: biến môi trường `HORIZONAI_AI_KEY` → `ai.api-key`. Sau khi sửa config, chạy `/horizonai reload`. Khi nạp thành công, console ghi nguồn key (không hiển thị giá trị). Không commit file config có key lên GitHub.

**Ràng buộc bảo mật của AI:**
- AI chỉ nhận snapshot JSON (hiệu suất, thống kê máy, bằng chứng). **Không** nhận tên người chơi, không có quyền truy cập world, không thực thi lệnh.
- AI không thể kích hoạt cách ly. Chỉ có thể **phủ quyết** (`causal: false`) một máy đã đủ điều kiện cục bộ.
- Đề xuất `ISOLATE_MACHINE` từ AI luôn chờ Admin duyệt. `RESTORE_MACHINE` từ AI bị từ chối.
- Circuit breaker: sau 3 lỗi liên tiếp, tạm dừng gọi AI trong 5 phút.

---

## 6. Redstone: cảnh báo và cách ly

### Điều kiện để một máy bị coi là nguyên nhân
Cần **đồng thời**:
1. Hoạt động vượt ngưỡng (`detection-threshold-events-per-window`) hoặc đột biến so với baseline.
2. Đủ số mẫu tương quan (`min-samples`, mặc định 12).
3. Hệ số Pearson giữa hoạt động và MSPT ≥ `min-correlation` (mặc định 0.5).
4. MSPT khi máy hoạt động cao hơn lúc nghỉ ≥ `min-mspt-correlation-delta` (mặc định 5 ms).

Hoạt động nhiều **không đủ** để bị phạt. Máy hoạt động mạnh nhưng MSPT không tăng sẽ không bị cảnh báo mức cao.

### Level 1: Cảnh báo
- Chỉ gửi cho người chơi trong bán kính `warning.distance`, ưu tiên người kích hoạt gần nhất (nếu xác định được).
- Không công khai toàn server. Nếu không có ai ở gần, thông báo cho Admin.
- Cooldown theo máy và theo người chơi. Người có quyền `horizonai.notify.exclude` không nhận cảnh báo.

### Level 2: Cách ly
Điều kiện: đã cảnh báo đủ số lần, máy vẫn đủ điều kiện nghiêm ngặt (mức ≥ HIGH, có tương quan, độ tin cậy ≥ 70%, ranh giới rõ, không có máy khác cùng hoạt động, AI không phủ quyết).

- `auto-isolate: false` (mặc định): máy chuyển sang trạng thái chờ, Admin duyệt bằng `/horizonai redstone isolate <id>`.
- `auto-isolate: true`: tự cách ly sau `isolation-delay-seconds`.

**Phạm vi tác động của cách ly** (chỉ trong hộp của máy đó):
- Piston và sticky piston không đẩy/kéo.
- Dispenser và dropper không phun/thả.
- Hopper không chuyển item vào/ra.

**Không làm:** không xóa, không thay block; không xóa item hoặc inventory; không tắt Redstone theo chunk, world hay server; không ảnh hưởng máy khác.

**Khôi phục:** `/horizonai redstone restore <id>`. Lệnh so sánh trạng thái hiện tại với snapshot lúc cách ly và ghi vào audit log. Có cooldown 10 phút và giới hạn 5 lần cách ly/máy; Admin có thể bỏ qua hai giới hạn này.

**Sau khi restart:** các máy đang cách ly được nạp lại từ bảng `isolations`, nên không bị mất hiệu lực.

### Khi nào KHÔNG cách ly
- Ranh giới không rõ (cụm chồng hoặc gần cụm khác trong `shared-source-margin`): chỉ cảnh báo, yêu cầu Admin kiểm tra.
- Vùng quá lớn (vượt `boundary-scan-radius`).
- Một phần vùng chưa được tải.
- Máy không có piston, hopper hoặc dispenser.

Admin **không** thể ép cách ly trong các trường hợp trên. Chỉ cooldown và giới hạn số lần mới được bỏ qua.

---

## 7. Giới hạn Paper API (quan trọng)

1. **`BlockRedstoneEvent` không thể hủy.** Plugin không chặn được sự lan truyền tín hiệu của dust, repeater hay observer. Hệ quả: một **clock thuần redstone** (không có piston/hopper/dispenser) **không được cách ly tự động**. Plugin chỉ cảnh báo và yêu cầu Admin tháo bớt mạch. Đây là giới hạn của API, không phải lỗi thiết kế. Hướng mở rộng khả thi là lưu và tạm vô hiệu hóa các block kích hoạt xác định, nhưng cần thiết kế khôi phục trạng thái cẩn thận.
2. **TNT châm bằng redstone** không bị chặn bởi cơ chế cách ly hiện tại.
3. **Người đặt và người kích hoạt** chỉ được xác định khi có bản ghi thật (`BlockPlaceEvent`, `PlayerInteractEvent` trên công tắc, nút, đĩa áp lực). Nếu không có bản ghi, plugin hiển thị "không xác định".
4. **Cần xác nhận khi build:** một số phương thức Paper như `Bukkit.getAverageTickTime()` và `World.getBlockType(int,int,int)` được dùng theo kiến thức API hiện có. Nếu build báo lỗi "cannot find symbol" ở các dòng này, hãy đối chiếu với Javadoc Paper 1.21.11.

---

## 8. Lệnh và quyền

| Lệnh | Quyền | Mô tả |
|---|---|---|
| `/horizonai` hoặc `status` | `horizonai.use` | Tình trạng server, AI, máy |
| `analyze` | `horizonai.use` | Phân tích cục bộ và (nếu bật) AI ngay lập tức |
| `report` | `horizonai.use` | Báo cáo cục bộ |
| `optimize` | `horizonai.use` | Đề xuất tối ưu (chỉ đề xuất) |
| `history` | `horizonai.use` | 10 thao tác gần nhất trong audit log |
| `redstone list` | `horizonai.use` | Danh sách máy |
| `redstone inspect <id>` | `horizonai.use` | Chi tiết, bằng chứng, nhật ký |
| `redstone isolate <id>` | `horizonai.redstone.isolate` | Cách ly (Admin duyệt) |
| `redstone restore <id>` | `horizonai.redstone.restore` | Khôi phục |
| `whitelist list / addworld / addregion` | `horizonai.whitelist` | Quản lý whitelist, lưu vào config |
| `reload` | `horizonai.reload` | Tải lại config; giữ nguyên các máy đang cách ly |

Quyền `horizonai.notify.exclude` (mặc định: không ai có) loại người chơi khỏi cảnh báo Redstone.

---

## 9. Kiểm thử

### Đã chạy trong môi trường phát triển
- Logic thuần (`RedstoneAnalyzer`, `MachineIdentifier`, `Region`) được kiểm tra bằng harness Java độc lập với **13 kiểm tra, đạt toàn bộ**. Các kiểm tra này tương đương 9 ca JUnit trong `src/test/java` (máy bình thường không bị báo sai, máy busy không tương quan không bị kết tội, clock có tương quan đủ điều kiện, máy khác cùng hoạt động chặn cách ly, ranh giới không rõ chặn cách ly, cụm quá lớn không cách ly được...).
- **Chưa chạy được bộ JUnit thật** vì môi trường phát triển không có JUnit 5. Hãy chạy `gradle test` trên máy của bạn để xác nhận.
- Kiểm tra cú pháp và kiểu toàn bộ mã nguồn bằng `javac`. Các lỗi còn lại đều do thiếu Paper API trong môi trường, không phải lỗi logic của dự án.

### Cần kiểm thử trên server thật (chưa thực hiện)
| # | Kịch bản | Cách kiểm tra |
|---|---|---|
| 1 | Máy Redstone bình thường không bị cảnh báo sai | Đặt đèn redstone + piston nhỏ, bật tắt vài lần; `redstone list` phải ở mức NORMAL/LOW |
| 2 | Clock gây tải được phát hiện | Đặt clock observer tần số cao + farm nặng; `inspect` phải có "Tương quan: CÓ" |
| 3 | Hai máy gần nhau không bị nhầm | Đặt hai máy cách 3 block; `inspect` phải báo ranh giới không rõ |
| 4 | Cách ly một máy không ảnh hưởng máy khác | Cách ly máy A bằng lệnh, kiểm tra piston máy B vẫn chạy |
| 5 | Khôi phục đúng trạng thái | Cách ly → restore → so sánh log "Trước / Sau" |
| 6 | Người không liên quan không nhận cảnh báo | Người ở xa hơn `warning.distance` không nhận tin |
| 7 | AI mất kết nối không làm dừng plugin | Đặt endpoint sai hoặc rút key; `status` vẫn cập nhật, log ghi cảnh báo, không có lỗi |
| 8 | 40–60 người chơi | Theo dõi MSPT bằng `/horizonai status` và profiler (spark) |
| 9 | Nhiều mob farm | Đặt farm vượt `entity.mob-thresholds`; kiểm tra cảnh báo chunk |
| 10 | Nhiều máy hoạt động cùng lúc | Đặt 5+ máy; kiểm tra `max-tracked-machines` và số lần quét mỗi cửa sổ |

Chạy trên **cả Paper và Purpur** để xác nhận hành vi event và scheduler giống nhau.

---

## 10. Khóa cấu hình đã khai báo nhưng chưa có tác dụng

Các khóa dưới đây có trong `config.yml` để chuẩn bị cho bản sau. Thay đổi giá trị chưa ảnh hưởng hành vi:

- `language`, `ai.report-language` (báo cáo hiện luôn bằng tiếng Việt)
- `entity.density-radius` (hiện dùng ngưỡng `density-warning` theo chunk 16×16)
- `entity.auto-remove-entities` (luôn `false`; không có code xóa entity)
- `hopper.transfers-per-second-warning` (chưa đo tốc độ chuyển item)
- `chunk.active-chunk-warning` (chưa cảnh báo theo số chunk hoạt động)
- `optimization.suggestions-only`, `optimization.admin-approval-actions` (hành vi hiện tại đã luôn là chỉ đề xuất)
- `logging.debug`

---

## 11. Cấu trúc thư mục

```
horizonai-optimizer/
├── build.gradle.kts
├── settings.gradle.kts
├── README.md
├── src/main/resources/
│   ├── plugin.yml
│   └── config.yml
├── src/main/java/com/horizon/ai/...
└── src/test/java/com/horizon/ai/redstone/
    ├── RedstoneAnalyzerTest.java
    └── MachineIdentifierTest.java
```
