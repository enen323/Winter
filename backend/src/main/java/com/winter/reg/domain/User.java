package com.winter.reg.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 用户。表名 {@code app_user} —— MySQL 8 把 {@code USER} 当保留字。 */
@TableName("app_user")
public class User {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String username;

  /** BCrypt 哈希，绝不存明文。 */
  private String password;

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getUsername() {
    return username;
  }

  public void setUsername(String username) {
    this.username = username;
  }

  public String getPassword() {
    return password;
  }

  public void setPassword(String password) {
    this.password = password;
  }
}
