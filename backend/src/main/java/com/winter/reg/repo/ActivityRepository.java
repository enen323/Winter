package com.winter.reg.repo;

import com.winter.reg.domain.Activity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ActivityRepository extends JpaRepository<Activity, Long> {

  /** 开抢中的活动，供活动列表页使用。 */
  List<Activity> findByStatusOrderByIdDesc(String status);
}
