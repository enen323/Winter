package com.winter.reg.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.winter.reg.domain.User;
import org.apache.ibatis.annotations.Select;

/** 用户表。注册/登录的查询走 MyBatis-Plus 的 {@code BaseMapper}。 */
public interface UserMapper extends BaseMapper<User> {

  @Select("SELECT id, username, password FROM app_user WHERE username = #{username}")
  User selectByUsername(String username);
}
