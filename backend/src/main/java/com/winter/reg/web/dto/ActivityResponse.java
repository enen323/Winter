package com.winter.reg.web.dto;

import com.winter.reg.domain.Activity;
import java.time.LocalDateTime;

/** 活动对外视图。 */
public record ActivityResponse(
    Long id, String title, int capacity, int stock, LocalDateTime startTime, String status) {

  public static ActivityResponse from(Activity a) {
    return new ActivityResponse(a.getId(), a.getTitle(), a.getCapacity(), a.getStock(), a.getStartTime(), a.getStatus());
  }
}
