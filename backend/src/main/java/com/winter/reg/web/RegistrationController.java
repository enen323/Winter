package com.winter.reg.web;

import com.winter.reg.auth.JwtAuthFilter;
import com.winter.reg.service.RegResult;
import com.winter.reg.service.RegistrationService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 报名。userId 取自 token，**不接收 body 里的 userId**（否则可以替别人报名，也会破坏压测前提①）。 */
@RestController
@RequestMapping("/api/registrations")
public class RegistrationController {

  private final RegistrationService registrationService;

  public RegistrationController(RegistrationService registrationService) {
    this.registrationService = registrationService;
  }

  @PostMapping("/{activityId}")
  public ApiResponse<Void> register(
      @PathVariable Long activityId, @RequestAttribute(JwtAuthFilter.ATTR_USER_ID) Long userId) {
    return switch (registrationService.register(activityId, userId)) {
      case SUCCESS -> ApiResponse.ok(null);
      case FULL -> ApiResponse.fail(ResultCode.FULL);
      case ALREADY -> ApiResponse.fail(ResultCode.ALREADY);
      case NOT_STARTED -> ApiResponse.fail(ResultCode.NOT_STARTED);
      case CLOSED -> ApiResponse.fail(ResultCode.CLOSED);
      case NOT_FOUND -> ApiResponse.fail(ResultCode.NOT_FOUND);
      case RATE_LIMITED -> ApiResponse.fail(ResultCode.RATE_LIMITED);
      case DEGRADED -> ApiResponse.fail(ResultCode.DEGRADED);
    };
  }
}
