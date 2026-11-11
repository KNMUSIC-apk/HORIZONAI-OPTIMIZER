# HorizonAI Optimizer

Plugin Paper/Purpur 1.21.x (Java 21) giám sát hiệu suất server, phát hiện máy Redstone gây quá tải và xử lý theo hai cấp: **cảnh báo** rồi **cách ly có thể hoàn tác**.

**Phiên bản này không dùng AI.** Toàn bộ phân tích do **Advanced Machine LUT** thực hiện: thuần Java, xác định (deterministic), chạy hoàn toàn cục bộ. Không cần API key, không cần Internet, không gọi dịch vụ bên ngoài nào. Logic phân tích không có đường gọi mạng nào.

> Mục `ai.*` trong config cũ đã bị loại bỏ hoàn toàn. Xem mục *7. Migration* bên dưới.

---

## 1. Kiến trúc

```
Machine Data Collector        ActivityTracker + MachineIdentifier (async, không chạm Bukkit)
        |                     MachineTracker (Main Thread): lịch sử, quét thành phần
        v
Feature Extraction            LUTFeatureExtractor → LUTFeatures (activity, clock, MSPT, tương quan, cấu tạo)
        |
        v
Advanced Machine LUT          MachineLUT (bảng tra điểm), LUTWeights, LUTThresholds
        |
        v
LUT Score Engine              LUTScoreEngine → điểm 0-100 theo 8 nhóm có trần
        |
        v
Risk Classification           LUTDecisionEngine.severityOf → NORMAL / LOW / MEDIUM / HIGH / CRITICAL
        |
        v
Confidence Engine             LUTConfidenceEngine → độ tin cậy 0.0-1.0 (tách khỏi điểm)
        |
        v
Machine Decision Engine       LUTDecisionEngine.decide → Level 0 / 1 / 2 + danh sách lý do chặn
        |                     RedstoneGuardian: Level 1 cảnh báo, Level 2 chờ Admin, Level 3 qua MachineIsolation
        v
Report                        LUTReportBuilder → báo cáo tiếng Việt, xác định, không bịa nguyên nhân
        |
        v
Admin approval / Safe actions ActionValidator + MachineIsolation (cách ly có snapshot, khôi phục được)
```

`LUTEngine` điều phối pipeline trên. Gói `lut` không import `org.bukkit`, nên có thể kiểm thử độc lập với server.

### Nguyên tắc an toàn

- LUT **không bao giờ** tự sửa block. Cách ly luôn đi qua `MachineIsolation` đã có (snapshot, khôi phục, cooldown, giới hạn số lần).
- Điểm số **không** đủ để cách ly. Level 2 cần đủ mọi cổng bằng chứng (mục 3).
- Tương quan Pearson **không** được coi là bằng chứng nhân quả. Nó chỉ là một cổng cần có.
- Clock nhanh chỉ là dấu hiệu hoạt động, không tự động bị coi là gây lag.
- Mặc định: `require-admin-approval: true`, `auto-isolate: false`.

---

## 2. Công thức chấm điểm

Điểm rủi ro = tổng 8 nhóm, mỗi nhóm có **trần cố định**. Tổng các trần bằng 100, nên kết quả luôn nằm trong [0, 100]. Trần ngăn việc cộng trùng: ΔMSPT, Pearson và độ nhất quán đều đo cùng một hiện tượng.

