package com.winter.reg.web;

import com.winter.reg.service.ActivityService;
import com.winter.reg.web.dto.ActivityRequest;
import com.winter.reg.web.dto.ActivityResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 活动查询与创建。全部要求已登录（{@code JwtAuthFilter} 保证 userId 一定在）。 */
@RestController
@RequestMapping("/api/activities")
public class ActivityController {

  private final ActivityService activityService;

  public ActivityController(ActivityService activityService) {
    this.activityService = activityService;
  }

  @PostMapping
  public ApiResponse<ActivityResponse> create(@Valid @RequestBody ActivityRequest request) {
    return ApiResponse.ok(activityService.create(request));
  }

  @GetMapping("/{id}")
  public ApiResponse<ActivityResponse> get(@PathVariable Long id) {
    ActivityResponse activity = activityService.get(id);
    return activity == null ? ApiResponse.fail(ResultCode.NOT_FOUND) : ApiResponse.ok(activity);
  }

  @GetMapping
  public ApiResponse<List<ActivityResponse>> listOpen() {
    return ApiResponse.ok(activityService.listOpen());
  }
}
