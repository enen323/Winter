package com.winter.reg.service;

import com.winter.reg.domain.Activity;
import com.winter.reg.mapper.ActivityMapper;
import com.winter.reg.web.dto.ActivityRequest;
import com.winter.reg.web.dto.ActivityResponse;
import java.util.List;
import org.springframework.stereotype.Service;

/** 活动 CRUD。名额的权威在 Redis（L1 起），这里只是建活动与展示。 */
@Service
public class ActivityService {

  /** 开抢中的活动状态值，与 DB 里的字符串一致。 */
  public static final String STATUS_OPEN = "OPEN";

  private final ActivityMapper activityMapper;

  public ActivityService(ActivityMapper activityMapper) {
    this.activityMapper = activityMapper;
  }

  /** 名额必须 {@code > 0}，否则 {@link IllegalArgumentException}（被映射为 {@code code=1500}）。 */
  public ActivityResponse create(ActivityRequest request) {
    if (request.capacity() <= 0) {
      throw new IllegalArgumentException("名额必须大于 0");
    }
    Activity activity = new Activity();
    activity.setTitle(request.title());
    activity.setCapacity(request.capacity());
    activity.setStock(request.capacity());
    activity.setStartTime(request.startTime());
    activity.setStatus(STATUS_OPEN);
    activityMapper.insert(activity);
    return ActivityResponse.from(activity);
  }

  /** 不存在返回 {@code null}，由控制器映射为 {@code code=1005}。 */
  public ActivityResponse get(Long id) {
    Activity activity = activityMapper.selectById(id);
    return activity == null ? null : ActivityResponse.from(activity);
  }

  public List<ActivityResponse> listOpen() {
    return activityMapper.selectOpen().stream()
        .map(ActivityResponse::from)
        .toList();
  }
}