| # | Nhóm | Trần | Quy tắc |
|---|------|-----:|---------|
| 1 | Cường độ sự kiện | 10 | sự kiện ≥ ngưỡng: +6; ≥ 2× ngưỡng: +10 |
| 2 | Bất thường hoạt động | 22 | (chỉ khi sự kiện ≥ ngưỡng) tỉ lệ / baseline ≥ 1.0x / 2.0x / 3.0x → +8 / +14 / +20; ≥ 3 cửa sổ cao liên tiếp: +2 |
| 3 | Clock ổn định | 12 | tần số ≥ `clock-min-frequency-hz`, CV ≤ `clock-max-interval-cv`, đang hoạt động: +12 |
| 4 | Hiệu năng | 30 | ΔMSPT ≥ 1× / 2× / 3× `mspt-delta-minimum` → +8 / +14 / +20; MSPT server ≥ `mspt-critical`: +12 |
| 5 | Tương quan | 10 | Pearson ≥ `correlation-minimum`: +10 |
| 6 | Cấu tạo | 4 | có piston/hopper/dispenser: +2; ≥ 20 linh kiện redstone: +2 |
| 7 | Máy đồng thời | 4 | 1 máy khác cùng hoạt động: +2; ≥ 3 máy: +4 |
| 8 | Ổn định lịch sử | 8 | round(tỉ lệ hai nửa lịch sử có ΔMSPT đủ lớn × 8) |

Định nghĩa:
- **Tỉ lệ** = sự kiện cửa sổ hiện tại / baseline lịch sử (baseline là trung bình trượt, hệ số 0.1).
- **ΔMSPT** = trung bình MSPT khi activity > trung vị − trung bình MSPT khi activity ≤ trung vị.
- **Pearson** tính trên các cặp (activity, MSPT) trong `history-size` cửa sổ gần nhất. NaN/Infinity bị bỏ qua; phương sai bằng 0 cho kết quả "không xác định", không bao giờ tạo điểm.

Mức nghiêm trọng: 0–24 NORMAL · 25–44 LOW · ≥ `warning-score` (45) MEDIUM · ≥ `high-score` (65) HIGH · ≥ `critical-score` (80) CRITICAL.

Các ngưỡng và bảng điểm đều đọc từ config. Xem `/horizonai lut profile` để thấy giá trị đang dùng.

## 3. Độ tin cậy và quyết định

**Độ tin cậy** (0.0–1.0), độc lập với điểm:

```
conf = 0.20·sample + 0.25·correlation + 0.15·delta + 0.15·consistency + 0.10·stability + 0.15·concurrency
```

- `sample` = 0 nếu n < `min-samples`, ngược lại min(1, n/60)
- `correlation` = (r − min) / (1 − min), kẹp [0, 1]
- `delta` = min(1, ΔMSPT / (2 · mspt-delta-minimum))
- `consistency` ∈ {0, 0.5, 1}: số nửa lịch sử có ΔMSPT đủ lớn, chia 2
- `stability` = 1 / (1 + CV hoạt động)
- `concurrency` = 1 / (1 + số máy khác cùng hoạt động)
- **Cổng cứng:** ranh giới không rõ hoặc quét thành phần chưa đầy đủ → độ tin cậy = 0.

**Level 2 (chờ cách ly)** chỉ đạt khi đồng thời thỏa:

1. Điểm ≥ `high-score`
2. Đủ mẫu (n ≥ `min-samples`)
3. Tương quan hợp lệ (r ≥ `correlation-minimum`) và ΔMSPT ≥ `mspt-delta-minimum`
4. Ranh giới máy rõ ràng
5. Quét thành phần đầy đủ (không có chunk chưa tải, không vượt giới hạn)
6. Có piston/hopper/dispenser để cách ly an toàn
7. Không có máy khác cùng hoạt động
8. Độ tin cậy ≥ `minimum-confidence` (0.70)

Ngoài ra máy phải đã bị cảnh báo đủ `redstone.warnings-before-isolation` lần (lặp lại bằng chứng).

Khi chưa đủ điều kiện, báo cáo liệt kê lý do cụ thể (`/horizonai redstone inspect <id>`).

**Level 3 (cách ly)** chỉ qua `MachineIsolation`, với Admin duyệt (`/horizonai redstone isolate <id>`). Tự cách ly chỉ xảy ra khi `lut.safety.auto-isolate: true` **và** `require-admin-approval: false`.

