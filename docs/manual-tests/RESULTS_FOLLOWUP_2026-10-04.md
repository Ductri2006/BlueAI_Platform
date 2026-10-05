# Báo cáo hoàn thiện bổ sung — 04/10/2026

## Kết luận

Bản sửa bổ sung đã chạy trên QA riêng và sẵn sàng để bạn tự kiểm lại. **Chưa duyệt production, chưa commit/push. Giữ Tailwind 3 theo lựa chọn của bạn.** Không thay đổi stack chính ở cổng 3000.

| Ledger tích lũy 90 tiêu chí gốc | PASS | PARTIAL | FAIL | BLOCKED | NOT RUN |
| --- | ---: | ---: | ---: | ---: | ---: |
| Trước bước bổ sung | 65 | 16 | 1 | 0 | 8 |
| Sau bước bổ sung | 73 | 8 | 1 | 0 | 8 |

Đây không phải 73 bài UI vừa chạy. Mỗi case có provenance riêng; kết quả không đổi được giữ từ vòng trước. Không đánh dấu skip hoặc gate hạ tầng chưa chạy là PASS. Chi tiết: [ledger mới](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/docs/manual-tests/qa-manual-ledger-followup-2026-10-04.json>), [manifest kiểm chứng](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/docs/manual-tests/qa-verification-summary-followup-2026-10-04.json>). Báo cáo vòng trước được giữ nguyên, không viết lại bằng chứng cũ.

## 1. Upload: kiểm nội dung thật, không chỉ đuôi tệp

Ví dụ, đổi tên một chương trình thành `guide.pdf` không biến nó thành PDF. Trước đây giới hạn extension chưa đủ để phát hiện trường hợp này.

- `DocumentContentValidator.java` mới: PDF kiểm signature; TXT/MD/CSV phải là UTF-8 hợp lệ, không có ký tự điều khiển nhị phân. DOCX phải có các entry cấu trúc bắt buộc; từ chối traversal, entry trùng, macro, embedded objects và dữ liệu giải nén vượt giới hạn.
- Giới hạn DOCX: 2.048 entry, 16 MiB/entry, tổng giải nén 64 MiB. Không giải nén theo đường dẫn do người dùng cung cấp.
- `DocumentService.java`: kiểm filename thô trước khi chuẩn hóa; ghi bằng tên server sinh, không ghi đè; đếm byte thực khi streaming, đối chiếu metadata, xóa file tạm khi reject trước khi tạo record/queue.
- `BlueAiApi.uploadDocument`: báo tiếng Việt cho tệp rỗng, sai extension hoặc lớn hơn 20 MiB trước khi gửi network. Java vẫn kiểm độc lập; frontend không phải ranh giới bảo mật.
- Tệp quá mức trả 413 qua ingress thật; 2 MiB và đúng 20 MiB được nhận. `MiB` ở đây là 1.048.576 byte, không phải 1.000.000 byte.

File sản phẩm:

- [DocumentContentValidator.java](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/src/main/java/com/blueai/rag/document/service/DocumentContentValidator.java>)
- [DocumentService.java](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/src/main/java/com/blueai/rag/document/service/DocumentService.java>)
- [blueAiApi.ts](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/FE/src/api/blueAiApi.ts>)

Kiểm chứng: `DocumentUploadIntegrationTest.java` có 28 executions, frontend có 5 test upload bổ sung; real ingress và thao tác chọn tệp 21 MiB trên browser đều đạt. Không tạo record mới cho tệp bị reject.

**Giới hạn:** signature/ZIP structure không phải antivirus, không chứng minh mọi PDF/DOCX đều parse được. DOCX chưa được kiểm đầy đủ XML/Office semantics. Parser PDF vẫn cần đánh giá sandbox/CPU/memory timeout nếu mở upload tài liệu không tin cậy rộng rãi. Không nhận bộ kiểm này là bảo đảm chống mọi parser exploit/zip bomb.

## 2. Worker: lỗi an toàn, retry hữu hạn, kết quả cũ không ghi đè

`ingestion.py` đưa parser blocking sang thread để không khóa event loop; reject dữ liệu rỗng/quá mức và nội dung không trích xuất được trước khi index. Lỗi gửi về Java dùng thông điệp an toàn, không dùng exception body/URL có thể chứa credential từ provider. HTTP 409 của claim hết hạn được bỏ qua; một kết quả cũ không làm dừng các tài liệu khác trong batch.

Đã bổ sung test Java cho claim hết hạn rồi claim mới, stale download/result 409, soft-delete khi worker đang xử lý và ba lần thất bại → FAILED. Worker không được tự báo READY cho dữ liệu trống; kết quả READY của attempt cũ không ghi đè trạng thái mới.

File: [ingestion.py](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/feature-rag-chatbot/rag-chatbot/ingestion.py>), [test_ingestion_security.py](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/feature-rag-chatbot/rag-chatbot/tests/test_ingestion_security.py>), [WorkerDocumentIntegrationTest.java](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/src/test/java/com/blueai/rag/document/controller/WorkerDocumentIntegrationTest.java>). `test_ingestion.py` cập nhật kỳ vọng lỗi đã che thông tin.

