package com.winter.reg.service;

/**
 * 名额已满。由 {@link SyncWriter#write} 抛出，让 {@code @Transactional} 回滚，
 * 把刚插入的那行报名记录撤掉——所以这个方法必须抛异常，不能只返回一个状态码。
 */
public class StockExhaustedException extends RuntimeException {

  public StockExhaustedException(Long activityId) {
    super("名额已满: activityId=" + activityId);
  }

  public StockExhaustedException(String message) {
    super(message);
  }
}
