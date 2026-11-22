package com.winter.reg.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.winter.reg.domain.Activity;
import java.util.List;
import org.apache.ibatis.annotations.Select;

/** 活动表。建活动与展示走 MyBatis-Plus 的 {@code BaseMapper}，列表用一条手写 SQL。 */
public interface ActivityMapper extends BaseMapper<Activity> {

  /** 开抢中的活动，供活动列表页使用。 */
  @Select("SELECT id, title, capacity, stock, start_time, status FROM activity WHERE status = 'OPEN' ORDER BY id DESC")
  List<Activity> selectOpen();
}
