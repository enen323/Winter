package com.winter.reg.service;

import com.winter.reg.domain.Activity;
import com.winter.reg.repo.ActivityRepository;
import com.winter.reg.web.dto.ActivityRequest;
import com.winter.reg.web.dto.ActivityResponse;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 活动 CRUD。名额的权威在 Redis（L1 起），这里只是建活动与展示。 */
@Service
public class ActivityService {

  /** 开抢中的活动状态值，与 DB 里的字符串一致。 */
  public static final String STATUS_OPEN = "OPEN";

  private final ActivityRepository activityRepository;

  public ActivityService(ActivityRepository activityRepository) {
    this.activityRepository = activityRepository;
  }

  /** 名额必须 {@code > 0}，否则 {@link IllegalArgumentException}（被映射为 {@code code=1500}）。 */
  @Transactional
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
    return ActivityResponse.from(activityRepository.save(activity));
  }

  /** 不存在返回 {@code null}，由控制器映射为 {@code code=1005}。 */
  @Transactional(readOnly = true)
  public ActivityResponse get(Long id) {
    return activityRepository.findById(id).map(ActivityResponse::from).orElse(null);
  }

  @Transactional(readOnly = true)
  public List<ActivityResponse> listOpen() {
    return activityRepository.findByStatusOrderByIdDesc(STATUS_OPEN).stream()
        .map(ActivityResponse::from)
        .toList();
  }
}
