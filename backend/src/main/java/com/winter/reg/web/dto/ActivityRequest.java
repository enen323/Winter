package com.winter.reg.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

/** 建活动请求体。注意没有 {@code stock} —— 库存恒等于名额，建活动时由服务端初始化。 */
public record ActivityRequest(
    @NotBlank(message = "标题不能为空") @Size(max = 200, message = "标题最长 200") String title,
    @Positive(message = "名额必须大于 0") int capacity,
    @NotNull(message = "开抢时间不能为空") LocalDateTime startTime) {}