---

## 4. Báo cáo

Báo cáo sinh từ dữ liệu đo được, không có nguyên nhân nào được bịa. Ví dụ rút gọn:

```
[HorizonAI LUT] Phân tích máy
Mã máy: HZ-12345678
Thế giới: world
Mức: HIGH | Điểm rủi ro: 78/100
Độ tin cậy: 0.86
Hoạt động:
- Sự kiện/cửa sổ: 850
- Baseline lịch sử: 220
- Tỉ lệ so với baseline: 3.86x
Hiệu năng:
- MSPT hiện tại: 68.0 ms
- ΔMSPT khi hoạt động: +14.2 ms
- Pearson: 0.79 (n=24)
Bằng chứng:
- Số sự kiện vượt baseline lịch sử 3.86 lần
- Hoạt động và MSPT tương quan dương (r=0.79)
- Suy giảm MSPT xuất hiện nhất quán ở cả hai nửa lịch sử
Quyết định: PENDING_ADMIN_APPROVAL
```

---

## 5. Lệnh

| Lệnh | Quyền | Mô tả |
|------|-------|-------|
| `/horizonai status` | `horizonai.use` | Tình trạng server, máy, trạng thái LUT |
| `/horizonai lut status` | `horizonai.use` | Trạng thái LUT, số máy đã phân tích, điểm từng máy |
| `/horizonai lut analyze` | `horizonai.use` | Báo cáo LUT cho các máy điểm cao nhất (đọc kết quả gần nhất, **không quét lại world**) |
| `/horizonai lut profile` | `horizonai.use` | Bảng điểm, ngưỡng, cờ an toàn, giới hạn đang dùng |
| `/horizonai lut reload` | `horizonai.reload` | Tải lại config và dựng lại thành phần; máy đang cách ly được giữ nguyên |
| `/horizonai analyze` | `horizonai.use` | Giống `lut analyze` |
| `/horizonai report` | `horizonai.use` | Tóm tắt một dòng cho mỗi máy |
| `/horizonai optimize` | `horizonai.use` | Đề xuất tối ưu tổng hợp (chỉ đề xuất) |
| `/horizonai redstone list` | `horizonai.use` | Danh sách máy |
| `/horizonai redstone inspect <id>` | `horizonai.use` | Chi tiết, bằng chứng, lý do chưa cách ly, nhật ký |
| `/horizonai redstone isolate <id>` | `horizonai.redstone.isolate` | Cách ly (Admin duyệt) |
| `/horizonai redstone restore <id>` | `horizonai.redstone.restore` | Khôi phục |
| `/horizonai whitelist ...` | `horizonai.whitelist` | Quản lý whitelist |
| `/horizonai history` | `horizonai.use` | 10 thao tác gần nhất |
| `/horizonai reload` | `horizonai.reload` | Tải lại config |

---

## 6. Cấu hình

Toàn bộ mục LUT nằm trong `lut:` của `config.yml`, có chú thích cho từng khóa. Giá trị sai kiểu hoặc ngoài khoảng sẽ được **ghi cảnh báo trong console** và thay bằng giá trị an toàn; cấu hình không bị bỏ qua im lặng.

Các cờ an toàn `require-correlation`, `require-clear-boundary`, `require-complete-scan` luôn được coi là bật: đặt `false` chỉ ghi cảnh báo.

## 7. Migration từ bản AI

1. **Xóa** mục `ai:` trong `plugins/HorizonAI/config.yml` (mọi khóa `ai.*`). Plugin sẽ ghi cảnh báo nếu còn mục này. Không cần và không nên giữ API key trong config.
2. **Chuyển** các khóa phân tích sang `lut:` (xem config mẫu). Nếu không đổi, các khóa cũ `redstone.min-correlation`, `redstone.min-samples`, `redstone.min-mspt-correlation-delta`, `redstone.clock-*`, `redstone.auto-isolate` vẫn được đọc như khóa dự phòng và có ghi log.
3. **Chạy** `/horizonai lut reload` (không cần khởi động lại server).
4. **Xóa** biến môi trường `HORIZONAI_AI_KEY` nếu có. Plugin không đọc nó nữa.
5. Dữ liệu cũ (`horizonai.db`, máy đang cách ly, snapshot cách ly) được giữ nguyên và tương thích.

