package com.winter.reg.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 报名记录。
 *
 * <p>{@code (activity_id, user_id)} 上有唯一索引，是「同一用户对同一活动只有一条记录」的物理保证；
 * 取消后重新报名复用同一行，只把 {@link #status} 从 {@code CANCELED} 改回 {@code CONFIRMED}。
 */
@TableName("registration")
public class Registration {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long activityId;

  private Long userId;

  private RegistrationStatus status;

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public Long getActivityId() {
    return activityId;
  }

  public void setActivityId(Long activityId) {
    this.activityId = activityId;
  }

  public Long getUserId() {
    return userId;
  }

  public void setUserId(Long userId) {
    this.userId = userId;
  }

  public RegistrationStatus getStatus() {
    return status;
  }

  public void setStatus(RegistrationStatus status) {
    this.status = status;
  }
}
