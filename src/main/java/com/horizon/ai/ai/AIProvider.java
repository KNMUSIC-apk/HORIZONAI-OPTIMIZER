package com.horizon.ai.ai;

import java.util.concurrent.CompletableFuture;

/**
 * Giao diện provider AI. Thay provider/model chỉ cần đổi config, không sửa code.
 * Mọi triển khai chỉ nhận chuỗi JSON snapshot và trả về nội dung text (kỳ vọng JSON).
 * Triển khai KHÔNG được có quyền truy cập world hay thực thi lệnh.
 */
public interface AIProvider {

    String name();

    CompletableFuture<String> complete(String systemPrompt, String userJson);
}