Đã xóa: `AIAnalyzer`, `AIProvider`, `OpenAIProvider`, `AIResponseParser`, `CircuitBreaker`, `LocalAnalyzer`, `HealthReport`, `RedstoneAnalyzer`, và thư viện Jackson. Chỉ còn SQLite JDBC.

---

## 8. Giới hạn Paper API và giới hạn đã biết

1. **`BlockRedstoneEvent` không thể hủy.** Plugin không chặn được sự lan truyền tín hiệu của dust, repeater hay observer. Một **clock thuần redstone** (không có piston/hopper/dispenser) **không được cách ly tự động**; plugin chỉ cảnh báo và yêu cầu Admin tháo bớt mạch.
2. **Cách ly chỉ dùng piston, hopper, dispenser** qua các sự kiện có thể hủy (`BlockPistonExtendEvent`, `BlockPistonRetractEvent`, `BlockDispenseEvent`, `InventoryMoveItemEvent`). Không xóa block, không thay đổi world.
3. **Phạm vi quét thành phần:** scanner hiện có đếm piston, hopper, dispenser, observer, TNT và linh kiện redstone. Sticky piston, dropper, repeater, comparator, redstone torch và redstone block **chưa được đếm riêng**. Đây là giới hạn của bản này, không phải tính năng đã có.
4. **Ngân sách thời gian là mềm:** LUT ngừng chấm điểm thêm máy trong cửa sổ khi vượt `max-analysis-ms-per-window`, nhưng không ngắt giữa chừng một máy. Các máy còn lại vẫn ghi lịch sử để không lệch cặp activity/MSPT.
5. **LUT chạy trên Main Thread.** Dữ liệu là các đối tượng `Machine` (không thread-safe) nên không chuyển sang async. Khối lượng được chặn bởi `max-machines-per-window` và `history-size` ≤ 120.
6. **Dashboard web chưa có.** Đây là phần còn thiếu, sẽ làm ở bản sau vì cần thiết kế bảo mật riêng.
7. **Không có khả năng tự học.** Ngưỡng và bảng điểm là giá trị khởi đầu có thể cấu hình; chúng không được điều chỉnh tự động theo server.

---

## 9. Build

```bash
gradle clean build
```

JAR tạo tại `build/libs/HorizonAI-Optimizer-1.0.0.jar`. Yêu cầu JDK 21 và Gradle 8.x. Paper API lấy từ `repo.papermc.io`. SQLite JDBC được Paper tải lúc khởi động theo mục `libraries` trong `plugin.yml`, nên không bị shade vào JAR.

Workflow `.github/workflows/build.yml` build trên JDK 21 với Gradle 8.14.3, chạy test và upload JAR làm artifact. Chạy thủ công: tab **Actions → Build → Run workflow**.

## 10. Kiểm thử

Tập `src/test/java/com/horizon/ai/lut/LUTEngineTest.java` gồm 20 kiểm thử:

