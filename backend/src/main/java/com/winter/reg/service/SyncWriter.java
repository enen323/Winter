package com.winter.reg.service;

import com.winter.reg.mapper.ActivityMapper;
import com.winter.reg.mapper.RegistrationMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 报名写路径：只做「占一行 + 扣一格」，不做任何时间/状态判断。
 *
 * <p>**必须是独立的 bean**，不能是 {@code RegistrationService} 实现里的私有方法：
 * {@code @Transactional} 靠 Spring AOP 代理生效，同类内自调用不走代理，事务会静默失效，
 * 回滚也就没了。L1/L2 复用它，所以「下单顺序」在整个项目里只有这一处。
 *
 * <p>类和方法都必须是 {@code public}：Spring 的 {@code AnnotationTransactionAttributeSource}
 * 默认只给 public 方法织事务，非 public 的 {@code @Transactional} 会被静默跳过。
 */
@Component
public class SyncWriter {

  private final RegistrationMapper registrationMapper;
  private final ActivityMapper activityMapper;

  public SyncWriter(RegistrationMapper registrationMapper, ActivityMapper activityMapper) {
    this.registrationMapper = registrationMapper;
    this.activityMapper = activityMapper;
  }

  /**
   * 三步：拿排他锁 → 插记录 → 扣库存。
   *
   * <p>锁必须在最前面，见 {@link ActivityMapper#lockActivityRow}。晚一步死锁率反而更高
   * （0 → 146 / 200 轮）：等 X 的时候，手里还捏着 INSERT 外键检查给的那把 S。
   * 顺便读到的 stock 用来短路「已满」，否则要先插一行再回滚——2500 个被拒请求就是 2500 次白写。
   *
   * @return {@link RegResult#SUCCESS} / {@link RegResult#ALREADY} / {@link RegResult#NOT_FOUND}
   * @throws StockExhaustedException 名额已满，由事务回滚撤掉刚插入的那行
   */
  @Transactional
  public RegResult write(Long activityId, Long userId) {
    Integer stock = activityMapper.lockActivityRow(activityId);
    if (stock == null) {
      // 调用方刚查过活动存在，这只防并发删除
      return RegResult.NOT_FOUND;
    }
    if (stock <= 0) {
      throw new StockExhaustedException(activityId);
    }

    // 顺序是**先插记录、后扣库存**，反了会在唯一索引命中时「吞名额」——不超卖，但少卖，
    // 是本项目最阴的一类 bug，压测只看到 QPS 正常、成交数少了几个根本发现不了。
    if (registrationMapper.tryConfirm(activityId, userId) == 0) {
      return RegResult.ALREADY;
    }
    if (activityMapper.decrementStockIfPositive(activityId) == 0) {
      // 理论到不了：排他锁一直持有，上面已看过 stock > 0。留着当兜底。
      throw new StockExhaustedException(activityId);
    }
    return RegResult.SUCCESS;
  }
}