Thread không phải process sandbox, cũng không tự giới hạn mọi tiêu thụ RAM/CPU của parser. Kiểm thử provider error dùng mock/indexer giả, không gây lỗi hoặc spam provider thật. Log redaction mới áp dụng cho nhánh lỗi tài liệu đã kiểm, không phải cam kết mọi log/config URL trong hệ thống đều đã được che.

## 3. Redis: không mất bảo vệ quota khi Redis ngắt

`WidgetRateLimiter.java` trước đây có thể quay về bộ đếm RAM khi Redis cấu hình bật nhưng không hoạt động. Nhiều API instance sẽ có bộ đếm riêng, không còn quota chung.

Nay khi Redis được bật, không lấy được counter thì trả **503 `WIDGET_PROTECTION_UNAVAILABLE`**, không tiếp tục công việc mới bằng counter cục bộ. RAM chỉ dùng khi Redis được cố ý tắt cho development; tối đa 10.000 cửa sổ đang theo dõi, không loại cửa sổ còn hiệu lực để né quota. Bộ đếm tăng và TTL Redis vẫn atomic bằng Lua. Metric `redis_fallback` giữ tên cũ để tương thích, nhưng rate limiter không còn fallback khi Redis bật.

Đã chạy drill thật trên Redis của QA:

1. Trước outage, hoàn tất một request Dialog không gọi Core.
2. Stop Redis: tạo phiên/gửi request mới trả 503; replay request đã hoàn tất vẫn 200 và cùng kết quả.
3. Start Redis: health phục hồi sau **9.742 ms**; tạo phiên và request mới lại thành công.
4. Vượt cửa sổ quota phiên: 429 `WIDGET_RATE_LIMITED` có `Retry-After`.

Hai lượt harness ban đầu chưa đạt được giữ riêng. Lượt đầu budget phục hồi chỉ 6 giây; log có `UnknownHostException: redis`, phù hợp việc Java cache DNS âm mặc định 10 giây. Lượt thứ hai poll tạo session quá nhanh chạm burst limit Nginx. Harness cuối poll health ở loopback QA trong budget 30 giây rồi xác minh request thật qua ingress; **không tắt/nới rate limit để lấy PASS**. Tham khảo [Java networking properties](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/net/doc-files/net-properties.html).

File: [WidgetRateLimiter.java](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/src/main/java/com/blueai/studio/service/WidgetRateLimiter.java>), `WidgetRateLimiterTest.java`, `BE/PRODUCTION_DEPLOYMENT.md`, [kết quả drill cuối](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/docs/manual-tests/qa-followup-api-results-final-2026-10-04.json>). 5 test limiter đạt. Không khẳng định các distributed lock/context store khác đều có cùng failure policy hoặc mọi race giữa các bước đã được loại bỏ.

## 4. Migration, input và XSS

`MySqlLegacyMigrationIntegrationTest.java` mới tạo schema V1/V3 trên hai DB MySQL 8.4 riêng, seed user/conversation/message/document rồi nâng thẳng V9. Kiểm dữ liệu, checksum history, validate và rerun no-op. **2 executions đạt; không baseline/repair/clean dữ liệu thật.** Helper sở hữu fixture bằng label và dọn đúng container/network do nó tạo.

`ConversationIntegrationTest.java` bổ sung SQL-shaped title/search, escape wildcard, owner isolation, malformed JSON, title quá dài và field tampering. ID/owner/role phải do server quyết định, không theo field được người gọi thêm vào.

`contentSecurity.test.tsx` mới có 12 executions cho user/assistant Markdown, script/img/iframe/javascript-link và tên node/validation error. Browser thật đã mở message, chọn node, gửi câu preview và xác nhận payload chỉ là text, không có injected img/script DOM nodes. Bot QA chỉ publish để thử widget, **không activate hoặc đổi bot toàn cục của Hỏi BlueAI**.

File: [legacy migration test](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/src/test/java/com/blueai/migration/MySqlLegacyMigrationIntegrationTest.java>), [conversation test](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/BE/src/test/java/com/blueai/rag/conversation/controller/ConversationIntegrationTest.java>), [contentSecurity.test.tsx](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/FE/src/components/chat/contentSecurity.test.tsx>).

CI `verify.yml` thêm DB fixture V1/V3 và migration test, đồng thời audit npm runtime. Không dùng audit runtime để che finding build dependencies. CI GitHub cho working tree này chưa chạy vì chưa push.

## 5. Kết quả chạy lại

