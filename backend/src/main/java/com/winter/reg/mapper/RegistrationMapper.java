package com.winter.reg.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.winter.reg.domain.Registration;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/** 报名记录。 */
public interface RegistrationMapper extends BaseMapper<Registration> {

  /**
   * 报名写路径第一步：插一行，靠 {@code (activity_id, user_id)} 唯一索引挡重复。
   *
   * @return 1 插入成功；0 该用户已有记录
   */
  @Insert("INSERT INTO registration (activity_id, user_id, status) "
      + "VALUES (#{activityId}, #{userId}, 'CONFIRMED') ON DUPLICATE KEY UPDATE id = id")
  int tryConfirm(@Param("activityId") Long activityId, @Param("userId") Long userId);
}