| # | Kịch bản |
|---|----------|
| 1 | Máy bình thường giữ mức NORMAL |
| 2 | Hoạt động cao nhưng ΔMSPT thấp không bị xếp nghiêm trọng |
| 3 | Clock tần số cao không phải bằng chứng gây lag |
| 4 | Phát hiện đột biến hoạt động |
| 5 | Tương quan dương mạnh: được ghi nhận, nhưng vẫn cần Admin |
| 6 | Tương quan âm không được cộng điểm |
| 7 | Không đủ mẫu chặn cách ly |
| 8 | Chuỗi hoạt động hằng số được xử lý an toàn |
| 9 | Chuỗi MSPT hằng số được xử lý an toàn |
| 10 | Nhiều máy cùng hoạt động chặn cách ly |
| 11 | Ranh giới mơ hồ chặn cách ly (độ tin cậy = 0) |
| 12 | Quét thành phần chưa đầy đủ chặn cách ly |
| 13 | MSPT rất cao cộng điểm nguy hiểm |
| 14 | Giá trị số không hợp lệ (NaN, Infinity, âm) không làm sập engine |
| 15 | Điểm bị kẹp trong [0, 100]; trần nhóm được giữ |
| 16 | Độ tin cậy tách khỏi điểm và phụ thuộc số mẫu |
| 17 | Đủ bằng chứng chỉ đạt Level 2, không tự cách ly |
| 18 | Admin duyệt được cưỡng chế |
| 19 | Điểm cao không có tương quan không thể cách ly |
| 20 | Ngưỡng sai thứ tự được sửa về hợp lệ |

Các kiểm thử này chỉ dùng logic thuần: không cần Paper API, mạng hay API key. `MachineIdentifierTest` (3 ca) được giữ nguyên.

Chạy: `gradle test`.

## 11. Triển khai trên Pterodactyl

1. Build JAR (mục 9) hoặc tải artifact từ GitHub Actions.
2. Tải `HorizonAI-Optimizer-1.0.0.jar` vào thư mục `plugins/` của server.
3. Khởi động server một lần để tạo `plugins/HorizonAI/config.yml` và `horizonai.db`.
4. Dừng server, sửa `config.yml` theo mục 6–7 (xóa mục `ai:`, chỉnh `lut:` nếu cần).
5. Khởi động lại server. Không cần biến môi trường hay API key.
6. Kiểm tra console: `HorizonAI đã bật. Advanced Machine LUT: BẬT (cục bộ, không cần API key)`.
7. Trong game (OP): `/horizonai lut status`, `/horizonai lut profile`.

## 12. Cấu trúc mã nguồn

```
com.horizon.ai
├── HorizonAI.java                  Điểm vào, khởi tạo/reload toàn bộ thành phần
├── lut/                            Advanced Machine LUT (không import Bukkit)
│   ├── LUTEngine                   Điều phối pipeline
│   ├── LUTInput / LUTFeatures      Đầu vào snapshot và đặc trưng đã trích xuất
│   ├── LUTFeatureExtractor         Trích xuất đặc trưng
│   ├── MachineLUT                  Bảng tra điểm theo bậc
│   ├── LUTScore / LUTScoreEngine   Điểm 0-100 theo nhóm có trần
│   ├── LUTConfidenceEngine         Độ tin cậy 0-1
│   ├── LUTDecision / LUTDecisionEngine   Mức nghiêm trọng, Level, lý do chặn
│   ├── LUTReportBuilder            Báo cáo tiếng Việt xác định
│   ├── LUTWeights / LUTThresholds / LUTSafety / LUTProfile   Cấu hình bất biến
│   └── LUTStats                    Thống kê an toàn với NaN/Infinity
├── redstone/                       Theo dõi, phân cụm, quét, cách ly
│   ├── RedstoneGuardian            Điều phối mỗi cửa sổ, Level 1/2
│   ├── MachineTracker / MachineIdentifier / ActivityTracker / ActorTracker
│   ├── Machine                     Trạng thái máy (kèm lastLut)
│   └── MachineIsolation            Cách ly/khôi phục có snapshot
├── optimizer/                      ActionValidator, WarningService
├── performance/                    PerformanceMonitor, PerfSample
├── chunk/                          ChunkHealthScanner
├── events/                         Listener cách ly và sự kiện redstone
├── commands/                       HorizonCommand
├── storage/                        Database (SQLite)
└── utils/                          Settings, Region, Severity, Text, BlockPos
```