| Nhóm | Kết quả |
| --- | --- |
| Backend full `verify` | 56 suites, 402 entries: **372 executed PASS, 30 skipped**, 0 failure/error |
| MySQL legacy V1/V3 chạy riêng | 2/2 PASS; không cộng thành unique tests với conditional suite |
| Frontend | **123/123 PASS**, TypeScript/Vite build và Docker build đạt |
| RAG | **70/70 PASS** trên runtime mới, network disabled trong unit suite |
| Follow-up qua ingress thật | **6/6 PASS**, không gọi provider chat |
| Follow-up UI | Message/node/assistant XSS và lỗi upload quá mức đạt, có screenshot |
| Compose/Nginx/Alloy | Contract và Nginx live ingress test đạt; local, không thay HTTPS/VPS thật |
| Artifact secret scan | Đúng 3 image đang chạy: **0 candidate findings**, không chứng minh key lịch sử đã revoke |
| Image vulnerability scan mới | API/FE 0 HIGH/CRITICAL; RAG 45 OS HIGH, 0 CRITICAL |
| Npm audit mới | Runtime 0; all dependencies 5 HIGH, 0 CRITICAL; giữ Tailwind 3 |

Trivy vulnerability scan exit 1 do còn finding RAG, không phải scanner crash. Lần chạy secret scanner song song ban đầu không hoàn tất; chạy lại tuần tự đã đạt đủ ba image. Không dùng artifact của lần scan không hoàn tất để kết luận.

## 6. Các gate còn mở

**SEC-05 chưa đạt:** Tailwind 3 build-chain vẫn có 5 HIGH; RAG OS còn 45 HIGH theo scanner. Chưa đủ bằng chứng để tự gọi chúng là false positive/không ảnh hưởng. Không bỏ kiểm chữ ký/ngày apt hoặc force-upgrade framework trái lựa chọn của bạn.

**8 PARTIAL:** REL-01 (release còn uncommitted); REL-06 (startup prod/HTTPS staging); API-06 (đủ quota/IP variants); SEC-02 (ma trận input toàn phạm vi); SEC-04 (secrets lịch sử/rotation thực); OPS-06 (promotion/rollback staging); OPS-07 (metrics/receiver team thật); OPS-08 (retention/lifecycle/socket hardening).

**8 NOT RUN:** REL-07/08 và OPS-09…14 cần registry/release approval, VPS, domain/TLS/firewall, proxy/hai IP thật, load/soak, encrypted off-host backup/RPO/RTO, provider billing policy và team approval/writer drain. Không thể đóng bằng việc đổi mã nguồn hay nhìn local xanh.

GitNexus đã được dùng để rà tác động và hồi quy; sau refresh, tracked working-tree diff ghi 29 files/108 symbols/521 flows, mức critical về phạm vi, chủ yếu auth client dùng chung. Đây không phải 521 lỗi hoặc CVE critical. Graph có giới hạn phân tích và không thay review framework/config/file mới. `git diff --check` đạt; vẫn có warning/deprecation từ dependency/tooling cũ, không nhận là zero warning.

## 7. Môi trường bàn giao

QA tại **http://localhost:23000**, project `blueai-preprod-20261003`; 5 dịch vụ healthy. API/RAG/FE dùng tag `followup-20261004`, exact IDs/hash nguồn ở manifest. Cổng 3000 vẫn là stack chính cũ, không dùng để đánh giá bản sửa bổ sung.

Ở lượt kiểm bàn giao cuối, cả 5 container QA đã dừng cùng lúc với exit 255; chưa xác định nguyên nhân host/engine từ sự kiện này, không suy là một lỗi ứng dụng đã được sửa. Đã khởi động lại đúng QA, giữ nguyên volumes. Kiểm lại 5 health checks, HTTP frontend/API/RAG 200, document READY/1 chunk, conversation còn tồn tại và preview bot vẫn không active/Core-disabled: [handoff check](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/docs/manual-tests/qa-handoff-results-followup-2026-10-04.json>) đạt. Không restart stack chính để xử lý sự kiện đó.

Tài liệu QA `b35c58bf-d447-47cb-90b5-583d82bb489a` vẫn READY/1 chunk. Bản FAILED cũ giữ làm bằng chứng, không tự chuyển sang READY. Các CSV 2/20 MiB do harness tạo đã soft-delete, không dùng làm KB; tombstone/file byte còn theo retention, không purge dữ liệu người dùng. Redis đã bật lại. Container/network MySQL fixture đã dọn, dữ liệu QA chính giữ nguyên. Tệp giả 21 MiB ở thư mục temp riêng được giữ để bạn có thể thử lại.

Các helper mới trong `docs/manual-tests`: `qa-followup-start.ps1`, `compose.followup.yaml`, `qa-followup-api.mjs`, `qa-followup-mysql.ps1`, `qa-browser-upload-fixture.mjs`, `qa-image-secret-scan.mjs`, `qa-followup-vulnerability-scan.mjs`, `qa-followup-finalize.mjs`. Chúng dành riêng QA, không phải script deploy production. Không chạy hai scanner cùng cache đồng thời. Finalizer ghi file follow-up riêng, không gọi finalizer cũ có số test hardcode của vòng trước.

`qa-followup-handoff-check.mjs` kiểm read-only readiness/fixture sau restart; đăng nhập fixture QA để đọc metadata, không gọi provider hoặc ghi token vào báo cáo.

Hướng dẫn tự kiểm: [SELF_TEST_FOLLOWUP_2026-10-04.md](<D:/ThucTap/RAG(BlueComAI)/BlueAI_Platform_repo/docs/manual-tests/SELF_TEST_FOLLOWUP_2026-10-04.md>).
